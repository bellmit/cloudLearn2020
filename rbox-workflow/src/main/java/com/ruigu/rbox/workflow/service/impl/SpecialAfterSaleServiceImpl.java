package com.ruigu.rbox.workflow.service.impl;

import com.alibaba.excel.EasyExcelFactory;
import com.alibaba.excel.ExcelWriter;
import com.querydsl.core.QueryResults;
import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.ruigu.rbox.cloud.kanai.model.YesOrNoEnum;
import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.cloud.kanai.util.JsonUtil;
import com.ruigu.rbox.cloud.kanai.util.TimeUtil;
import com.ruigu.rbox.workflow.constants.RedisKeyConstants;
import com.ruigu.rbox.workflow.exceptions.GlobalRuntimeException;
import com.ruigu.rbox.workflow.exceptions.VerificationFailedException;
import com.ruigu.rbox.workflow.feign.PassportFeignClient;
import com.ruigu.rbox.workflow.manager.PassportFeignManager;
import com.ruigu.rbox.workflow.manager.SpecialAfterSaleApplyManager;
import com.ruigu.rbox.workflow.manager.SpecialAfterSaleLogManager;
import com.ruigu.rbox.workflow.manager.UserManager;
import com.ruigu.rbox.workflow.model.dto.*;
import com.ruigu.rbox.workflow.model.entity.*;
import com.ruigu.rbox.workflow.model.enums.*;
import com.ruigu.rbox.workflow.model.request.*;
import com.ruigu.rbox.workflow.model.request.lightning.AddSpecialAfterSaleApplyRequest;
import com.ruigu.rbox.workflow.model.vo.*;
import com.ruigu.rbox.workflow.repository.SpecialAfterSaleApplyApproverRepository;
import com.ruigu.rbox.workflow.repository.SpecialAfterSaleApplyRepository;
import com.ruigu.rbox.workflow.repository.SpecialAfterSaleDetailRepository;
import com.ruigu.rbox.workflow.repository.SpecialAfterSaleReviewNodeRepository;
import com.ruigu.rbox.workflow.service.*;
import com.ruigu.rbox.workflow.supports.ObjectUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author liqingtian
 * @date 2020/08/11 14:55
 */
@Service
public class SpecialAfterSaleServiceImpl implements SpecialAfterSaleService {

    @Resource
    private WorkflowInstanceService workflowInstanceService;
    @Resource
    private WorkflowTaskService workflowTaskService;
    @Resource
    private SpecialAfterSaleApplyManager specialAfterSaleApplyManager;
    @Resource
    private SpecialAfterSaleApplyRepository specialAfterSaleApplyRepository;
    @Resource
    private SpecialAfterSaleDetailRepository specialAfterSaleDetailRepository;
    @Autowired
    private SpecialAfterSaleApplyRepository repository;
    @Resource
    private SpecialAfterSaleReviewNodeRepository specialAfterSaleReviewNodeRepository;
    @Resource
    private SpecialAfterSaleLogManager specialAfterSaleLogManager;
    @Resource
    private SpecialAfterSaleConfigService specialAfterSaleConfigService;
    @Resource
    private SpecialAfterSaleQuotaService specialAfterSaleQuotaService;
    @Resource
    private PassportFeignManager passportFeignManager;
    @Resource
    private UserManager userManager;
    @Resource
    private QuestNoticeService questNoticeService;
    @Value("${rbox.workflow.definition.special-after-sale}")
    private String sasDefinitionKey;
    @Autowired
    private PassportFeignClient passportFeignClient;

    @Autowired
    private JPAQueryFactory queryFactory;

    @Autowired
    private SpecialAfterSaleApplyApproverRepository specialAfterSaleApplyApproverRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final static String EXCEL_NAME = "??????????????????????????????";

    private final static String FILE_TYPE = ".xlsx";

    private final Map<Integer, String> logActionDict;

    private final Map<Integer, String> applyStatusDict;

    {
        logActionDict = new HashMap<>(16);
        for (SpecialAfterSaleLogActionEnum action : SpecialAfterSaleLogActionEnum.values()) {
            logActionDict.put(action.getCode(), action.getDesc());
        }
        applyStatusDict = new HashMap<>(4);
        for (SpecialAfterSaleApplyStatusEnum action : SpecialAfterSaleApplyStatusEnum.values()) {
            applyStatusDict.put(action.getCode(), action.getDesc());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void apply(AddSpecialAfterSaleApplyRequest request) {
        // 1. ???????????? ??????????????????
        // ???????????????????????????
        Integer userId = UserHelper.getUserId();
        UserGroupSimpleDTO userInfo = userManager.searchUserGroupFromCache(userId);
        String position = userInfo.getPosition();
        if (StringUtils.isBlank(position)) {
            throw new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "???????????????????????????-??????");
        }
        List<UserGroupSimpleDTO.GroupInfoVO> groups = userInfo.getGroups();
        if (CollectionUtils.isEmpty(groups)) {
            throw new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "???????????????????????????-????????????");
        }
        // ??????????????????
        List<SpecialAfterSaleReviewPositionDTO> matchReviewList = specialAfterSaleConfigService.matchConfigs(userInfo);
        if (CollectionUtils.isEmpty(matchReviewList)) {
            throw new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "?????????????????????????????????????????????");
        }
        // ??????????????? ???????????????????????????
        SpecialAfterSaleReviewPositionDTO useReview = matchReviewList.get(0);
        // 2. ????????????
        Integer configId = useReview.getConfigId();
        SpecialAfterSaleApplyEntity apply = specialAfterSaleApplyManager.saveApply(request, configId, userId, userInfo.getNickname());
        Long applyId = apply.getId();
        // 3. ????????????
        // ??????????????????
        specialAfterSaleLogManager.createActionLog(applyId, SpecialAfterSaleLogActionEnum.START.getValue(), null,
                SpecialAfterSaleLogActionEnum.START.getCode(), null, YesOrNoEnum.YES.getCode(), userId);
        // ????????????????????????????????????
        if (PositionEnum.DX.getPosition().equals(position)) {
            apply.setApplyUserType(SpecialAfterSaleApplyUserTypeEnum.DX.getCode());
            // ??????????????????
            List<List<PassportUserInfoDTO>> allLeader = passportFeignManager.getAllLeader(userId);
            PassportUserInfoDTO dxManager = getDxManager(allLeader);
            if (dxManager == null) {
                throw new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "???????????????????????????????????????????????????");
            }
            Integer dxManagerId = dxManager.getId();
            // ????????????????????????
            SpecialAfterSaleReviewNodeEntity startNode = specialAfterSaleReviewNodeRepository.findTopByConfigIdOrderBySort(configId);
            // ???????????????????????????
            specialAfterSaleLogManager.createActionLog(applyId, SpecialAfterSaleLogActionEnum.DX_MANAGER_TRANSFER.getValue(), startNode.getId(),
                    SpecialAfterSaleLogActionEnum.DX_MANAGER_TRANSFER.getCode(), null, YesOrNoEnum.YES.getCode(), dxManagerId);
            // ???????????????????????????
            specialAfterSaleApplyManager.saveCurrentApprover(applyId, Collections.singletonList(dxManagerId));
            // ????????????????????????????????????????????????
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // todo ??????????????????
                    String definitionName = "????????????????????????";
                    String title = apply.getApplyNickname() + "???" + definitionName;
                    String content = "???????????????" + apply.getTotalApplyAmount() +
                            "???????????????" + apply.getApplyReason() +
                            "???????????????" + apply.getCreatedAt();
                    String url = "http://www.baidu.com";
                    // todo ????????????
                    questNoticeService.sendTextCardMultipleApp(EnvelopeChannelEnum.SPECIAL_AFTER_SALE, title, content, url, Collections.singleton(dxManagerId));
                }
            });
        } else {
            apply.setApplyUserType(SpecialAfterSaleApplyUserTypeEnum.BD.getCode());
            // ?????????????????????????????????
            Map<String, Object> var = new HashMap<>();
            var.put(SpecialAfterSaleUseVariableEnum.APPLY_ID.getCode(), applyId);
            var.put(SpecialAfterSaleUseVariableEnum.APPLY_USER_ID.getCode(), apply.getCreatedBy());
            var.put(SpecialAfterSaleUseVariableEnum.APPLY_USER_TYPE.getCode(), SpecialAfterSaleApplyUserTypeEnum.BD.getCode());
            var.put(SpecialAfterSaleUseVariableEnum.APPLY_REASON.getCode(), apply.getApplyReason());
            var.put(SpecialAfterSaleUseVariableEnum.APPLY_TIME.getCode(), TimeUtil.format(new Date(), TimeUtil.FORMAT_DATE_TIME));
            var.put(SpecialAfterSaleUseVariableEnum.CONFIG_ID.getCode(), configId);
            var.put(SpecialAfterSaleUseVariableEnum.APPLY_TOTAL_AMOUNT.getCode(), apply.getTotalApplyAmount().toString());
            DefinitionAndInstanceIdVO workflowInfo = workflowInstanceService.startExternalCall(buildStartReq(applyId, userId, var), userId);
            apply.setDefinitionId(workflowInfo.getDefinitionId());
            apply.setInstanceId(workflowInfo.getInstanceId());
        }
        specialAfterSaleApplyManager.saveApply(apply);
    }

    @Override
    public Page<SpecialAfterSaleSimpleApplyVO> queryListMyApproved(SpecialAfterSaleSearchRequest request) {
        QueryResults<SpecialAfterSaleApplyEntity> results = specialAfterSaleApplyRepository.queryListMyApproved(request);
        return convertApplySimpleVoPage(PageRequest.of(request.getPage(), request.getSize()), results.getTotal(), results.getResults());
    }

    @Override
    public Page<SpecialAfterSaleSimpleApplyVO> queryListMyPendingApproval(SpecialAfterSaleSearchRequest request) {
        QueryResults<SpecialAfterSaleApplyEntity> results = specialAfterSaleApplyRepository.queryListMyPendingApprove(request);
        return convertApplySimpleVoPage(PageRequest.of(request.getPage(), request.getSize()), results.getTotal(), results.getResults());
    }

    @Override
    public SpecialAfterSaleDetailApplyVO detail(Long applyId) {
        // ??????????????????
        SpecialAfterSaleApplyEntity apply = specialAfterSaleApplyRepository.findById(applyId)
                .orElseThrow(() -> new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "???????????????????????????"));
        SpecialAfterSaleDetailApplyVO result = new SpecialAfterSaleDetailApplyVO();
        // set
        ObjectUtil.extendObject(result, apply, true);
        // ?????????????????????
        UserGroupSimpleDTO userGroupInfo = userManager.searchUserGroupFromCache(apply.getCreatedBy());
        result.setCreatorId(userGroupInfo.getUserId());
        result.setCreatorName(userGroupInfo.getNickname());
        List<UserGroupSimpleDTO.GroupInfoVO> groups = userGroupInfo.getGroups();
        if (CollectionUtils.isNotEmpty(groups)) {
            result.setCreatorGroupName(groups.get(0).getGroupDecs());
        }
        // ????????????
        result.setDetails(specialAfterSaleDetailRepository.findAllByApplyId(applyId));
        // ????????????????????????
        String instanceId = apply.getInstanceId();
        result.setInstanceId(instanceId);
        TaskEntity currentTask = workflowTaskService.getCurrentTaskByInstanceId(instanceId);
        if (Objects.nonNull(currentTask)) {
            result.setTaskId(currentTask.getId());
        }
        List<Integer> approverIdList = specialAfterSaleApplyManager.queryCurrentApprover(applyId);
        result.setCurrentApproverIdList(approverIdList);
        // ????????????
        List<SpecialAfterSaleLogVO> actionLogs = specialAfterSaleLogManager.queryListLog(applyId);
        result.setLogs(actionLogs);
        // ????????????????????????
        SpecialAfterSaleLogVO lastLog = actionLogs.get(actionLogs.size() - 1);
        final boolean isDxManagerNode = SpecialAfterSaleLogActionEnum.DX_MANAGER_TRANSFER.getCode() == lastLog.getAction();
        if (isDxManagerNode) {
            result.setDxManagerNode(YesOrNoEnum.YES.getCode());
        } else {
            result.setDxManagerNode(YesOrNoEnum.NO.getCode());
        }
        // ????????????
        Integer reviewNodeId = lastLog.getReviewNodeId();
        if (Objects.nonNull(reviewNodeId)) {
            SpecialAfterSaleReviewNodeEntity reviewNode = specialAfterSaleReviewNodeRepository.findById(reviewNodeId)
                    .orElseThrow(() -> new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "???????????????????????? "));
            result.setReviewNodeInfo(reviewNode);
            if (reviewNode.getUseQuota() == YesOrNoEnum.YES.getCode()) {
                Map<Integer, List<SpecialAfterSaleGroupQuotaDTO>> userQuotaMap = specialAfterSaleQuotaService.queryQuotaByUserId(approverIdList, apply.getApplyUserType());
                result.setApproverQuota(userQuotaMap);
            }
        } else if (isDxManagerNode) {
            SpecialAfterSaleReviewNodeEntity startNode = specialAfterSaleReviewNodeRepository.findTopByConfigIdOrderBySort(apply.getConfigId());
            result.setReviewNodeInfo(startNode);
        }
        // ??????
        result.setDictionaries(logActionDict);
        return result;
    }

    @Override
    public SpecialAfterSaleDetailApplyPcVO pcDetail(Long applyId) {
        // ??????????????????
        SpecialAfterSaleApplyEntity apply = specialAfterSaleApplyRepository.findById(applyId)
                .orElseThrow(() -> new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "???????????????????????????"));
        SpecialAfterSaleDetailApplyPcVO pcVo = new SpecialAfterSaleDetailApplyPcVO();
        ObjectUtil.extendObject(pcVo, apply, true);
        // ?????????????????????
        UserGroupSimpleDTO userGroupInfo = userManager.searchUserGroupFromCache(apply.getCreatedBy());
        pcVo.setCreatorId(userGroupInfo.getUserId());
        pcVo.setCreatorName(userGroupInfo.getNickname());
        List<UserGroupSimpleDTO.GroupInfoVO> groups = userGroupInfo.getGroups();
        if (CollectionUtils.isNotEmpty(groups)) {
            pcVo.setCreatorGroupName(groups.get(0).getGroupDecs());
        }
        // ??????????????????
        pcVo.setDetails(specialAfterSaleDetailRepository.findAllByApplyId(applyId));
        return pcVo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(SpecialAfterSaleApprovalRequest request) throws Exception {
        Long applyId = request.getApplyId();
        Integer userId = UserHelper.getUserId();
        if (!specialAfterSaleApplyManager.checkIsApprover(applyId, userId)) {
            throw new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "????????????????????????????????????");
        }
        SpecialAfterSaleApplyEntity apply = specialAfterSaleApplyRepository.findById(applyId)
                .orElseThrow(() -> new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "???????????????????????????"));
        // ???????????????submit
        TaskForm taskForm = new TaskForm();
        String taskId = request.getTaskId();
        taskForm.setId(taskId);
        int action;
        String actionDesc;
        // ?????????
        TaskFormItem item1 = new TaskFormItem();
        item1.setName(SpecialAfterSaleUseVariableEnum.Last_APPROVER.getCode());
        item1.setValue(userId);
        // ???????????????id
        TaskFormItem item2 = new TaskFormItem();
        item2.setName(SpecialAfterSaleUseVariableEnum.USE_QUOTA_ID.getCode());
        item2.setValue(request.getQuotaId());
        // ????????????
        TaskFormItem item = new TaskFormItem();
        item.setName(WorkflowStatusFlag.TASK_STATUS.getName());
        if (YesOrNoEnum.YES.getCode() == request.getStatus()) {
            item.setValue(TaskState.APPROVAL.getState());
            action = SpecialAfterSaleLogActionEnum.PASS.getCode();
            actionDesc = SpecialAfterSaleLogActionEnum.PASS.getDesc();
        } else {
            item.setValue(TaskState.REJECT.getState());
            action = SpecialAfterSaleLogActionEnum.REJECT.getCode();
            actionDesc = SpecialAfterSaleLogActionEnum.REJECT.getDesc();
        }
        // ??????????????????
        TaskFormItem item3 = new TaskFormItem();
        item3.setName(SpecialAfterSaleUseVariableEnum.APPROVAL_STATUS_DESC.getCode());
        item3.setValue(actionDesc);
        taskForm.setFormData(Arrays.asList(item, item1, item2, item3));
        // ?????????task?????????????????????
        List<SpecialAfterSaleLogEntity> logs = specialAfterSaleLogManager.hideOrShowLog(applyId, SpecialAfterSaleLogActionEnum.PENDING_APPROVAL.getValue(),
                SpecialAfterSaleLogActionEnum.PENDING_APPROVAL.getCode(), YesOrNoEnum.NO.getCode());
        // ??????????????????????????????
        specialAfterSaleLogManager.createActionLog(applyId, taskId, logs.get(0).getReviewNodeId(), action, request.getOpinions(), YesOrNoEnum.YES.getCode(), userId);
        // ???????????? ??????????????????????????????????????????????????????????????????????????????
        workflowTaskService.saveTask(taskForm, true, false, true, userId.longValue());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transfer(SpecialAfterSaleTransferRequest request) throws Exception {
        Long applyId = request.getApplyId();
        Integer operatorId = UserHelper.getUserId();
        if (!specialAfterSaleApplyManager.checkIsApprover(applyId, operatorId)) {
            throw new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "????????????????????????????????????");
        }
        String taskId = request.getTaskId();
        List<Integer> userIds = request.getUserIds();
        // ??????
        workflowTaskService.transfer(taskId, userIds, operatorId);
        // ????????????
        specialAfterSaleLogManager.createTransferLog(request.getApplyId(), taskId, userIds, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void routingAndStart(SpecialAfterSaleDxTransferRequest request) {
        Long applyId = request.getApplyId();
        Integer userId = UserHelper.getUserId();
        if (!specialAfterSaleApplyManager.checkIsApprover(applyId, userId)) {
            throw new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "????????????????????????????????????");
        }
        // ??????????????????????????????????????????????????????CM?????????
        SpecialAfterSaleApplyEntity apply = specialAfterSaleApplyRepository.findById(applyId)
                .orElseThrow(() -> new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "???????????????????????????"));
        // ???????????????
        specialAfterSaleLogManager.hideOrShowLog(applyId, SpecialAfterSaleLogActionEnum.DX_MANAGER_TRANSFER.getValue(),
                SpecialAfterSaleLogActionEnum.DX_MANAGER_TRANSFER.getCode(), YesOrNoEnum.NO.getCode(), userId);
        Integer nodeId = request.getNodeId();
        // ???????????????
        specialAfterSaleLogManager.createActionLog(applyId, SpecialAfterSaleLogActionEnum.DX_MANAGER_TRANSFERRED.getValue(), nodeId,
                SpecialAfterSaleLogActionEnum.DX_MANAGER_TRANSFERRED.getCode(), null, YesOrNoEnum.YES.getCode(), userId);
        // ????????????
        Map<String, Object> var = new HashMap<>();
        var.put(SpecialAfterSaleUseVariableEnum.APPLY_ID.getCode(), applyId);
        var.put(SpecialAfterSaleUseVariableEnum.APPLY_USER_ID.getCode(), apply.getCreatedBy());
        var.put(SpecialAfterSaleUseVariableEnum.APPLY_USER_TYPE.getCode(), SpecialAfterSaleApplyUserTypeEnum.DX.getCode());
        var.put(SpecialAfterSaleUseVariableEnum.APPLY_REASON.getCode(), apply.getApplyReason());
        Date createdAt = TimeUtil.localDateTime2Date(apply.getCreatedAt());
        var.put(SpecialAfterSaleUseVariableEnum.APPLY_TIME.getCode(), TimeUtil.format(createdAt, TimeUtil.FORMAT_DATE_TIME));
        var.put(SpecialAfterSaleUseVariableEnum.CONFIG_ID.getCode(), apply.getConfigId());
        var.put(SpecialAfterSaleUseVariableEnum.CURRENT_NODE_SPECIFY_USER.getCode() + nodeId, JsonUtil.toJsonString(request.getUserIds()));
        var.put(SpecialAfterSaleUseVariableEnum.APPLY_TOTAL_AMOUNT.getCode(), apply.getTotalApplyAmount().toString());
        DefinitionAndInstanceIdVO workflowInfo = workflowInstanceService.startExternalCall(buildStartReq(applyId, userId, var), userId);
        // ??????????????????
        apply.setDefinitionId(workflowInfo.getDefinitionId());
        apply.setInstanceId(workflowInfo.getInstanceId());
        specialAfterSaleApplyManager.saveApply(apply);
    }

    private PassportUserInfoDTO getDxManager(List<List<PassportUserInfoDTO>> leaderList) {
        for (List<PassportUserInfoDTO> user : leaderList) {
            PassportUserInfoDTO leader = user.stream()
                    .filter(u -> PositionEnum.DXM.getPosition().equals(u.getPosition()))
                    .findFirst().orElse(null);
            if (leader != null) {
                return leader;
            }
        }
        return null;
    }

    private StartInstanceRequest buildStartReq(Long applyId, Integer userId, Map<String, Object> var) {
        StartInstanceRequest startReq = new StartInstanceRequest();
        startReq.setKey(sasDefinitionKey);
        startReq.setBusinessKey(applyId.toString());
        startReq.setCreatorId(userId.longValue());
        startReq.setVariables(var);
        return startReq;
    }

    @Override
    public Page<SpecialAfterSaleSimpleApplyVO> queryMyCcApplyList(SpecialAfterSaleSearchRequest req) {
        Integer userId = UserHelper.getUserId();
        Integer page = req.getPage();
        Integer limit = req.getSize();
        String applyName = StringUtils.trim(req.getKeyWord());
        QSpecialAfterSaleCcEntity ccEntity = QSpecialAfterSaleCcEntity.specialAfterSaleCcEntity;
        QSpecialAfterSaleApplyEntity applyEntity = QSpecialAfterSaleApplyEntity.specialAfterSaleApplyEntity;
        Predicate predicate = ccEntity.userId.eq(userId);
        //????????????????????????
        predicate = StringUtils.isEmpty(applyName) ?
                predicate : ExpressionUtils.and(predicate, applyEntity.applyNickname.like("%" + applyName + "%"));
        QueryResults<SpecialAfterSaleApplyEntity> results = queryFactory.select(applyEntity)
                .from(applyEntity)
                .join(ccEntity)
                .on(ccEntity.applyId.eq(applyEntity.id))
                .where(predicate)
                .offset(page * limit)
                .limit(limit)
                .orderBy(ccEntity.userId.asc())
                .fetchResults();
        return convertApplySimpleVoPage(PageRequest.of(page, limit), results.getTotal(), results.getResults());
    }

    @Override
    public com.ruigu.rbox.cloud.kanai.web.page.PageImpl<SpecialAfterSaleApplyRecordVO> queryAfterSaleList(SpecialAfterSaleApplyRequest req) {
        Integer page = req.getPage();
        Integer limit = req.getSize();
        Integer status = req.getStatus();
        Integer deptId = req.getDeptId();
        String userName = StringUtils.trim(req.getUserName());
        LocalDate startDate = req.getStartTime();
        LocalDate endDate = req.getEndTime();
        QSpecialAfterSaleApplyEntity applyEntity = QSpecialAfterSaleApplyEntity.specialAfterSaleApplyEntity;
        Predicate predicate = applyEntity.isNotNull().or(applyEntity.isNull());
        //????????????????????????
        predicate = StringUtils.isBlank(userName) ?
                predicate : ExpressionUtils.and(predicate, applyEntity.applyNickname.like("%" + userName + "%"));
        predicate = status == null ?
                predicate : ExpressionUtils.and(predicate, applyEntity.status.eq(status));
        if (deptId != null) {
            List<Integer> userList = passportFeignClient.getUserIdListUnderTheGroup(Collections.singletonList(deptId)).getData();
            if (userList == null) {
                throw new GlobalRuntimeException(ResponseCode.ERROR.getCode(), "???????????????" + deptId + "???????????????????????????");
            }
            predicate = ExpressionUtils.and(predicate, applyEntity.createdBy.in(userList));
        }
        if (startDate != null && endDate != null) {
            LocalDateTime start = LocalDateTime.of(req.getStartTime(), LocalTime.MIN);
            LocalDateTime end = LocalDateTime.of(req.getEndTime(), LocalTime.MAX);
            predicate = ExpressionUtils.and(predicate, applyEntity.createdAt.between(start, end));
        } else if (startDate != null) {
            LocalDateTime start = LocalDateTime.of(req.getStartTime(), LocalTime.MIN);
            predicate = ExpressionUtils.and(predicate, applyEntity.createdAt.goe(start));
        } else if (endDate != null) {
            LocalDateTime end = LocalDateTime.of(req.getEndTime(), LocalTime.MAX);
            predicate = ExpressionUtils.and(predicate, applyEntity.createdAt.loe(end));
        }
        QueryResults<SpecialAfterSaleApplyEntity> results = queryFactory.selectFrom(applyEntity)
                .where(predicate)
                .offset(page * limit)
                .limit(limit)
                .orderBy(applyEntity.id.asc())
                .fetchResults();
        List<SpecialAfterSaleApplyRecordVO> applyList = queryUserIdByGroup(results);
        return com.ruigu.rbox.cloud.kanai.web.page.PageImpl.of(applyList, PageRequest.of(page, limit), (int) results.getTotal());
    }

    /**
     * ????????????????????????????????????
     *
     * @param results ??????????????????
     */
    private List<SpecialAfterSaleApplyRecordVO> queryUserIdByGroup(QueryResults<SpecialAfterSaleApplyEntity> results) {
        List<SpecialAfterSaleApplyRecordVO> applyList = results.getResults().stream().map(apply -> SpecialAfterSaleApplyRecordVO.builder()
                .nickName(apply.getApplyNickname())
                .userId(apply.getCreatedBy())
                .customerName(apply.getCustomerName())
                .applyDate(apply.getCreatedAt())
                .applyReason(apply.getApplyReason())
                .customerRating(apply.getCustomerRating())
                .status(applyStatusDict.get(apply.getStatus()))
                .id(apply.getId())
                .totalApplyAmount(apply.getTotalApplyAmount())
                .applyCode(apply.getCode())
                .build()).collect(Collectors.toList());
        Set<Integer> userSet = results.getResults().stream().map(SpecialAfterSaleApplyEntity::getCreatedBy).collect(Collectors.toSet());
        Map<Integer, UserGroupSimpleDTO> userGroupFromCache = userManager.searchUserGroupFromCache(userSet);
        for (SpecialAfterSaleApplyRecordVO apply : applyList) {
            UserGroupSimpleDTO userGroup = userGroupFromCache.get(apply.getUserId());
            if (userGroup == null) {
                throw new GlobalRuntimeException(ResponseCode.ERROR.getCode(), "???????????????" + apply.getUserId() + "????????????");
            } else {
                apply.setDeptName(userGroup.getGroups().get(0).getGroupName());
                apply.setDeptNo(userGroup.getGroups().get(0).getGroupId());
            }
        }
        return applyList;
    }

    @Override
    public void exportAllAfterSale(HttpServletResponse response) {

        // TODO ??????????????????????????????????????????
        ExcelWriter excelWriter = null;
        ServletOutputStream out = null;
        QSpecialAfterSaleApplyEntity applyEntity = QSpecialAfterSaleApplyEntity.specialAfterSaleApplyEntity;
        QueryResults<SpecialAfterSaleApplyEntity> results = queryFactory.selectFrom(applyEntity)
                .orderBy(applyEntity.id.asc())
                .fetchResults();
        List<SpecialAfterSaleApplyRecordVO> applyList = queryUserIdByGroup(results);
        try {
            out = response.getOutputStream();
            response.setContentType("multipart/form-data");
            response.setCharacterEncoding("utf-8");
            response.setHeader("Content-disposition", "attachment;filename=" + URLEncoder.encode(EXCEL_NAME, "UTF-8") + FILE_TYPE);
            EasyExcelFactory.write(out, SpecialAfterSaleApplyRecordVO.class)
                    .sheet(0, EXCEL_NAME)
                    .doWrite(applyList);
            // ???????????????finish ??????????????????
            if (excelWriter != null) {
                excelWriter.finish();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * ????????????????????????????????????
     *
     * @return
     */
    @Override
    public Page<SpecialAfterSaleSimpleApplyVO> findAllByCreatedBy(SpecialAfterSaleSearchRequest request) {
        PageRequest pageable = PageRequest.of(request.getPage(), request.getSize());
        Integer userId = UserHelper.getUserId();
        Page<SpecialAfterSaleApplyEntity> queryResult = repository.findAllByCreatedBy(userId, pageable);
        return convertApplySimpleVoPage(pageable, queryResult.getTotalElements(), queryResult.getContent());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void urgeSpecialSaleApply(Long applyId) {
        // ??????????????????
        SpecialAfterSaleApplyEntity apply = specialAfterSaleApplyRepository.findById(applyId)
                .orElseThrow(() -> new GlobalRuntimeException(ResponseCode.REQUEST_ERROR.getCode(), "??????id" + applyId + "????????????????????????????????????"));
        Integer status = apply.getStatus();
        if (status == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(),
                    "????????????????????????????????????????????????????????????");
        }
        // ?????????????????????
        List<SpecialAfterSaleApplyApproverEntity> currentApprovers = specialAfterSaleApplyApproverRepository.findAllByApplyId(applyId);
        if (currentApprovers.isEmpty()) {
            throw new GlobalRuntimeException(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????????????????????????????");
        }
        ArrayList<Integer> approverIds = new ArrayList<>(8);
        for (SpecialAfterSaleApplyApproverEntity approver : currentApprovers) {
            approverIds.add(approver.getApplyId().intValue());
        }
        // ??????????????????redis??????????????????
        String urgeRedisKey = RedisKeyConstants.SPECIAL_SALE_URGE_RESTRICT + "approverIds:" + StringUtils.join(approverIds, Symbol.COMMA.getValue()) + ":applyId:" + applyId;
        Boolean result = redisTemplate.hasKey(urgeRedisKey);
        if (result != null && result) {
            throw new GlobalRuntimeException(ResponseCode.REFUSE_EXECUTE.getCode(), "????????????????????????????????????");
        }
        redisTemplate.opsForValue().set(urgeRedisKey, new Date(), 30, TimeUnit.MINUTES);
        // ??????????????????
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                String definitionName = "????????????????????????";
                String title = "????????????" + apply.getApplyNickname() + "???" + definitionName;
                Map<String, Object> body = new HashMap<>(8);
                body.put(NoticeParam.TITLE.getDesc(), title);
                body.put(NoticeParam.CONTENT.getDesc(), title);
                questNoticeService.sendTextCardMultipleApp(EnvelopeChannelEnum.SPECIAL_AFTER_SALE, body, approverIds);
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelSpecialSaleApply(Long applyId) {
        Integer userId = UserHelper.getUserId();
        // ??????????????????????????????
        SpecialAfterSaleApplyEntity entity = specialAfterSaleApplyRepository.findById(applyId)
                .orElseThrow(() -> new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "???????????????????????????"));
        QSpecialAfterSaleApplyEntity applyEntity = QSpecialAfterSaleApplyEntity.specialAfterSaleApplyEntity;
        queryFactory.update(applyEntity)
                .set(applyEntity.status, SpecialAfterSaleApplyStatusEnum.UNDO.getCode())
                .set(applyEntity.lastUpdateAt, LocalDateTime.now())
                .set(applyEntity.lastUpdateBy, userId)
                .execute();
        // ??????????????????
        specialAfterSaleLogManager.createActionLog(applyId, SpecialAfterSaleLogActionEnum.CANCEL.getValue(), null,
                SpecialAfterSaleLogActionEnum.CANCEL.getCode(), null, YesOrNoEnum.YES.getCode(), userId);
        // ??????workflow???????????????
        workflowInstanceService.revokeInstanceById(entity.getInstanceId());
    }

    private Page<SpecialAfterSaleSimpleApplyVO> convertApplySimpleVoPage(Pageable pageable, long total, List<SpecialAfterSaleApplyEntity> entities) {
        List<Integer> applyUserIds = entities.stream().map(SpecialAfterSaleApplyEntity::getCreatedBy).collect(Collectors.toList());
        Map<Integer, PassportUserInfoDTO> applyUserInfoMap = passportFeignManager.getUserInfoMapFromRedis(applyUserIds);
        List<SpecialAfterSaleSimpleApplyVO> vos = new ArrayList<>();
        entities.forEach(e -> {
            SpecialAfterSaleSimpleApplyVO vo = new SpecialAfterSaleSimpleApplyVO();
            vo.setApplyId(e.getId());
            vo.setCode(e.getCode());
            vo.setApplyTime(e.getCreatedAt());
            Integer applyUserId = e.getCreatedBy();
            vo.setApplyUserId(applyUserId);
            PassportUserInfoDTO info = applyUserInfoMap.getOrDefault(applyUserId, null);
            if (Objects.nonNull(info)) {
                vo.setApplyUserName(info.getNickname());
            } else {
                vo.setApplyUserName(e.getApplyNickname());
            }
            vo.setApplyAmount(e.getTotalApplyAmount());
            vo.setStatus(e.getStatus());
            vo.setApprovalTime(e.getApprovalTime());
            vos.add(vo);
        });
        return new PageImpl(vos, pageable, total);
    }
}
