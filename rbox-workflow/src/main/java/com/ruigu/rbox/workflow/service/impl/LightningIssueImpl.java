package com.ruigu.rbox.workflow.service.impl;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.QueryResults;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringTemplate;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.ruigu.rbox.cloud.kanai.model.YesOrNoEnum;
import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.cloud.kanai.util.ConvertUtil;
import com.ruigu.rbox.cloud.kanai.util.TimeUtil;
import com.ruigu.rbox.cloud.kanai.web.page.PageImpl;
import com.ruigu.rbox.workflow.config.RedisCache;
import com.ruigu.rbox.workflow.config.UpyunConfig;
import com.ruigu.rbox.workflow.constants.RedisKeyConstants;
import com.ruigu.rbox.workflow.exceptions.GlobalRuntimeException;
import com.ruigu.rbox.workflow.exceptions.LogicException;
import com.ruigu.rbox.workflow.exceptions.VerificationFailedException;
import com.ruigu.rbox.workflow.feign.PassportFeignClient;
import com.ruigu.rbox.workflow.manager.*;
import com.ruigu.rbox.workflow.model.ServerResponse;
import com.ruigu.rbox.workflow.model.dto.*;
import com.ruigu.rbox.workflow.model.entity.*;
import com.ruigu.rbox.workflow.model.enums.*;
import com.ruigu.rbox.workflow.model.request.*;
import com.ruigu.rbox.workflow.model.request.lightning.*;
import com.ruigu.rbox.workflow.model.vo.DefinitionAndInstanceIdVO;
import com.ruigu.rbox.workflow.model.vo.GroupAndUserAndCountVO;
import com.ruigu.rbox.workflow.model.vo.LightningIssueCategoryVO;
import com.ruigu.rbox.workflow.model.vo.LightningMyAcceptanceVO;
import com.ruigu.rbox.workflow.model.vo.lightning.*;
import com.ruigu.rbox.workflow.repository.*;
import com.ruigu.rbox.workflow.service.*;
import com.ruigu.rbox.workflow.supports.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author caojinghong
 * @date 2019/12/27 14:50
 */
@Service
@Slf4j
public class LightningIssueImpl implements LightningIssueService {

    private static final Map<Integer, String> STATUS_MAP = new HashMap<>(11);

    static {
        STATUS_MAP.put(LightningApplyStatus.TO_BE_ACCEPTED.getCode(), LightningApplyStatus.TO_BE_ACCEPTED.getDesc());
        STATUS_MAP.put(LightningApplyStatus.ACCEPTING.getCode(), LightningApplyStatus.ACCEPTING.getDesc());
        STATUS_MAP.put(LightningApplyStatus.TO_BE_CONFIRMED.getCode(), LightningApplyStatus.TO_BE_CONFIRMED.getDesc());
        STATUS_MAP.put(LightningApplyStatus.RESOLVED.getCode(), LightningApplyStatus.RESOLVED.getDesc());
        STATUS_MAP.put(LightningApplyStatus.UNRESOLVED.getCode(), LightningApplyStatus.UNRESOLVED.getDesc());
        STATUS_MAP.put(LightningApplyStatus.HANDED_OVER.getCode(), LightningApplyStatus.HANDED_OVER.getDesc());
        STATUS_MAP.put(LightningApplyStatus.REVOKED.getCode(), LightningApplyStatus.REVOKED.getDesc());
    }

    private static final Map<Integer, String> LOG_TYPE_MAP = new HashMap<>(16);

    static {
        LOG_TYPE_MAP.put(LightningIssueLogActionEnum.START.getCode(), LightningIssueLogActionEnum.START.getDesc());
        LOG_TYPE_MAP.put(LightningIssueLogActionEnum.ACCEPTED.getCode(), LightningIssueLogActionEnum.ACCEPTED.getDesc());
        LOG_TYPE_MAP.put(LightningIssueLogActionEnum.HANDED_OVER.getCode(), LightningIssueLogActionEnum.HANDED_OVER.getDesc());
        LOG_TYPE_MAP.put(LightningIssueLogActionEnum.SUBMIT_CONFIRMED.getCode(), LightningIssueLogActionEnum.SUBMIT_CONFIRMED.getDesc());
        LOG_TYPE_MAP.put(LightningIssueLogActionEnum.RESOLVED.getCode(), LightningIssueLogActionEnum.RESOLVED.getDesc());
        LOG_TYPE_MAP.put(LightningIssueLogActionEnum.UNRESOLVED.getCode(), LightningIssueLogActionEnum.UNRESOLVED.getDesc());
        LOG_TYPE_MAP.put(LightningIssueLogActionEnum.REVOKED.getCode(), LightningIssueLogActionEnum.REVOKED.getDesc());
        LOG_TYPE_MAP.put(LightningIssueLogActionEnum.TO_BE_ACCEPTED.getCode(), LightningIssueLogActionEnum.TO_BE_ACCEPTED.getDesc());
        LOG_TYPE_MAP.put(LightningIssueLogActionEnum.AUTO_CONFIRM.getCode(), LightningIssueLogActionEnum.AUTO_CONFIRM.getDesc());
        LOG_TYPE_MAP.put(LightningIssueLogActionEnum.RESUBMIT.getCode(), LightningIssueLogActionEnum.RESUBMIT.getDesc());
        LOG_TYPE_MAP.put(LightningIssueLogActionEnum.AUTO_HANDED_OVER.getCode(), LightningIssueLogActionEnum.AUTO_HANDED_OVER.getDesc());
        LOG_TYPE_MAP.put(LightningIssueLogActionEnum.INVITE_HANDED_OVER.getCode(), LightningIssueLogActionEnum.INVITE_HANDED_OVER.getDesc());
    }

    @Autowired
    private LightningIssueApplyRepository issueApplyRepository;
    @Autowired
    private LightningIssueEvaluationRepository evaluationRepository;
    @Autowired
    private LightningIssueLogRepository logRepository;
    @Autowired
    private LightningIssueRelevantUserRepository relevantUserRepository;
    @Autowired
    private WorkflowInstanceService workflowInstanceService;
    @Resource
    private WorkflowHistoryService workflowHistoryService;
    @Autowired
    private WorkflowTaskService workflowTaskService;
    @Autowired
    private ChatWebSocketService chatWebSocketService;
    @Autowired
    private LightningIssueLogManager lightningIssueLogManager;
    @Autowired
    private PassportFeignClient passportFeignClient;
    @Autowired
    private PassportFeignManager passportFeignManager;
    @Resource
    private UserManager userManager;
    @Autowired
    private LightningUserIgnoreIssueRepository ignoreIssueRepository;
    @Resource
    private LightningIssueGroupRepository lightningIssueGroupRepository;
    @Resource
    private QuestNoticeService questNoticeService;
    @Autowired
    private UpyunConfig upyunConfig;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${rbox.workflow.lightning.list-solvers.manager-id-list}")
    private List<Integer> managerIdList;

    @Value("${rbox.workflow.definition.lightning}")
    private String lightningKey;

    @Autowired
    private RedisCache redisCache;

    @Resource
    private LightningIssueManager lightningIssueManager;

    @Resource
    private LightningIssueConfigManager lightningIssueConfigManager;

    @Resource
    private DutyPlanRepository dutyPlanRepository;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public LightningIssueApplyEntity resubmitIssueApply(IssueResubmitReq req) throws Exception {
        // ??????id
        Integer issueId = req.getIssueId();
        LightningIssueApplyEntity applyEntity = issueApplyRepository.findById(issueId)
                .orElseThrow(() -> new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????"));
        if (applyEntity.getAutoConfirm() == YesOrNoEnum.NO.getCode()) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????????????????????????????");
        }
        LightningIssueGroupEntity groupInfo = lightningIssueGroupRepository.findByIssueId(issueId);
        if (groupInfo == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????????????????");
        }
        Long groupId = Long.valueOf(groupInfo.getGroupId());
        // ??????id
        Integer userId = UserHelper.getUserId();
        if (!applyEntity.getCreatedBy().equals(userId)) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????????????????");
        }
        // ???????????????
        Integer currentSolverId = applyEntity.getCurrentSolverId();
        ServerResponse<List<PassportUserInfoDTO>> currentSolverInfoResponse = passportFeignClient.getUserMsgByIds(Collections.singleton(currentSolverId));
        List<PassportUserInfoDTO> currentSolverInfoList = currentSolverInfoResponse.getData();
        if (ResponseCode.SUCCESS.getCode() != currentSolverInfoResponse.getCode() || CollectionUtils.isEmpty(currentSolverInfoList)) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????");
        }
        // ????????????
        PassportUserInfoDTO currentSolverInfo = currentSolverInfoList.get(0);
        if (YesOrNoEnum.YES.getCode() == currentSolverInfo.getDeleted() || currentSolverInfo.getStatus() == YesOrNoEnum.NO.getCode()) {
            List<PassportUserInfoDTO> superiorLeader = passportFeignManager.getSuperiorLeader(currentSolverId);
            if (CollectionUtils.isEmpty(superiorLeader)) {
                throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "?????????????????????????????????????????????????????????????????????????????????");
            }
            currentSolverId = superiorLeader.get(0).getId();
            // ??????????????????????????????????????????????????????????????????????????????????????????????????????
            LightningIssueRelevantUserEntity byIssueIdAndUserId = relevantUserRepository.findByIssueIdAndUserId(issueId, currentSolverId);
            if (byIssueIdAndUserId == null) {
                // ???????????????????????????
                chatWebSocketService.addUserToGroup(issueId, groupId, userId, currentSolverId);
                // ??????????????????????????????????????????????????????
                LightningIssueRelevantUserEntity relevantUserEntity = addIssueRelevantUser(issueId, currentSolverId, userId);
                relevantUserRepository.save(relevantUserEntity);
            }
        }
        // ????????????
        workflowHistoryService.abandonedHistoryInstance(lightningKey, issueId.toString());
        // ????????????
        StartLightningInstanceRequest startReq = new StartLightningInstanceRequest();
        InstanceInfoRequest instanceInfoRequest = new InstanceInfoRequest();
        instanceInfoRequest.setBusinessKey(issueId.toString());
        instanceInfoRequest.setOwnerId(userId.longValue());
        Map<String, Object> variables = new HashMap<>(8);
        variables.put(InstanceVariableParam.RECEIVER.getText(), currentSolverId);
        String description = applyEntity.getDescription();
        String paramDesc = description.length() <= 10 ? description : description.substring(0, 10);
        variables.put(InstanceVariableParam.DESCRIPTION.getText(), paramDesc);
        instanceInfoRequest.setVariables(variables);
        startReq.setInstanceInfo(instanceInfoRequest);
        startReq.setCreatorId(userId.longValue());
        startReq.setKey(lightningKey);
        DefinitionAndInstanceIdVO definitionAndInstanceIdVO = workflowInstanceService.lightningStart(startReq, userId.longValue());
        // ???????????????????????????
        applyEntity.setDefinitionId(definitionAndInstanceIdVO.getDefinitionId());
        applyEntity.setInstanceId(definitionAndInstanceIdVO.getInstanceId());
        applyEntity.setCurrentSolverId(currentSolverId);
        applyEntity.setStatus(LightningApplyStatus.TO_BE_ACCEPTED.getCode());
        applyEntity.setAutoConfirm(YesOrNoEnum.NO.getCode());
        applyEntity.setLastUpdatedBy(userId);
        applyEntity.setLastUpdatedOn(new Date());
        issueApplyRepository.save(applyEntity);
        // ????????????
        LightningIssueLogEntity reSubmitLog = lightningIssueLogManager.saveIssueLog(issueId, LightningIssueLogActionEnum.RESUBMIT.getCode(), userId.intValue());
        LightningIssueLogEntity toBeAcceptedLog = lightningIssueLogManager.saveIssueLog(issueId, LightningIssueLogActionEnum.TO_BE_ACCEPTED.getCode(), currentSolverId);
        logRepository.saveAll(Arrays.asList(reSubmitLog, toBeAcceptedLog));
        // ??????????????????
        chatWebSocketService.sendActionMessage(issueId, groupId, userId, LightningApplyStatus.RESUBMIT);
        // ????????????
        lightningIssueManager.addIssueId(issueId);
        return applyEntity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServerResponse insertIssueApply(LightningIssueApplyReq req) throws Exception {
        // ???????????????Id?????????
        Integer userId = UserHelper.getUserId();
        String username = UserHelper.getUsername();
        List<LightningIssueApplyEntity> issueApplyEntities = new ArrayList<>();
        // ???????????????id (???????????????????????????id)
        Set<Integer> expectedSolver = new HashSet<>(req.getExpectedSolver());
        // ??????????????????????????????,??????????????????
        String issueImages = CollectionUtils.emptyIfNull(req.getAttachments()).stream().filter(StringUtils::isNotBlank).collect(Collectors.joining(","));
        // ??????????????????id???????????????
        Integer categoryId = req.getCategoryId();
        String description = req.getDescription();
        String paramDesc = description.length() <= 10 ? description : description.substring(0, 10);
        for (Integer everyExpectedSolver : expectedSolver) {
            if (everyExpectedSolver.equals(userId)) {
                return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????????????????");
            }
            LightningIssueApplyEntity issueApplyEntity = new LightningIssueApplyEntity();
            issueApplyEntity.setCategoryId(categoryId);
            issueApplyEntity.setDescription(description);
            issueApplyEntity.setExpectedSolver(everyExpectedSolver);
            issueApplyEntity.setCreator(username);
            issueApplyEntity.setCreatedBy(userId);
            issueApplyEntity.setCreatedOn(new Date());
            issueApplyEntity.setLastUpdatedBy(userId);
            issueApplyEntity.setLastUpdatedOn(new Date());
            issueApplyEntity.setAutoConfirm(YesOrNoEnum.NO.getCode());
            issueApplyEntity.setAttachments(issueImages);
            // ?????????????????????
            issueApplyEntity.setStatus(LightningApplyStatus.TO_BE_ACCEPTED.getCode());
            issueApplyEntity.setCurrentSolverId(everyExpectedSolver);
            issueApplyEntities.add(issueApplyEntity);
        }
        // ??????????????????
        List<LightningIssueApplyEntity> applyEntities = issueApplyRepository.saveAll(issueApplyEntities);
        // ??????????????????????????????????????????????????????
        List<LightningIssueRelevantUserEntity> relevantUserEntities = new ArrayList<>();
        // ???????????????????????????????????????????????? ??????????????????????????????????????????????????????
        List<LightningIssueLogEntity> logStartAndToBeAcceptedEntities = new ArrayList<>();
        // ???????????????????????????
        List<BuildGroupDTO> buildGroupList = new ArrayList<>();
        // ???????????????????????????????????????
        BatchStartLightningInstanceRequest lightningBatchStartReq = new BatchStartLightningInstanceRequest();
        lightningBatchStartReq.setKey(lightningKey);
        lightningBatchStartReq.setCreatorId(userId.longValue());
        // ??????????????????????????????
        List<InstanceInfoRequest> instanceInfoList = new ArrayList<>();
        Map<String, LightningIssueApplyEntity> map = new HashMap<>(applyEntities.size());
        for (LightningIssueApplyEntity everyEntity : applyEntities) {
            Integer id = everyEntity.getId();
            Integer expectedSolver1 = everyEntity.getExpectedSolver();
            // ????????????
            LightningIssueRelevantUserEntity relevantUserEntity = addIssueRelevantUser(id, userId, userId);
            relevantUserEntities.add(relevantUserEntity);
            // ????????????
            LightningIssueRelevantUserEntity relevantUserEntity1 = addIssueRelevantUser(id, expectedSolver1, userId);
            relevantUserEntities.add(relevantUserEntity1);
            LightningIssueLogEntity lightningIssueLogStartEntity = lightningIssueLogManager.saveIssueLog(id, LightningIssueLogActionEnum.START.getCode(), userId);
            logStartAndToBeAcceptedEntities.add(lightningIssueLogStartEntity);
            LightningIssueLogEntity lightningIssueLogToBeAcceptedEntity = lightningIssueLogManager.saveIssueLog(id, LightningIssueLogActionEnum.TO_BE_ACCEPTED.getCode(), expectedSolver1);
            logStartAndToBeAcceptedEntities.add(lightningIssueLogToBeAcceptedEntity);
            // ???????????????????????????????????????
            List<Integer> memberIds = new ArrayList<>();
            memberIds.add(expectedSolver1);
            BuildGroupDTO buildGroupDTO = new BuildGroupDTO();
            buildGroupDTO.setIssueId(id);
            buildGroupDTO.setMasterId(userId);
            buildGroupDTO.setMemberIds(memberIds);
            buildGroupList.add(buildGroupDTO);

            String busniessKey = String.valueOf(id);
            InstanceInfoRequest instanceInfoReq = new InstanceInfoRequest();
            instanceInfoReq.setBusinessKey(busniessKey);
            // ??????????????????????????????????????????
            Map<String, Object> everyVariables = new HashMap<>(2);
            everyVariables.put(InstanceVariableParam.RECEIVER.getText(), everyEntity.getExpectedSolver());
            everyVariables.put(InstanceVariableParam.DESCRIPTION.getText(), paramDesc);
            instanceInfoReq.setVariables(everyVariables);
            instanceInfoList.add(instanceInfoReq);
            map.put(busniessKey, everyEntity);
        }
        lightningBatchStartReq.setInstanceInfoList(instanceInfoList);
        // ????????????????????????
        chatWebSocketService.buildGroup(buildGroupList);
        List<DefinitionAndInstanceIdVO> definitionAndInstanceIdVos = workflowInstanceService.lightningBatchStart(lightningBatchStartReq);
        if (CollectionUtils.isNotEmpty(definitionAndInstanceIdVos)) {
            for (DefinitionAndInstanceIdVO definitionAndInstanceIdVO : definitionAndInstanceIdVos) {
                LightningIssueApplyEntity entity = map.get(definitionAndInstanceIdVO.getBusinessKey());
                entity.setDefinitionId(definitionAndInstanceIdVO.getDefinitionId());
                entity.setInstanceId(definitionAndInstanceIdVO.getInstanceId());
                entity.setLastUpdatedOn(new Date());
                // redis ????????????
                lightningIssueManager.addIssueId(entity.getId(), entity.getCurrentSolverId());
            }
            // ??????????????????id?????????id
            issueApplyRepository.saveAll(issueApplyEntities);
        }
        // ??????????????????????????????????????????????????????
        relevantUserRepository.saveAll(relevantUserEntities);
        // ???????????????????????????????????????????????? ??????????????????????????????????????????????????????
        logRepository.saveAll(logStartAndToBeAcceptedEntities);

        return ServerResponse.ok("????????????????????????");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServerResponse confirmIssue(IssueConfirmReq req) {
        Integer userId = UserHelper.getUserId();
        String unsolvedReason = req.getUnsolvedReason();
        LightningIssueApplyEntity issueApplyEntity = issueApplyRepository.findById(req.getIssueId()).orElseThrow(() -> new LogicException("??????????????????"));
        if (!userId.equals(issueApplyEntity.getCreatedBy())) {
            return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????????????????????????????????????????????????????");
        }
        if (!issueApplyEntity.getStatus().equals(LightningApplyStatus.TO_BE_CONFIRMED.getCode())) {
            return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????????????????????????????????????????????????????");
        }
        ConfirmSolveSignalRequest confirmSolveSignalRequest = new ConfirmSolveSignalRequest();
        confirmSolveSignalRequest.setInstanceId(issueApplyEntity.getInstanceId());
        Integer status = req.getResolved() ? LightningApplyStatus.RESOLVED.getCode() : LightningApplyStatus.UNRESOLVED.getCode();
        Map<String, Object> variables = new HashMap<>(2);
        variables.put(WorkflowStatusFlag.TASK_STATUS.getName(), status);

        Integer issueId = req.getIssueId();
        LightningIssueGroupEntity groupEntity = lightningIssueGroupRepository.findByIssueId(issueId);
        if (groupEntity == null) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "????????????????????????????????????");
        }
        Long groupId = Long.valueOf(groupEntity.getGroupId());
        // ??????????????????????????????????????? ??????????????????????????????????????????????????????????????????
        if (status.equals(LightningApplyStatus.RESOLVED.getCode())) {
            boolean evaluation = req.getBestPersonId() == null && StringUtils.isBlank(req.getBestPersonName()) && req.getScore() == null;
            if (!evaluation) {
                Integer bestPersonId = req.getBestPersonId();
                if (bestPersonId != null) {
                    // ????????????????????????????????????????????????????????????
                    List<LightningIssueLogEntity> allByIssueIdAndCreatedBy = logRepository.findAllByIssueIdAndCreatedBy(issueId, bestPersonId);
                    if (CollectionUtils.isEmpty(allByIssueIdAndCreatedBy)) {
                        return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????????????????????????????");
                    }
                }
                LightningIssueEvaluationEntity issueEvaluationEntity = new LightningIssueEvaluationEntity();
                issueEvaluationEntity.setIssueId(issueId);
                issueEvaluationEntity.setBestPersonId(bestPersonId);
                issueEvaluationEntity.setBestPersonName(req.getBestPersonName());
                issueEvaluationEntity.setCreatedAt(new Date());
                issueEvaluationEntity.setCreatedBy(userId);
                issueEvaluationEntity.setLastUpdatedAt(new Date());
                issueEvaluationEntity.setLastUpdatedBy(userId);
                issueEvaluationEntity.setScore(req.getScore());
                issueEvaluationEntity.setInstanceId(issueApplyEntity.getInstanceId());
                evaluationRepository.save(issueEvaluationEntity);
            }
            issueApplyEntity.setStatus(LightningApplyStatus.RESOLVED.getCode());
            issueApplyEntity.setAutoConfirm(YesOrNoEnum.NO.getCode());
            // ????????????????????????????????????????????????
            lightningIssueLogManager.saveIssueLogAction(issueId, LightningIssueLogActionEnum.RESOLVED.getCode(), userId);

            // ?????????????????????????????????
            chatWebSocketService.sendActionMessage(issueId, groupId, userId, LightningApplyStatus.RESOLVED);
            // ????????????????????????????????????????????????????????????
            chatWebSocketService.closeGroup(groupId, issueId);

            // ??????redis??????
            lightningIssueManager.removeIssueId(issueId);

        } else {
            if (StringUtils.isBlank(unsolvedReason)) {
                return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????????????????");
            }
            variables.put(InstanceVariableParam.UNSOLVED_REASON.getText(), unsolvedReason);
            // ??????????????????????????????????????????????????????????????????????????????
            issueApplyEntity.setStatus(LightningApplyStatus.UNRESOLVED.getCode());
            issueApplyEntity.setUnresolvedReason(unsolvedReason);
            // ???????????????
            Integer currentSolverId = issueApplyEntity.getCurrentSolverId();
            ServerResponse<List<PassportUserInfoDTO>> currentSolverInfoResponse = passportFeignClient.getUserMsgByIds(Collections.singleton(currentSolverId));
            if (ResponseCode.SUCCESS.getCode() != currentSolverInfoResponse.getCode()) {
                throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????");
            }
            List<PassportUserInfoDTO> currentSolverInfo = currentSolverInfoResponse.getData();

            // PassportUserInfoDTO currentSolverInfo = passportFeignManager.getUserInfoFromRedis(currentSolverId);
            // ????????????
            if (currentSolverInfo.get(0).getDeleted().equals(YesOrNoEnum.YES.getCode())) {
                List<PassportUserInfoDTO> superiorLeader = passportFeignManager.getSuperiorLeader(currentSolverId);
                if (CollectionUtils.isEmpty(superiorLeader)) {
                    throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "?????????????????????????????????????????????????????????????????????????????????");
                }
                currentSolverId = superiorLeader.get(0).getId();
                variables.put(InstanceVariableParam.RECEIVER.getText(), currentSolverId);
                issueApplyEntity.setCurrentSolverId(currentSolverId);
                // ???????????????
                // ??????????????????????????????????????????????????????????????????????????????????????????????????????
                LightningIssueRelevantUserEntity byIssueIdAndUserId = relevantUserRepository.findByIssueIdAndUserId(issueId, currentSolverId);
                if (byIssueIdAndUserId == null) {
                    // ??????????????????????????????????????????????????????
                    LightningIssueRelevantUserEntity relevantUserEntity = addIssueRelevantUser(issueId, currentSolverId, userId);
                    relevantUserRepository.save(relevantUserEntity);
                    // ???????????????????????????
                    chatWebSocketService.addUserToGroup(issueId, groupId, userId, currentSolverId);
                }
            }
            // ????????????????????????????????????????????????
            lightningIssueLogManager.saveIssueLogAction(issueId, LightningIssueLogActionEnum.UNRESOLVED.getCode(), userId, unsolvedReason);
            // ??????????????????????????????????????????????????????
            lightningIssueLogManager.saveIssueLogAction(issueId, LightningIssueLogActionEnum.TO_BE_ACCEPTED.getCode(), currentSolverId);
            // ?????????????????????????????????
            chatWebSocketService.sendActionMessage(issueId, groupId, userId, LightningApplyStatus.UNRESOLVED);

        }
        issueApplyEntity.setLastUpdatedBy(userId);
        issueApplyEntity.setLastUpdatedOn(new Date());
        issueApplyRepository.save(issueApplyEntity);

        confirmSolveSignalRequest.setVariables(variables);
        // ??????????????????
        workflowInstanceService.sendConfirmIsSolveSignal(confirmSolveSignalRequest);
        return ServerResponse.ok("???????????????????????????");
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServerResponse systemConfirmSolve(Integer issueId) {
        LightningIssueApplyEntity issueApplyEntity = issueApplyRepository.findById(issueId).orElseThrow(() -> new LogicException("??????????????????"));
        if (!issueApplyEntity.getStatus().equals(LightningApplyStatus.TO_BE_CONFIRMED.getCode())) {
            return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????????????????????????????????????????????????????");
        }
        ConfirmSolveSignalRequest confirmSolveSignalRequest = new ConfirmSolveSignalRequest();
        confirmSolveSignalRequest.setInstanceId(issueApplyEntity.getInstanceId());
        Map<String, Object> variables = new HashMap<>(2);
        variables.put(WorkflowStatusFlag.TASK_STATUS.getName(), LightningApplyStatus.RESOLVED.getCode());

        LightningIssueGroupEntity groupEntity = lightningIssueGroupRepository.findByIssueId(issueId);
        // ???????????????????????????????????????????????????????????????????????????????????????
        issueApplyEntity.setStatus(LightningApplyStatus.RESOLVED.getCode());
        issueApplyEntity.setAutoConfirm(YesOrNoEnum.YES.getCode());
        // -2????????????
        issueApplyEntity.setLastUpdatedBy(-2);
        issueApplyEntity.setLastUpdatedOn(new Date());
        issueApplyRepository.save(issueApplyEntity);
        // ?????????????????????????????????????????????
        lightningIssueLogManager.saveIssueLogAction(issueId, LightningIssueLogActionEnum.AUTO_CONFIRM.getCode(), issueApplyEntity.getCreatedBy());
        // ?????????????????????????????????
        if (groupEntity != null) {
            Long groupId = Long.valueOf(groupEntity.getGroupId());
            chatWebSocketService.sendMessage(issueId, groupId, "???????????????????????????", LightningApplyStatus.RESOLVED.getDesc());
        }

        confirmSolveSignalRequest.setVariables(variables);
        // ??????????????????
        workflowInstanceService.sendConfirmIsSolveSignal(confirmSolveSignalRequest);
        return ServerResponse.ok("?????????????????????????????????????????????");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchSystemConfirmSolve(List<LightningIssueIdInfoDTO> issueList) {
        List<Integer> issueIds = issueList.stream().map(LightningIssueIdInfoDTO::getIssueId).collect(Collectors.toList());
        List<Integer> successIssueIdList = new ArrayList<>();
        List<LightningIssueGroupEntity> groupInfos = lightningIssueGroupRepository.findAllByIssueIdIn(issueIds);
        Map<Integer, LightningIssueGroupEntity> groupMap = groupInfos.stream().collect(Collectors.toMap(LightningIssueGroupEntity::getIssueId, g -> g));
        List<LightningIssueLogEntity> logList = new ArrayList<>();
        for (LightningIssueIdInfoDTO issue : issueList) {
            ConfirmSolveSignalRequest confirmSolveSignalRequest = new ConfirmSolveSignalRequest();
            confirmSolveSignalRequest.setInstanceId(issue.getInstanceId());
            Map<String, Object> variables = new HashMap<>(4);
            variables.put(WorkflowStatusFlag.TASK_STATUS.getName(), LightningApplyStatus.RESOLVED.getCode());
            confirmSolveSignalRequest.setVariables(variables);
            // ??????????????????
            try {
                workflowInstanceService.sendConfirmIsSolveSignal(confirmSolveSignalRequest);
                Integer issueId = issue.getIssueId();
                LightningIssueGroupEntity groupEntity = groupMap.get(issueId);
                // ?????????????????????????????????
                if (groupEntity != null) {
                    Long groupId = Long.valueOf(groupEntity.getGroupId());
                    chatWebSocketService.sendMessage(issueId, groupId, "???????????????????????????", LightningApplyStatus.RESOLVED.getDesc());
                }
                successIssueIdList.add(issueId);
                // ?????????????????????????????????????????????
                logList.add(lightningIssueLogManager.saveIssueLog(issueId, LightningIssueLogActionEnum.AUTO_CONFIRM.getCode(), issue.getCreatedBy()));
                // ??????redis??????
                lightningIssueManager.removeIssueId(issueId);
            } catch (Exception e) {
                log.error("???????????????????????????{}", e);
            }
        }
        issueApplyRepository.updateApplyByIds(YesOrNoEnum.YES.getCode(), LightningApplyStatus.RESOLVED.getCode(), -2, new Date(), successIssueIdList);
        logRepository.saveAll(logList);
    }

    @Override
    public PageImpl<LightningMySolvedDTO> listMySubmitted(QueryMySubmittedReq req) {
        Integer run = req.getRun();
        if (run == null || RunningStatusEnum.ALL.getCode().equals(run)) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????????????????????????????,????????????????????????");
        }
        Integer userId = UserHelper.getUserId();
        PageRequest pageable = PageRequest.of((req.getPage() == null || req.getPage() < 0) ? 0 : req.getPage(), (req.getSize() == null || req.getSize() == -1) ? Integer.MAX_VALUE : req.getSize());
        // ??????????????????????????????????????????????????????????????????????????????
        String keyWord = req.getKeyWord();
        List<Integer> userIds = null;
        if (StringUtils.isNotBlank(keyWord)) {
            ServerResponse<List<Integer>> userIdByUserName = passportFeignClient.getUserIdByUserName(keyWord);
            if (userIdByUserName.getCode() != ResponseCode.SUCCESS.getCode()) {
                throw new VerificationFailedException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????????????????ID????????????");
            }
            userIds = userIdByUserName.getData();
            if (CollectionUtils.isEmpty(userIds)) {
                userIds = null;
            }
        }
        List<Integer> stopStatusList;
        int isAuto = 0;
        if (RunningStatusEnum.RUNNING.getCode().equals(run)) {
            stopStatusList = Arrays.asList(LightningApplyStatus.TO_BE_ACCEPTED.getCode(), LightningApplyStatus.ACCEPTING.getCode(),
                    LightningApplyStatus.TO_BE_CONFIRMED.getCode(), LightningApplyStatus.UNRESOLVED.getCode());
            isAuto = 1;
        } else {
            stopStatusList = Collections.singletonList(LightningApplyStatus.REVOKED.getCode());
        }
        Page<Map> maps = issueApplyRepository.queryMySubmitted(keyWord, userIds, userId, stopStatusList, isAuto, pageable);
        List<LightningMySolvedDTO> mySolvedDtoPage = PageImpl.of(maps, x -> ConvertUtil.mapToObject(x, LightningMySolvedDTO.class)).getContent();
        if (CollectionUtils.isEmpty(mySolvedDtoPage)) {
            return PageImpl.of(mySolvedDtoPage, pageable, (int) maps.getTotalElements());
        }
        long totalElements = maps.getTotalElements();
        List<LightningMySolvedDTO> filterDeleteDto = new ArrayList<>();
        Set<Integer> deleteSet = redisCache.getCacheSet(RedisKeyConstants.IGNORE_ISSUE + userId);
        Set<Integer> currentSolverId = new HashSet<>();
        currentSolverId.add(userId);
        boolean empty = deleteSet.isEmpty();
        for (LightningMySolvedDTO issue : mySolvedDtoPage) {
            if (!empty && deleteSet.contains(issue.getIssueId())) {
                totalElements--;
            } else {
                filterDeleteDto.add(issue);
                currentSolverId.add(issue.getCurrentSolverId());
            }
        }

        if (CollectionUtils.isEmpty(filterDeleteDto)) {
            return PageImpl.of(filterDeleteDto, pageable, (int) totalElements);
        }
        Map<Integer, PassportUserInfoDTO> userInfoMap = passportFeignManager.getUserInfoMapFromRedis(currentSolverId);
        for (LightningMySolvedDTO everyMySolve : filterDeleteDto) {
            PassportUserInfoDTO currentSolverInfo = userInfoMap.get(everyMySolve.getCurrentSolverId());
            if (currentSolverInfo != null) {
                everyMySolve.setCurrentSolverName(currentSolverInfo.getNickname());
                everyMySolve.setCurrentSolverAvatar(currentSolverInfo.getAvatar());
            }
            PassportUserInfoDTO creator = userInfoMap.get(userId);
            if (creator != null) {
                everyMySolve.setHeadUrl(creator.getAvatar());
            }
        }
        return PageImpl.of(filterDeleteDto, pageable, (int) totalElements);
    }

    @Override
    public Page<LightningMyAcceptanceVO> listMyAcceptance(LightningMyAcceptanceRequest request) {
        Integer userId = UserHelper.getUserId();

        BooleanBuilder totalBuilder = new BooleanBuilder();

        QLightningIssueApplyEntity qApplyEntity = QLightningIssueApplyEntity.lightningIssueApplyEntity;
        // ????????? ?????????????????????????????????????????????????????????????????????
        String keyword = request.getKeyword();
        if (StringUtils.isNotBlank(keyword)) {
            BooleanBuilder keywordBuilder = new BooleanBuilder();
            keywordBuilder.or(qApplyEntity.description.like("%" + request.getKeyword() + "%"));
            ServerResponse<List<Integer>> userIdsResponse = passportFeignClient.getUserIdByUserName(keyword);
            if (ResponseCode.SUCCESS.getCode() != userIdsResponse.getCode()) {
                throw new VerificationFailedException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????????????????ID????????????");
            }
            List<Integer> userIds = userIdsResponse.getData();
            if (CollectionUtils.isNotEmpty(userIds)) {
                keywordBuilder.or(qApplyEntity.currentSolverId.in(userIds));
                keywordBuilder.or(qApplyEntity.createdBy.in(userIds));
            }
            totalBuilder.and(keywordBuilder);
        }

        // ????????????????????????
        Integer run = request.getRun();
        boolean running = RunningStatusEnum.RUNNING.getCode().equals(run);
        if (!RunningStatusEnum.ALL.getCode().equals(run)) {
            BooleanBuilder runningBuilder = new BooleanBuilder();
            List<Integer> stopStatusList = Arrays.asList(LightningApplyStatus.RESOLVED.getCode(), LightningApplyStatus.REVOKED.getCode());
            if (running) {
                runningBuilder.and(qApplyEntity.status.notIn(stopStatusList));
            } else {
                runningBuilder.and(qApplyEntity.status.in(stopStatusList));
            }
            totalBuilder.and(runningBuilder);
        }

        // ??????????????????????????????????????????????????????????????? (?????? - ??????????????????????????????????????? )
        // ???????????????????????????id?????????redis???
        // ???????????????????????????????????????redis???????????????key???
        //        ?????? ??? ????????????
        //      ????????? ??? ??????????????? ????????? ??????????????????

        BooleanBuilder myAcceptBuilder = new BooleanBuilder();
        boolean hasInit = true;
        if (running) {
            Set<Integer> ids = lightningIssueManager.checkMyAcceptanceIssueInfoInit(userId);
            hasInit = ids != null;
            if (hasInit) {
                if (ids.isEmpty()) {
                    return new org.springframework.data.domain.PageImpl<>(new ArrayList<>(), PageRequest.of(0, 20), 0);
                } else {
                    myAcceptBuilder.and(qApplyEntity.id.in(ids));
                }
            } else {
                // ???????????????
                lightningIssueManager.asynInitMyAcceptanceIds(userId);
            }
        }

        // ???????????? ??? ?????????????????????????????????????????????
        if (!running || !hasInit) {
            QLightningIssueLogEntity qLogEntity = QLightningIssueLogEntity.lightningIssueLogEntity;
            myAcceptBuilder.and(
                    qApplyEntity.id.in(
                            JPAExpressions.selectDistinct(qLogEntity.issueId)
                                    .from(qLogEntity)
                                    .where(
                                            qLogEntity.action.in(
                                                    LightningIssueLogActionEnum.TO_BE_ACCEPTED.getCode(),
                                                    LightningIssueLogActionEnum.LEADER_ADD.getCode(),
                                                    LightningIssueLogActionEnum.INVITE_HANDED_OVER.getCode()
                                            )
                                                    .and(qLogEntity.createdBy.eq(userId))
                                    )
                    )
            );
        }

        totalBuilder.and(myAcceptBuilder);

        // ????????????
        Integer page = request.getPage() == null || request.getPage() < 0 ? 0 : request.getPage();
        Integer size = request.getSize() == null ? -1 : request.getSize();
        Integer selectSize = size <= 0 ? 20 : size;
        boolean isSelectAll = page == 0 && size == -1;
        if (isSelectAll) {
            selectSize = 10000;
        }
        QLightningIssueGroupEntity qGroupEntity = QLightningIssueGroupEntity.lightningIssueGroupEntity;
        QueryResults<Tuple> queryResults = queryFactory.select(qApplyEntity.id, qGroupEntity.groupId, qGroupEntity.groupName, qApplyEntity.description, qApplyEntity.createdBy, qApplyEntity.currentSolverId, qApplyEntity.status, qApplyEntity.autoConfirm, qApplyEntity.lastUpdatedOn)
                .from(qApplyEntity).leftJoin(qGroupEntity).on(qApplyEntity.id.eq(qGroupEntity.issueId))
                .where(totalBuilder)
                .orderBy(qApplyEntity.status.asc(), qApplyEntity.lastUpdatedOn.desc())
                .offset(page * selectSize).limit(selectSize)
                .fetchResults();
        Long total = queryResults.getTotal();
        if (total == 0) {
            return new org.springframework.data.domain.PageImpl<>(new ArrayList<>(), PageRequest.of(0, 20), 0);
        }

        // ????????????id??????
        Set<Integer> relatedUserIds = new HashSet<>();
        List<Tuple> acceptedList = queryResults.getResults();
        acceptedList.forEach(apply -> {
            relatedUserIds.add(apply.get(qApplyEntity.createdBy));
            relatedUserIds.add(apply.get(qApplyEntity.currentSolverId));
        });

        // ?????? - ?????? redis ?????? ????????????
        Map<Integer, PassportUserInfoDTO> userInfoMap = passportFeignManager.getUserInfoMapFromRedis(relatedUserIds);

        // ???????????????
        Set<Integer> deleteSet = new HashSet<>(16);
        boolean deleteEmpty = true;
        if (!running) {
            deleteSet = redisCache.getCacheSet(RedisKeyConstants.IGNORE_ISSUE + userId);
            deleteEmpty = deleteSet.isEmpty();
        }

        List<LightningMyAcceptanceVO> myAcceptanceList = new ArrayList<>();
        // ?????????
        List<LightningMyAcceptanceVO> notTopList = new ArrayList<>();
        for (Tuple apply : acceptedList) {
            Integer issueId = apply.get(qApplyEntity.id);
            if (!deleteEmpty && deleteSet.contains(issueId)) {
                total--;
                continue;
            }
            LightningMyAcceptanceVO lightningMyAcceptanceVO = new LightningMyAcceptanceVO();
            lightningMyAcceptanceVO.setIssueId(issueId);
            lightningMyAcceptanceVO.setGroupId(apply.get(qGroupEntity.groupId));
            lightningMyAcceptanceVO.setGroupName(apply.get(qGroupEntity.groupName));
            lightningMyAcceptanceVO.setDescription(apply.get(qApplyEntity.description));
            Integer status = apply.get(qApplyEntity.status);
            lightningMyAcceptanceVO.setStatus(status);
            lightningMyAcceptanceVO.setAutoConfirm(apply.get(qApplyEntity.autoConfirm));
            lightningMyAcceptanceVO.setLastUpdatedOn(apply.get(qApplyEntity.lastUpdatedOn));
            Integer creatorId = apply.get(qApplyEntity.createdBy);
            lightningMyAcceptanceVO.setCreatedBy(creatorId);
            PassportUserInfoDTO creatorInfo = userInfoMap.get(creatorId);
            if (creatorInfo != null) {
                lightningMyAcceptanceVO.setCreatorName(creatorInfo.getNickname());
                lightningMyAcceptanceVO.setHeadUrl(creatorInfo.getAvatar());
            }
            Integer solverId = apply.get(qApplyEntity.currentSolverId);
            lightningMyAcceptanceVO.setCurrentSolverId(solverId);
            PassportUserInfoDTO solverInfo = userInfoMap.get(solverId);
            if (solverInfo != null) {
                lightningMyAcceptanceVO.setCurrentSolverName(solverInfo.getNickname());
            }
            if (LightningApplyStatus.UNRESOLVED.getCode().equals(status)) {
                myAcceptanceList.add(lightningMyAcceptanceVO);
            } else {
                notTopList.add(lightningMyAcceptanceVO);
            }
        }
        myAcceptanceList.addAll(notTopList);
        Pageable pageable = PageRequest.of(page, selectSize);
        if (isSelectAll) {
            pageable = PageRequest.of(page, total.intValue());
        }
        return new org.springframework.data.domain.PageImpl<>(myAcceptanceList, pageable, total);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServerResponse<LightningIssueItemVO> getIssueItem(Integer issueId) throws Exception {
        LightningIssueApplyEntity issueApplyEntity = issueApplyRepository.findById(issueId).orElseThrow(() -> new LogicException("????????????Id?????????"));
        Integer userId = UserHelper.getUserId();

        LightningIssueGroupEntity groupEntity = lightningIssueGroupRepository.findByIssueId(issueId);
        if (groupEntity == null) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "??????Id???" + issueId + "????????????????????????????????????");
        }
        String groupId = groupEntity.getGroupId();
        String userName = null;
        // ???????????????????????????????????????????????????????????????
        TaskEntity currentTask = workflowTaskService.getCurrentTaskByDefinitionKeyAndBusinessKey(lightningKey, String.valueOf(issueId));
        if (currentTask != null && currentTask.getStatus().equals(TaskState.UNTREATED.getState())) {
            // ?????????????????????????????????????????????????????????????????????????????????????????????????????????
            if (userId.equals(issueApplyEntity.getCurrentSolverId())
                    && userId.equals(Integer.valueOf(currentTask.getCandidateUsers()))) {
                workflowTaskService.updateTaskBeginStatus(currentTask);
                Integer status = issueApplyEntity.getStatus();
                if (status.equals(LightningApplyStatus.TO_BE_ACCEPTED.getCode()) || status.equals(LightningApplyStatus.UNRESOLVED.getCode())) {
                    // ????????????????????????????????? 2-?????????
                    issueApplyEntity.setStatus(LightningApplyStatus.ACCEPTING.getCode());
                    issueApplyEntity.setLastUpdatedOn(new Date());
                    issueApplyEntity.setLastUpdatedBy(userId);
                    issueApplyRepository.save(issueApplyEntity);
                }
                // ????????????????????????????????? ???????????????1-?????????
                lightningIssueLogManager.saveIssueLogAction(issueId, LightningIssueLogActionEnum.ACCEPTED.getCode(), userId);
                // ????????????????????????????????????
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // ?????????xxx????????????????????????
                        chatWebSocketService.sendActionMessage(issueId, Long.valueOf(groupId), userId, LightningApplyStatus.ACCEPTING);
                    }
                });
            }
        }

        LightningIssueItemVO issueItemVO = new LightningIssueItemVO();
        issueItemVO.setDescription(issueApplyEntity.getDescription());
        issueItemVO.setCreatedOn(issueApplyEntity.getCreatedOn());
        issueItemVO.setStatus(issueApplyEntity.getStatus());
        issueItemVO.setAutoConfirm(issueApplyEntity.getAutoConfirm());
        issueItemVO.setTask(currentTask);
        String attachments = issueApplyEntity.getAttachments();
        // ????????????????????????
        if (StringUtils.isNotBlank(attachments)) {
            String[] split = attachments.split(",");
            for (int i = 0; i < split.length; i++) {
                split[i] = upyunConfig.getPrefix() + split[i];
            }
            issueItemVO.setAttachments(new ArrayList<>(Arrays.asList(split)));
        } else {
            issueItemVO.setAttachments(Collections.emptyList());
        }
        issueItemVO.setGroupId(groupId);
        issueItemVO.setGroupName(groupEntity.getGroupName());

        List<LightningIssueRelevantUserEntity> allUserByIssueId = relevantUserRepository.findAllByIssueId(issueId);
        List<LightningIssueRelevantUserVO> relevantUserVos = new ArrayList<>();
        Set<Integer> relevantUserIds = new HashSet<>();
        for (LightningIssueRelevantUserEntity everyUser : allUserByIssueId) {
            Integer everyUserId = everyUser.getUserId();
            LightningIssueRelevantUserVO relevantUserVO = new LightningIssueRelevantUserVO();
            relevantUserVO.setUserId(everyUserId);
            relevantUserVos.add(relevantUserVO);
            relevantUserIds.add(everyUserId);
        }

        // ?????????????????????
        LightningUserInfoVO applicantVO = new LightningUserInfoVO();
        Integer applicantId = issueApplyEntity.getCreatedBy();
        applicantVO.setId(applicantId);

        Set<Integer> logUserIdList = new HashSet<>();
        logUserIdList.add(applicantId);
        List<Integer> actions = new ArrayList<>();
        actions.add(LightningIssueLogActionEnum.TIME_OUT_4.getCode());
        actions.add(LightningIssueLogActionEnum.TIME_OUT_24.getCode());
        actions.add(LightningIssueLogActionEnum.TIME_OUT_48.getCode());
        actions.add(LightningIssueLogActionEnum.LEADER_ADD.getCode());
        // ??????????????????????????????????????????????????????
        List<LightningIssueLogEntity> allByIssueId = logRepository.findAllByIssueIdAndActionNotIn(issueId, actions);
        List<LightningIssueLogVO> logs = new ArrayList<>();
        if (CollectionUtils.isEmpty(allByIssueId)) {
            throw new VerificationFailedException(ResponseCode.INTERNAL_ERROR.getCode(), "?????? ??????????????????????????????");
        }
        for (int i = 0; i < allByIssueId.size(); i++) {
            LightningIssueLogEntity everyLogEntity = allByIssueId.get(i);
            LightningIssueLogVO issueLogVO = new LightningIssueLogVO();
            Integer createdBy = everyLogEntity.getCreatedBy();
            issueLogVO.setUserId(createdBy);
            issueLogVO.setOperatingTime(everyLogEntity.getCreatedOn());
            issueLogVO.setOperatingType(everyLogEntity.getAction());
            if (i == 0) {
                issueLogVO.setDuration(null);
            } else {
                LightningIssueLogEntity lightningIssueLastLogEntity = allByIssueId.get(i - 1);
                Date lastCreatedOn = lightningIssueLastLogEntity.getCreatedOn();
                Date nextCreatedOn = everyLogEntity.getCreatedOn();
                LocalDateTime lastLocalDateTime = lastCreatedOn.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                LocalDateTime nextLocalDateTime = nextCreatedOn.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                long millisDuration = Duration.between(lastLocalDateTime, nextLocalDateTime).toMillis();
                issueLogVO.setDuration(millisDuration / 1000);
            }
            // ????????????????????????id
            logUserIdList.add(createdBy);
            logs.add(issueLogVO);
        }
        Set<Integer> collect = new HashSet<>();
        collect.addAll(logUserIdList);
        collect.addAll(relevantUserIds);
        // ?????????????????????
        Map<Integer, PassportUserInfoDTO> userMap = passportFeignManager.getUserInfoMapFromRedis(collect);
        // ????????????????????????
        Map<Integer, UserGroupSimpleDTO> userGroupFromCache = userManager.searchUserGroupFromCache(collect);
        // ????????????
        logs.forEach(log -> {
            Integer logUserId = log.getUserId();
            PassportUserInfoDTO user = userMap.getOrDefault(logUserId, null);
            if (Objects.nonNull(user)) {
                log.setNickName(user.getNickname());
                log.setAvatar(user.getAvatar());
            }
            UserGroupSimpleDTO userGroup = userGroupFromCache.getOrDefault(logUserId, null);
            if (Objects.nonNull(userGroup)) {
                // ????????????????????????
                log.setGroupDesc(userGroup.getGroups().get(0).getGroupDecs());
            }
        });

        // ???????????????
        relevantUserVos.forEach(relevantUser -> {
            Integer relevantUserId = relevantUser.getUserId();
            PassportUserInfoDTO user = userMap.getOrDefault(relevantUserId, null);
            if (Objects.nonNull(user)) {
                relevantUser.setNickName(user.getNickname());
                relevantUser.setAvatar(user.getAvatar());
            }
            UserGroupSimpleDTO userGroup = userGroupFromCache.getOrDefault(relevantUserId, null);
            if (Objects.nonNull(userGroup)) {
                relevantUser.setGroupDesc(userGroup.getGroups().get(0).getGroupDecs());
            }
        });

        // ???????????????
        PassportUserInfoDTO applicantInfo = userMap.getOrDefault(applicantId, null);
        if (Objects.nonNull(applicantInfo)) {
            applicantVO.setNickName(applicantInfo.getNickname());
            applicantVO.setAvatar(applicantInfo.getAvatar());
        }
        UserGroupSimpleDTO applicantGroupInfo = userGroupFromCache.getOrDefault(applicantId, null);
        if (Objects.nonNull(applicantGroupInfo)) {
            applicantVO.setGroupDesc(applicantGroupInfo.getGroups().get(0).getGroupDecs());
        }
        if (userId.equals(applicantId)) {
            userName = applicantVO.getNickName();
        } else {
            PassportUserInfoDTO operatorInfo = userMap.getOrDefault(userId, null);
            if (Objects.nonNull(operatorInfo)) {
                userName = operatorInfo.getNickname();
            }
        }

        // ??????????????????????????????????????????
        if (userId.equals(issueApplyEntity.getCurrentSolverId())) {
            issueItemVO.setUserIssueRole(LightningIssueRoleType.ASSIGNEE.getCode());
        } else if (userId.equals(applicantVO.getId())) {
            issueItemVO.setUserIssueRole(LightningIssueRoleType.INITIATOR.getCode());
        } else {
            issueItemVO.setUserIssueRole(LightningIssueRoleType.OTHER.getCode());
        }
        LightningIssueEvaluationEntity byIssueIdAndInstanceId = evaluationRepository.findByIssueIdAndInstanceId(issueId, issueApplyEntity.getInstanceId());
        issueItemVO.setEvaluation(byIssueIdAndInstanceId);
        issueItemVO.setApplicant(applicantVO);
        issueItemVO.setLogs(logs);
        issueItemVO.setRelevantUserVos(relevantUserVos);
        issueItemVO.setStatusMap(STATUS_MAP);
        issueItemVO.setLogTypeMap(LOG_TYPE_MAP);
        issueItemVO.setUserId(userId);
        issueItemVO.setUserName(userName);
        return ServerResponse.ok(issueItemVO);
    }

    @Override
    public ServerResponse<List<LightningUserInfoVO>> listSolvers(Integer issueId) {
        QLightningIssueLogEntity qLightningIssueLogEntity = QLightningIssueLogEntity.lightningIssueLogEntity;
        List<Integer> operatorList = queryFactory.selectDistinct(qLightningIssueLogEntity.createdBy)
                .from(qLightningIssueLogEntity)
                .where(qLightningIssueLogEntity.issueId.eq(issueId)
                        .and(qLightningIssueLogEntity.action.eq(LightningIssueLogActionEnum.ACCEPTED.getCode())))
                .fetch();
        // ?????????????????????id??????????????????????????????
        operatorList.removeIf(operatorId -> managerIdList.stream().anyMatch(managerId -> managerId.equals(operatorId)));
        List<PassportUserInfoDTO> data = passportFeignManager.getUserInfoListFromRedis(operatorList);
        List<LightningUserInfoVO> userInfoListVO = new ArrayList<>();
        for (PassportUserInfoDTO passportUserInfoDTO : data) {
            LightningUserInfoVO lightningUserInfoVO = new LightningUserInfoVO();
            lightningUserInfoVO.setId(passportUserInfoDTO.getId());
            lightningUserInfoVO.setAvatar(passportUserInfoDTO.getAvatar());
            lightningUserInfoVO.setNickName(passportUserInfoDTO.getNickname());
            userInfoListVO.add(lightningUserInfoVO);
        }
        return ServerResponse.ok(userInfoListVO);
    }

    @Override
    public ServerResponse leaderInvite(Integer issueId, Integer addUserId) throws Exception {
        Integer userId = UserHelper.getUserId();
        LightningIssueApplyEntity issue = issueApplyRepository.findById(issueId).orElseThrow(() -> new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????ID?????????"));
        Integer status = issue.getStatus();
        if (LightningApplyStatus.RESOLVED.getCode().equals(status) || LightningApplyStatus.REVOKED.getCode().equals(status) || LightningApplyStatus.TO_BE_CONFIRMED.getCode().equals(status)) {
            throw new GlobalRuntimeException(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????????????????????????????????????????");
        }
        // ????????????????????????
        TaskEntity currentTask = workflowTaskService.getCurrentTaskByDefinitionKeyAndBusinessKey(lightningKey, String.valueOf(issueId));
        if (currentTask == null) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????????????????????????????????????????????????????????????????Id??????" + issueId);
        }
        String taskId = currentTask.getId();
        transferIssue(issue, issue.getCurrentSolverId(), issueId, addUserId, taskId, TransferTypeEnum.INVITE_TRANSFER.getCode(), userId);
        return ServerResponse.ok("??????????????????");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServerResponse transferIssueToAssignee(IssueTransferReq req) throws Exception {
        Integer userId = UserHelper.getUserId();
        Integer issueId = req.getIssueId();
        Integer assigneeId = req.getAssigneeId();
        // ???????????????????????????????????????
        LightningIssueApplyEntity issueApplyEntity = issueApplyRepository.findById(issueId).orElseThrow(() -> new LogicException("????????????Id?????????"));
        if (!userId.equals(issueApplyEntity.getCurrentSolverId())) {
            return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????????????????????????????????????????????????????");
        }
        if (!LightningApplyStatus.ACCEPTING.getCode().equals(issueApplyEntity.getStatus())) {
            return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????????????????????????????????????????");
        }
        if (userId.equals(assigneeId)) {
            return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????");
        }
        if (assigneeId.equals(issueApplyEntity.getCreatedBy())) {
            return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????????????????");
        }
        transferIssue(issueApplyEntity, userId, issueId, assigneeId, req.getTaskId(), TransferTypeEnum.SOLVER_TRANSFER.getCode(), null);
        return ServerResponse.ok("??????????????????");
    }

    @Transactional(rollbackFor = Exception.class)
    public void transferIssue(LightningIssueApplyEntity issueApplyEntity, Integer userId, Integer issueId, Integer assigneeId, String taskId, Integer transferType, Integer leaderId) throws Exception {

        // ????????????????????????
        LightningIssueGroupEntity groupInfo = lightningIssueGroupRepository.findByIssueId(issueId);
        if (groupInfo == null) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "???????????????????????????????????????????????????????????????");
        }
        Long groupId = Long.valueOf(groupInfo.getGroupId());

        // ??????????????????????????????????????????????????????????????????????????????????????????????????????
        LightningIssueRelevantUserEntity byIssueIdAndUserId = relevantUserRepository.findByIssueIdAndUserId(issueId, assigneeId);
        if (byIssueIdAndUserId == null) {
            // ??????????????????????????????????????????????????????
            LightningIssueRelevantUserEntity relevantUserEntity = addIssueRelevantUser(issueId, assigneeId, userId);
            relevantUserRepository.save(relevantUserEntity);
            // ???????????????????????????
            chatWebSocketService.addUserToGroup(issueId, groupId, userId, assigneeId);
        }
        // ??????????????????????????????????????????
        TaskForm form = new TaskForm();
        List<TaskFormItem> formData = new ArrayList<>();
        TaskFormItem taskFormItem1 = new TaskFormItem();
        TaskFormItem taskFormItem2 = new TaskFormItem();
        taskFormItem1.setName(WorkflowStatusFlag.TASK_STATUS.getName());
        taskFormItem1.setValue(LightningApplyStatus.HANDED_OVER.getCode());
        formData.add(taskFormItem1);
        taskFormItem2.setName(InstanceVariableParam.RECEIVER.getText());
        taskFormItem2.setValue(assigneeId);
        formData.add(taskFormItem2);
        form.setFormData(formData);
        form.setId(taskId);
        if (TransferTypeEnum.LEAVE_TRANSFER.getCode().equals(transferType)) {
            issueApplyEntity.setLastUpdatedBy(-2);
            // ????????????????????????: ????????????
            lightningIssueLogManager.saveIssueLogAction(issueId, LightningIssueLogActionEnum.AUTO_HANDED_OVER.getCode(), userId);
            // ??????????????????????????????????????????????????????
            lightningIssueLogManager.saveIssueLogAction(issueId, LightningIssueLogActionEnum.TO_BE_ACCEPTED.getCode(), assigneeId);
            workflowTaskService.saveTask(form, true, false, true, userId.longValue());
            chatWebSocketService.sendAssociateMessage(issueId, groupId, userId, assigneeId, TransferTypeEnum.LEAVE_TRANSFER.getCode());
        } else if (TransferTypeEnum.INVITE_TRANSFER.getCode().equals(transferType)) {
            issueApplyEntity.setLastUpdatedBy(leaderId);
            // ????????????????????????: ?????????????????????
            lightningIssueLogManager.saveIssueLogAction(issueId, LightningIssueLogActionEnum.INVITE_HANDED_OVER.getCode(), leaderId);
            // ??????????????????????????????????????????????????????
            lightningIssueLogManager.saveIssueLogAction(issueId, LightningIssueLogActionEnum.TO_BE_ACCEPTED.getCode(), assigneeId);
            workflowTaskService.saveTask(form, true, false, true, userId.longValue());
            chatWebSocketService.sendAssociateMessage(issueId, groupId, leaderId, assigneeId, TransferTypeEnum.INVITE_TRANSFER.getCode());
        } else if (TransferTypeEnum.SOLVER_TRANSFER.getCode().equals(transferType)) {
            issueApplyEntity.setLastUpdatedBy(userId);
            // ????????????????????????: ?????????????????????????????? ???  ??????????????????????????????????????????????????????????????????????????????
            lightningIssueLogManager.saveIssueLogAction(issueId, LightningIssueLogActionEnum.HANDED_OVER.getCode(), userId);
            // ??????????????????????????????????????????????????????
            lightningIssueLogManager.saveIssueLogAction(issueId, LightningIssueLogActionEnum.TO_BE_ACCEPTED.getCode(), assigneeId);
            workflowTaskService.saveTask(form, true, false, false, userId.longValue());
            chatWebSocketService.sendAssociateMessage(issueId, groupId, userId, assigneeId, TransferTypeEnum.SOLVER_TRANSFER.getCode());
        } else {
            throw new RuntimeException("transferType??????");
        }
        issueApplyEntity.setCurrentSolverId(assigneeId);
        issueApplyEntity.setLastUpdatedOn(new Date());
        issueApplyRepository.save(issueApplyEntity);

        // ??????redis??????
        lightningIssueManager.addIssueId(issueId, assigneeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServerResponse finishIssue(IssueResolvedReq req) throws Exception {
        // ???????????????????????? ???3-???????????????????????????????????????????????????????????????3-???????????????
        Integer userId = UserHelper.getUserId();
        String issueReason = req.getIssueReason();
        Integer issueId = req.getIssueId();
        LightningIssueApplyEntity issueApplyEntity = issueApplyRepository.findById(issueId).orElseThrow(() -> new LogicException("??????Id?????????"));
        if (!userId.equals(issueApplyEntity.getCurrentSolverId())) {
            return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????????????????????????????????????????");
        }
        if (!LightningApplyStatus.ACCEPTING.getCode().equals(issueApplyEntity.getStatus())) {
            return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????????????????????????????????????????????????????");
        }
        issueApplyEntity.setStatus(LightningApplyStatus.TO_BE_CONFIRMED.getCode());
        issueApplyEntity.setLastUpdatedOn(new Date());
        issueApplyEntity.setLastUpdatedBy(userId);
        issueApplyEntity.setIssueReason(issueReason);
        issueApplyEntity.setIssueDepartmentId(req.getIssueDepartmentId());
        int demand = req.getDemand() ? YesOrNoEnum.YES.getCode() : YesOrNoEnum.NO.getCode();
        issueApplyEntity.setDemand(demand);

        // ????????????
        TaskForm taskForm = new TaskForm();
        List<TaskFormItem> formData = new ArrayList<>();
        TaskFormItem taskFormItem1 = new TaskFormItem();
        TaskFormItem taskFormItem2 = new TaskFormItem();
        taskFormItem1.setName(WorkflowStatusFlag.TASK_STATUS.getName());
        taskFormItem1.setValue(LightningApplyStatus.TO_BE_CONFIRMED.getCode());
        formData.add(taskFormItem1);
        taskFormItem2.setName(InstanceVariableParam.CAUSE_SUMMARY.getText());
        taskFormItem2.setValue(issueReason);
        formData.add(taskFormItem2);
        taskForm.setFormData(formData);
        taskForm.setId(req.getTaskId());
        workflowTaskService.saveTask(taskForm, true, false, false, userId.longValue());
        issueApplyRepository.save(issueApplyEntity);
        lightningIssueLogManager.saveIssueLogAction(issueId, LightningIssueLogActionEnum.SUBMIT_CONFIRMED.getCode(), userId, issueReason);

        // ???????????????????????????
        LightningIssueGroupEntity groupEntity = lightningIssueGroupRepository.findByIssueId(issueId);
        if (groupEntity != null) {
            Long groupId = Long.valueOf(groupEntity.getGroupId());
            chatWebSocketService.sendActionMessage(issueId, groupId, userId, LightningApplyStatus.TO_BE_CONFIRMED);
        }
        return ServerResponse.ok("?????????????????????");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServerResponse evaluateIssue(IssueEvaluateReq req) {
        Integer userId = UserHelper.getUserId();
        Integer issueId = req.getIssueId();
        LightningIssueApplyEntity issueApplyEntity = issueApplyRepository.findById(issueId).orElseThrow(() -> new LogicException("??????????????????"));
        if (!userId.equals(issueApplyEntity.getCreatedBy())) {
            return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????????????????????????????");
        }
        if (!LightningApplyStatus.RESOLVED.getCode().equals(issueApplyEntity.getStatus())) {
            return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????????????????????????????????????????");
        }
        Integer bestPersonId = req.getBestPersonId();
        if (bestPersonId != null) {
            // ????????????????????????????????????????????????????????????
            List<LightningIssueLogEntity> allByIssueIdAndCreatedBy = logRepository.findAllByIssueIdAndCreatedBy(issueId, bestPersonId);
            if (CollectionUtils.isEmpty(allByIssueIdAndCreatedBy)) {
                return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????????????????????????????");
            }
        }
        LightningIssueEvaluationEntity evaluationEntity = new LightningIssueEvaluationEntity();
        evaluationEntity.setIssueId(issueId);
        evaluationEntity.setBestPersonId(bestPersonId);
        evaluationEntity.setBestPersonName(req.getBestPersonName());
        evaluationEntity.setScore(req.getScore());
        evaluationEntity.setCreatedBy(userId);
        evaluationEntity.setCreatedAt(new Date());
        evaluationEntity.setLastUpdatedBy(userId);
        evaluationEntity.setLastUpdatedAt(new Date());
        evaluationEntity.setInstanceId(issueApplyEntity.getInstanceId());
        LightningIssueEvaluationEntity save = evaluationRepository.save(evaluationEntity);
        return ServerResponse.ok(save.getId());
    }

    @Override
    public DutyRosterEntity getCurrentDutyRoster() {
        QDutyRosterEntity qDutyRosterEntity = QDutyRosterEntity.dutyRosterEntity;
        // ????????????????????????????????????????????????????????????
        Date todayDate = new Date();
        String queryDate = DateUtil.formatDate(todayDate, "yyyy-MM-dd");
        // ???????????????????????????????????????sql??????DATE_FORMAT(qDutyRosterEntity.dutyDate,'%Y-%m-%d')
        StringTemplate dateExpr = Expressions.stringTemplate("DATE_FORMAT({0},'%Y-%m-%d')", qDutyRosterEntity.dutyDate);
        DutyRosterEntity dutyRosterEntity = queryFactory.selectFrom(qDutyRosterEntity).where(dateExpr.eq(queryDate)).fetchFirst();
        if (dutyRosterEntity == null) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "?????????????????????????????????");
        } else {
            return dutyRosterEntity;
        }
    }

    @Override
    public DutyPlanEntity getTodayDutyPlan() {
        DutyPlanEntity todayPlan = dutyPlanRepository.findTechnicalTodayDutyUser(YesOrNoEnum.YES.getCode(), LocalDateTime.of(LocalDate.now(), LocalTime.MIN), YesOrNoEnum.YES.getCode());
        if (todayPlan == null) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "?????????????????????????????????");
        }
        return todayPlan;
    }

    @Override
    public List<GroupAndUserAndCountVO> getAddressBook(DepartmentsAndEmployeesRequest request) {
        DutyPlanEntity dutyRoster = getTodayDutyPlan();
        request.setDutyId(dutyRoster.getPersonId());
        ServerResponse<List<GroupAndUserAndCountVO>> response = passportFeignClient.getDepartmentsAndEmployees(request);
        if (response.getCode() != ResponseCode.SUCCESS.getCode()) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "?????????????????????????????????");
        }
        List<GroupAndUserAndCountVO> data = response.getData();
        Map<String, GroupAndUserAndCountVO> dataMap = new HashMap<>(16);
        List<Integer> userIdList = new ArrayList<>();
        data.forEach(info -> {
            if (info.getType() == 2) {
                info.setIssueCount(0L);
                userIdList.add(info.getValue());
                dataMap.put(info.getValue().toString(), info);
            }
        });
        if (CollectionUtils.isNotEmpty(userIdList)) {
            // ??????????????????
            List<LightningIssueCountDTO> userIssueCountList = issueApplyRepository.findUserIssueCount(userIdList);
            userIssueCountList.forEach(user -> {
                GroupAndUserAndCountVO info = dataMap.get(user.getUserId().toString());
                if (info != null) {
                    info.setIssueCount(user.getCount());
                }
            });
        }
        return data;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Integer> batchRevokeIssue(BatchRevokeRequest request) {
        Integer userId = UserHelper.getUserId();
        if (userId == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(),
                    "??????????????????????????????");
        }
        // ??????id??????
        List<RevokeRequest> revokeList = request.getRevokeIssueList();
        List<Integer> issueIds = revokeList.stream().map(RevokeRequest::getIssueId)
                .distinct().collect(Collectors.toList());
        // ??????????????????????????????????????????
        List<LightningIssueApplyEntity> issueList = issueApplyRepository.findAllByIdInAndStatusNotInAndCreatedBy(issueIds,
                Arrays.asList(LightningApplyStatus.TO_BE_CONFIRMED.getCode(),
                        LightningApplyStatus.RESOLVED.getCode(),
                        LightningApplyStatus.UNRESOLVED.getCode(),
                        LightningApplyStatus.REVOKED.getCode()), userId);
        if (CollectionUtils.isEmpty(issueList)) {
            return issueIds;
        }
        // ????????????????????????
        Map<String, RevokeRequest> revokeIssueMap = new HashMap<>(16);
        for (RevokeRequest req : revokeList) {
            revokeIssueMap.put(req.getIssueId().toString(), req);
        }
        for (LightningIssueApplyEntity apply : issueList) {
            RevokeRequest req = revokeIssueMap.get(apply.getId().toString());
            req.setInstanceId(apply.getInstanceId());
        }
        // ???????????????????????????(???????????????????????????id)
        List<String> cannotRevokeInstanceIds = workflowInstanceService.revokeLightningInstanceById(revokeList);
        // ???????????????????????????????????????
        issueList.removeIf(issue -> cannotRevokeInstanceIds.contains(issue.getInstanceId()));
        // ?????????????????????
        if (CollectionUtils.isEmpty(issueList)) {
            return issueIds;
        }
        // ???????????????????????????
        // ?????????????????????id
        List<Integer> revokeIds = issueList.stream().map(LightningIssueApplyEntity::getId)
                .collect(Collectors.toList());
        List<LightningIssueGroupEntity> groupInfoList = lightningIssueGroupRepository.findAllByIssueIdIn(revokeIds);
        Map<String, Long> groupInfoMap = new HashMap<>(16);
        groupInfoList.forEach(group -> {
            String groupId = group.getGroupId();
            if (StringUtils.isNotBlank(groupId)) {
                groupInfoMap.put(group.getIssueId().toString(), Long.valueOf(groupId));
            }
        });
        // ??????????????????
        List<LightningIssueLogEntity> revokeLogList = new ArrayList<>();
        issueList.forEach(issue -> {
            // ????????????????????????
            Integer issueId = issue.getId();
            Long groupId = groupInfoMap.get(issueId.toString());
            if (groupId != null) {
                chatWebSocketService.sendActionMessage(issueId, groupId, userId, LightningApplyStatus.REVOKED);
                chatWebSocketService.closeGroup(groupId, issueId);
            }
            // ??????????????????s
            issue.setStatus(LightningApplyStatus.REVOKED.getCode());
            issue.setLastUpdatedOn(new Date());
            issue.setLastUpdatedBy(userId);
            RevokeRequest revokeRequest = revokeIssueMap.get(issueId.toString());
            if (revokeRequest != null) {
                issue.setRevokeReason(revokeRequest.getRevokeReason());
            }
            revokeLogList.add(lightningIssueLogManager.saveIssueLog(issueId, LightningIssueLogActionEnum.REVOKED.getCode(), userId));

            // ??????redis??????
            lightningIssueManager.removeIssueId(issueId);
        });
        issueApplyRepository.saveAll(issueList);
        logRepository.saveAll(revokeLogList);
        return issueIds.stream().filter(id -> !revokeIds.contains(id)).collect(Collectors.toList());
    }

    @Override
    public ServerResponse urgeIssue(Integer issueId) {
        Integer userId = UserHelper.getUserId();
        LightningIssueApplyEntity issue = issueApplyRepository.findById(issueId).orElse(null);
        if (issue == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(),
                    "?????????????????????????????????????????????");
        }
        Integer status = issue.getStatus();
        if (status == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(),
                    "????????????????????????????????????????????????");
        }
        if (status.equals(LightningApplyStatus.TO_BE_CONFIRMED.getCode())
                || status.equals(LightningApplyStatus.RESOLVED.getCode()) || status.equals(LightningApplyStatus.REVOKED.getCode())) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(),
                    "?????????????????????????????????????????????????????????");
        }
        String urgeRedisKey = RedisKeyConstants.URGE_RESTRICT + userId + ":issueId:" + issueId;
        Boolean result = redisTemplate.hasKey(urgeRedisKey);
        if (result != null && result) {
            return ServerResponse.fail(ResponseCode.REFUSE_EXECUTE.getCode(), "????????????????????????????????????");
        }
        workflowInstanceService.urgeCurrentTaskByBusinessKey(lightningKey, issueId.toString());
        redisTemplate.opsForValue().set(urgeRedisKey, new Date(), 30, TimeUnit.MINUTES);
        // ??????????????????
        try {
            LightningIssueGroupEntity groupInfo = lightningIssueGroupRepository.findByIssueId(issueId);
            Long groupId = Long.valueOf(groupInfo.getGroupId());
            chatWebSocketService.sendActionMessage(issueId, groupId, userId, LightningApplyStatus.URGE);
        } catch (Exception e) {
            log.error("?????????????????????????????????{}", e);
        }
        return ServerResponse.ok("????????????");
    }

    @Override
    public ServerResponse<LightningUsersGroupIdsVO> getGroupIds() {
        Integer userId = UserHelper.getUserId();
        QLightningIssueGroupEntity groupEntity = QLightningIssueGroupEntity.lightningIssueGroupEntity;
        QLightningIssueRelevantUserEntity relevantUserEntity = QLightningIssueRelevantUserEntity.lightningIssueRelevantUserEntity;
        QLightningIssueApplyEntity applyEntity = QLightningIssueApplyEntity.lightningIssueApplyEntity;
        List<String> groupIds = new ArrayList<>();
        List<Tuple> fetch = queryFactory.select(groupEntity.groupId, applyEntity.id, applyEntity.status, applyEntity.autoConfirm)
                .from(applyEntity)
                .where(applyEntity.id.in(JPAExpressions.selectDistinct(relevantUserEntity.issueId)
                        .from(relevantUserEntity)
                        .where(relevantUserEntity.userId.eq(userId))
                )).leftJoin(groupEntity).on(groupEntity.issueId.eq(applyEntity.id)).fetch();
        if (CollectionUtils.isNotEmpty(fetch)) {
            for (Tuple userGroup : fetch) {
                // ?????????????????????????????????????????????
                boolean result = (LightningApplyStatus.REVOKED.getCode().equals(userGroup.get(applyEntity.status)) || (LightningApplyStatus.RESOLVED.getCode().equals(userGroup.get(applyEntity.status))) && Objects.equals(userGroup.get(applyEntity.autoConfirm), YesOrNoEnum.NO.getCode()));
                if (!result) {
                    groupIds.add(userGroup.get(groupEntity.groupId));
                }
            }
        }

        PassportUserInfoDTO userInfo = passportFeignManager.getUserInfoFromRedis(userId);

        ServerResponse<Boolean> serverResponse = passportFeignClient.judgeIsLeader(userId);
        if (serverResponse.getCode() != ResponseCode.SUCCESS.getCode()) {
            throw new VerificationFailedException(ResponseCode.INTERNAL_ERROR.getCode(), "???????????????????????????????????????????????????");
        }
        LightningUsersGroupIdsVO usersGroupIdsVO = new LightningUsersGroupIdsVO();
        usersGroupIdsVO.setServerTime(new Date());
        usersGroupIdsVO.setGroupIds(groupIds);
        usersGroupIdsVO.setUserId(userId);
        usersGroupIdsVO.setUserName(userInfo.getNickname());
        usersGroupIdsVO.setLeader(serverResponse.getData());
        return ServerResponse.ok(usersGroupIdsVO);
    }

    @Override
    public Page<LightningMyParticipatedVO> getParticipatedList(QueryMySubmittedReq req) {
        Integer userId = UserHelper.getUserId();
        QLightningIssueGroupEntity groupEntity = QLightningIssueGroupEntity.lightningIssueGroupEntity;
        QLightningIssueRelevantUserEntity relevantUserEntity = QLightningIssueRelevantUserEntity.lightningIssueRelevantUserEntity;
        QLightningIssueApplyEntity applyEntity = QLightningIssueApplyEntity.lightningIssueApplyEntity;
        String keyWord = req.getKeyWord();
        BooleanBuilder builder = new BooleanBuilder();
        if (StringUtils.isNotBlank(keyWord)) {
            builder.and(applyEntity.description.like("%" + keyWord + "%"));
        }
        JPAQuery<Tuple> jpaQuery = queryFactory.select(applyEntity.id, applyEntity.description, applyEntity.createdBy, applyEntity.currentSolverId, applyEntity.status, applyEntity.autoConfirm, applyEntity.lastUpdatedOn, groupEntity.groupId)
                .from(applyEntity)
                .where(applyEntity.id.in(JPAExpressions.selectDistinct(relevantUserEntity.issueId)
                        .from(relevantUserEntity)
                        .where(relevantUserEntity.userId.eq(userId))
                ).and(builder)).leftJoin(groupEntity).on(groupEntity.issueId.eq(applyEntity.id)).orderBy(applyEntity.status.asc(), applyEntity.lastUpdatedOn.desc());
        long total = jpaQuery.fetchCount();
        // ????????????
        int page = req.getPage() == null || req.getPage() < 0 ? 0 : req.getPage();
        Integer size = req.getSize();
        if (size == null) {
            size = 20;
        } else if (page == 0 && size == -1) {
            size = (total == 0 ? 20 : (int) total);
        } else if (size < 0) {
            size = 20;
        }
        Pageable pageable = PageRequest.of(page, size);
        List<Tuple> fetch = jpaQuery.offset(pageable.getOffset()).limit(pageable.getPageSize()).fetch();
        if (CollectionUtils.isEmpty(fetch)) {
            return new org.springframework.data.domain.PageImpl<>(new ArrayList<>(), pageable, total);
        }
        List<Tuple> filterList = new ArrayList<>();
        // ????????????id??????
        Set<Integer> relatedUserIds = new HashSet<>();
        relatedUserIds.add(userId);
        // ??????????????????
        for (Tuple userGroup : fetch) {
            // ?????????????????????????????????????????????
            boolean result = (LightningApplyStatus.REVOKED.getCode().equals(userGroup.get(applyEntity.status)) || LightningApplyStatus.RESOLVED.getCode().equals(userGroup.get(applyEntity.status))) && userGroup.get(applyEntity.autoConfirm).equals(YesOrNoEnum.NO.getCode());
            if (!result) {
                relatedUserIds.add(userGroup.get(applyEntity.createdBy));
                relatedUserIds.add(userGroup.get(applyEntity.currentSolverId));
                filterList.add(userGroup);
            }
        }
        if (CollectionUtils.isEmpty(filterList)) {
            return new org.springframework.data.domain.PageImpl<>(new ArrayList<>(), pageable, total);
        }
        // ????????????????????????
        Map<Integer, PassportUserInfoDTO> userInfoMap = passportFeignManager.getUserInfoMapFromRedis(relatedUserIds);
        // ????????????
        List<LightningMyParticipatedVO> returnData = new ArrayList<>();
        for (Tuple tuple : filterList) {
            LightningMyParticipatedVO every = new LightningMyParticipatedVO();
            Integer createdBy = tuple.get(applyEntity.createdBy);
            Integer currentSolverId = tuple.get(applyEntity.currentSolverId);
            PassportUserInfoDTO createdByInfoDTO = userInfoMap.get(createdBy);
            PassportUserInfoDTO currentSolverInfoDTO = userInfoMap.get(currentSolverId);
            every.setIssueId(tuple.get(applyEntity.id));
            every.setCreatedBy(createdBy);
            every.setCreatorName(createdByInfoDTO.getNickname());
            every.setHeadUrl(createdByInfoDTO.getAvatar());
            every.setCurrentSolverId(currentSolverId);
            every.setCurrentSolverName(currentSolverInfoDTO.getNickname());
            every.setCurrentSolverHeadUrl(currentSolverInfoDTO.getAvatar());
            every.setDescription(tuple.get(applyEntity.description));
            every.setGroupId(tuple.get(groupEntity.groupId));
            every.setStatus(tuple.get(applyEntity.status));
            every.setLastUpdatedOn(tuple.get(applyEntity.lastUpdatedOn));
            returnData.add(every);
        }
        return new org.springframework.data.domain.PageImpl<>(returnData,
                pageable, total);
    }

    @Override
    public Page<LightningMyAcceptanceVO> listLeaderOverTime(LightningMyAcceptanceRequest request) {
        Integer userId = UserHelper.getUserId();
        if (userId == null || userId == -1) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????????????????");
        }
        if (request == null) {
            request = new LightningMyAcceptanceRequest();
        }
        // query dsl
        QLightningIssueApplyEntity applyEntity = QLightningIssueApplyEntity.lightningIssueApplyEntity;
        QLightningTimeoutLogEntity timeoutLogEntity = QLightningTimeoutLogEntity.lightningTimeoutLogEntity;
        QLightningIssueGroupEntity groupEntity = QLightningIssueGroupEntity.lightningIssueGroupEntity;
        BooleanBuilder keywordBuilder = new BooleanBuilder();
        // ?????????
        String keyword = request.getKeyword();
        // ?????????????????????????????????????????????????????????????????????
        if (StringUtils.isNotBlank(keyword)) {
            keywordBuilder.or(applyEntity.description.like("%" + request.getKeyword() + "%"));
            ServerResponse<List<Integer>> userIdsResponse = passportFeignClient.getUserIdByUserName(keyword);
            if (userIdsResponse.getCode() != ResponseCode.SUCCESS.getCode()) {
                throw new VerificationFailedException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????????????????ID????????????");
            }
            List<Integer> userIds = userIdsResponse.getData();
            if (CollectionUtils.isNotEmpty(userIds)) {
                keywordBuilder.or(applyEntity.currentSolverId.in(userIds));
                keywordBuilder.or(applyEntity.createdBy.in(userIds));
            }
        }

        BooleanBuilder totalBuilder = new BooleanBuilder();
        totalBuilder.and(keywordBuilder);

        // ????????????????????????
        Integer run = request.getRun();
        if (!RunningStatusEnum.ALL.getCode().equals(run)) {
            BooleanBuilder runningBuilder = new BooleanBuilder();
            List<Integer> stopStatusList = Arrays.asList(LightningApplyStatus.RESOLVED.getCode(), LightningApplyStatus.REVOKED.getCode());
            if (RunningStatusEnum.RUNNING.getCode().equals(run)) {
                runningBuilder.and(applyEntity.status.notIn(stopStatusList));
            } else {
                runningBuilder.and(applyEntity.status.in(stopStatusList));
            }
            totalBuilder.and(runningBuilder);
        }

        // ??????
        JPAQuery<Tuple> tupleJpaQuery = queryFactory.select(applyEntity.id, applyEntity.description, applyEntity.createdBy, applyEntity.currentSolverId, applyEntity.status, applyEntity.autoConfirm, applyEntity.lastUpdatedOn, groupEntity.groupId, groupEntity.groupName)
                .from(applyEntity)
                .where(applyEntity.id.in(JPAExpressions.selectDistinct(timeoutLogEntity.issueId)
                        .from(timeoutLogEntity)
                        .where(timeoutLogEntity.userId.eq(userId).and(timeoutLogEntity.userId.ne(timeoutLogEntity.createdBy)))
                ).and(totalBuilder)).leftJoin(groupEntity).on(groupEntity.issueId.eq(applyEntity.id)).orderBy(applyEntity.status.asc(), applyEntity.lastUpdatedOn.desc());

        long total = tupleJpaQuery.fetchCount();
        // ????????????
        int page = request.getPage() == null || request.getPage() < 0 ? 0 : request.getPage();
        Integer size = request.getSize();
        if (size == null) {
            size = 20;
        } else if (page == 0 && size == -1) {
            size = (total == 0 ? 20 : (int) total);
        } else if (size < 0) {
            size = 20;
        }
        Pageable pageable = PageRequest.of(page, size);
        List<Tuple> fetch = tupleJpaQuery.offset(pageable.getOffset()).limit(pageable.getPageSize()).fetch();
        if (CollectionUtils.isEmpty(fetch)) {
            return new org.springframework.data.domain.PageImpl<>(new ArrayList<>(), pageable, total);
        }
        List<Tuple> filterDeleteTuple = fetch;
        Set<Integer> deleteSet = redisCache.getCacheSet(RedisKeyConstants.IGNORE_ISSUE + userId);
        boolean flag = false;
        if (!deleteSet.isEmpty()) {
            filterDeleteTuple = fetch.stream().filter(issue -> !deleteSet.contains(issue.get(applyEntity.id))).collect(Collectors.toList());
            flag = true;
        }
        if (flag && CollectionUtils.isEmpty(filterDeleteTuple)) {
            return new org.springframework.data.domain.PageImpl<>(new ArrayList<>(), pageable, total);
        }
        // ????????????id??????
        Set<Integer> relatedUserIds = new HashSet<>();
        filterDeleteTuple.forEach(apply -> {
            relatedUserIds.add(apply.get(applyEntity.createdBy));
            relatedUserIds.add(apply.get(applyEntity.currentSolverId));
        });

        // ????????????????????????
        Map<Integer, PassportUserInfoDTO> userInfoMap = passportFeignManager.getUserInfoMapFromRedis(relatedUserIds);
        // ????????????
        List<LightningMyAcceptanceVO> listLeaderOverTime = new ArrayList<>();
        for (Tuple tuple : filterDeleteTuple) {
            LightningMyAcceptanceVO every = new LightningMyAcceptanceVO();
            Integer createdBy = tuple.get(applyEntity.createdBy);
            Integer currentSolverId = tuple.get(applyEntity.currentSolverId);
            PassportUserInfoDTO createdByInfoDTO = userInfoMap.get(createdBy);
            PassportUserInfoDTO currentSolverInfoDTO = userInfoMap.get(currentSolverId);
            every.setIssueId(tuple.get(applyEntity.id));
            every.setCreatedBy(createdBy);
            every.setCreatorName(createdByInfoDTO.getNickname());
            every.setHeadUrl(createdByInfoDTO.getAvatar());
            every.setCurrentSolverId(currentSolverId);
            every.setCurrentSolverName(currentSolverInfoDTO.getNickname());
            every.setDescription(tuple.get(applyEntity.description));
            every.setGroupId(tuple.get(groupEntity.groupId));
            every.setGroupName(tuple.get(groupEntity.groupName));
            every.setStatus(tuple.get(applyEntity.status));
            every.setAutoConfirm(tuple.get(applyEntity.autoConfirm));
            every.setLastUpdatedOn(tuple.get(applyEntity.lastUpdatedOn));
            listLeaderOverTime.add(every);
        }
        return new org.springframework.data.domain.PageImpl<>(listLeaderOverTime, pageable, total);
    }

    @Override
    public void addUser(Integer issueId, Integer addUserId) {
        LightningIssueApplyEntity issue = issueApplyRepository.findById(issueId).orElseThrow(() -> new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????ID?????????"));
        Integer status = issue.getStatus();
        if (LightningApplyStatus.RESOLVED.getCode().equals(status) || LightningApplyStatus.REVOKED.getCode().equals(status)) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????????????????????????????");
        }
        // ?????????????????????id
        Integer userId = UserHelper.getUserId();
        // ?????????id
        LightningIssueGroupEntity groupInfo = lightningIssueGroupRepository.findByIssueId(issueId);
        if (groupInfo == null) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "????????????????????????????????????");
        }
        // ????????????
        LightningIssueRelevantUserEntity userRecord = relevantUserRepository.findByIssueIdAndUserId(issueId, addUserId);
        if (userRecord != null) {
            return;
        }
        // ????????????
        Long groupId = Long.valueOf(groupInfo.getGroupId());
        chatWebSocketService.addUserToGroup(issueId, groupId, userId, addUserId);
        // ??????
        LightningIssueRelevantUserEntity relevantUser = addIssueRelevantUser(issueId, addUserId, userId);
        relevantUserRepository.save(relevantUser);
        // ?????????
        LightningIssueLogEntity logEntity = lightningIssueLogManager.saveIssueLog(issueId, LightningIssueLogActionEnum.LEADER_ADD.getCode(), addUserId);
        logRepository.save(logEntity);
        // ?????????
        ServerResponse serverResponse = questNoticeService.sendNoticeByEventAndId(issue.getInstanceId(), InstanceEvent.TASK_CREATE.getCode(), Collections.singleton(addUserId));
        if (serverResponse.getCode() != ResponseCode.SUCCESS.getCode()) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????????????????");
        }
    }

    @Override
    public void unreadMessageNotice(LightningUnreadMessageDTO unreadMessage) {
        String groupId = unreadMessage.getGroupId().toString();
        LightningIssueGroupEntity groupInfo = lightningIssueGroupRepository.findByGroupId(groupId);
        if (groupInfo == null) {
            log.error("| - > [ ?????????????????? ] ???????????????????????????????????????");
            return;
        }
        LightningIssueApplyEntity issueInfo = issueApplyRepository.findByGroupId(groupId);
        if (issueInfo == null) {
            log.error("| - > [ ?????????????????? ] ??????????????????????????????????????????????????????");
            return;
        }
        Integer fromUserId = unreadMessage.getFromUser();
        Integer currentUserId = issueInfo.getCurrentSolverId();
        Map<Integer, PassportUserInfoDTO> userInfoMap = passportFeignManager.getUserInfoMapFromRedis(Arrays.asList(fromUserId, currentUserId));
        PassportUserInfoDTO fromUserInfo = userInfoMap.get(fromUserId);
        if (fromUserInfo != null) {
            unreadMessage.setFromUserName(fromUserInfo.getNickname());
        }
        PassportUserInfoDTO currentUserInfo = userInfoMap.get(currentUserId);
        if (currentUserInfo != null) {
            unreadMessage.setCurrentUserName(currentUserInfo.getNickname());
        }
        // ??????groupId??????issueId
        unreadMessage.setIssueInfo(issueInfo);
        unreadMessage.setGroupName(groupInfo.getGroupName());
        // ??????
        questNoticeService.sendUnreadMessageNotice(unreadMessage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServerResponse deleteIssue(Integer issueId) {
        List<Integer> status = new ArrayList<>();
        status.add(LightningApplyStatus.RESOLVED.getCode());
        status.add(LightningApplyStatus.REVOKED.getCode());
        issueApplyRepository.findFirstByIdAndAutoConfirmAndStatusIn(issueId, YesOrNoEnum.NO.getCode(), status).orElseThrow(() -> new VerificationFailedException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "???????????????????????????????????????????????????????????????"));
        Integer userId = UserHelper.getUserId();
        String redisKey = RedisKeyConstants.IGNORE_ISSUE + userId;
        Boolean result = redisTemplate.hasKey(redisKey);
        SetOperations<String, Object> stringObjectSetOperations = redisTemplate.opsForSet();
        if (result == null || !result) {
            loadUserIgnoreIssue(userId);
        }
        Boolean exists = stringObjectSetOperations.isMember(redisKey, issueId);
        if (exists != null && !exists) {
            LightningUserIgnoreIssueEntity userIgnoreIssueEntity = addUserIgnoreIssue(issueId, userId, userId);
            ignoreIssueRepository.save(userIgnoreIssueEntity);
            stringObjectSetOperations.add(redisKey, issueId);
        }
        return ServerResponse.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void userLeaveOffice(String userId) throws Exception {
        // ??????????????????????????????
        ServerResponse<PassportUserInfoDTO> userInfoResponse = passportFeignClient.getIdByUserId(userId);
        PassportUserInfoDTO userInfoDTO = userInfoResponse.getData();
        if (userInfoResponse.getCode() != ResponseCode.SUCCESS.getCode() || userInfoDTO == null) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????????????????????????????");
        }
        Integer userInfoId = userInfoDTO.getId();
        // ??????????????????????????????????????????
        List<PassportUserInfoDTO> superiorLeader = passportFeignManager.getSuperiorLeader(userInfoId);
        if (superiorLeader == null || superiorLeader.isEmpty()) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "????????????????????????????????????");
        }
        PassportUserInfoDTO leaderInfoDTO = superiorLeader.get(0);
        Integer leaderId = leaderInfoDTO.getId();
        QLightningIssueApplyEntity applyEntity = QLightningIssueApplyEntity.lightningIssueApplyEntity;
        // ??????????????????????????????????????????   ?????????????????????????????????????????????????????????     ????????????????????????????????????????????????
        List<Integer> issueIds = queryFactory.select(applyEntity.id).from(applyEntity).where(applyEntity.currentSolverId.eq(userInfoId).and(applyEntity.status.notIn(LightningApplyStatus.RESOLVED.getCode(), LightningApplyStatus.REVOKED.getCode(), LightningApplyStatus.TO_BE_CONFIRMED.getCode()))).fetch();
        // ?????????????????????
        for (Integer issueId : issueIds) {
            // ???????????????????????????????????????
            LightningIssueApplyEntity issueApplyEntity = issueApplyRepository.findById(issueId).orElseThrow(() -> new LogicException("????????????Id?????????"));
            // ????????????????????????
            TaskEntity currentTask = workflowTaskService.getCurrentTaskByDefinitionKeyAndBusinessKey(lightningKey, String.valueOf(issueId));
            if (currentTask == null) {
                throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????????????????????????????????????????????????????????????????Id??????" + issueId);
            }
            String taskId = currentTask.getId();
            transferIssue(issueApplyEntity, userInfoId, issueId, leaderId, taskId, TransferTypeEnum.LEAVE_TRANSFER.getCode(), null);
        }
        // ????????????
        lightningIssueManager.addIssueId(issueIds, leaderId);
    }

    @Override
    public Page<LightningMyAcceptanceVO> getIssueItemByGroupId(QueryByGroupReq req) {
        List<String> groupIds = req.getGroupIds();
        Integer userId = UserHelper.getUserId();
        if (userId == null || userId == -1) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????");
        }

        // query dsl
        QLightningIssueApplyEntity qApplyEntity = QLightningIssueApplyEntity.lightningIssueApplyEntity;
        QLightningIssueGroupEntity qGroupEntity = QLightningIssueGroupEntity.lightningIssueGroupEntity;

        // ????????????
        Integer page = req.getPage() == null || req.getPage() < 0 ? 0 : req.getPage();
        Integer size = req.getSize() == null ? -1 : req.getSize();
        Integer selectSize = size <= 0 ? 20 : size;
        boolean isSelectAll = page == 0 && size == -1;
        if (isSelectAll) {
            selectSize = 10000;
        }

        // ?????????
        QueryResults<Tuple> queryResults = queryFactory.selectDistinct(qApplyEntity.id, qGroupEntity.groupId, qGroupEntity.groupName, qApplyEntity.description, qApplyEntity.createdBy, qApplyEntity.currentSolverId, qApplyEntity.status, qApplyEntity.autoConfirm, qApplyEntity.lastUpdatedOn)
                .from(qApplyEntity).leftJoin(qGroupEntity).on(qApplyEntity.id.eq(qGroupEntity.issueId))
                .where(qGroupEntity.groupId.in(groupIds))
                .offset(page * selectSize).limit(selectSize)
                .fetchResults();

        Long total = queryResults.getTotal();
        if (total == 0) {
            return new org.springframework.data.domain.PageImpl<>(new ArrayList<>(), PageRequest.of(0, 20), total);
        }
        List<Tuple> issueList = queryResults.getResults();
        // ????????????id??????
        Set<Integer> relatedUserIds = new HashSet<>();
        issueList.forEach(issue -> {
            relatedUserIds.add(issue.get(qApplyEntity.createdBy));
            relatedUserIds.add(issue.get(qApplyEntity.currentSolverId));
        });
        Map<Integer, PassportUserInfoDTO> userInfoMap = passportFeignManager.getUserInfoMapFromRedis(relatedUserIds);
        // ???????????????
        Set<Integer> deleteSet = redisCache.getCacheSet(RedisKeyConstants.IGNORE_ISSUE + userId);
        List<LightningMyAcceptanceVO> myAcceptanceList = new ArrayList<>();
        boolean empty = deleteSet.isEmpty();
        // ?????????
        for (Tuple apply : issueList) {
            Integer issueId = apply.get(qApplyEntity.id);
            if (!empty && deleteSet.contains(issueId)) {
                total--;
                continue;
            }
            LightningMyAcceptanceVO lightningMyAcceptanceVO = new LightningMyAcceptanceVO();
            lightningMyAcceptanceVO.setIssueId(issueId);
            lightningMyAcceptanceVO.setGroupId(apply.get(qGroupEntity.groupId));
            lightningMyAcceptanceVO.setGroupName(apply.get(qGroupEntity.groupName));
            lightningMyAcceptanceVO.setDescription(apply.get(qApplyEntity.description));
            Integer status = apply.get(qApplyEntity.status);
            lightningMyAcceptanceVO.setStatus(status);
            lightningMyAcceptanceVO.setAutoConfirm(apply.get(qApplyEntity.autoConfirm));
            lightningMyAcceptanceVO.setLastUpdatedOn(apply.get(qApplyEntity.lastUpdatedOn));
            Integer creatorId = apply.get(qApplyEntity.createdBy);
            lightningMyAcceptanceVO.setCreatedBy(creatorId);
            PassportUserInfoDTO creatorInfo = userInfoMap.get(creatorId);
            if (creatorInfo != null) {
                lightningMyAcceptanceVO.setCreatorName(creatorInfo.getNickname());
                lightningMyAcceptanceVO.setHeadUrl(creatorInfo.getAvatar());
            }
            Integer solverId = apply.get(qApplyEntity.currentSolverId);
            lightningMyAcceptanceVO.setCurrentSolverId(solverId);
            PassportUserInfoDTO solverInfo = userInfoMap.get(solverId);
            if (solverInfo != null) {
                lightningMyAcceptanceVO.setCurrentSolverName(solverInfo.getNickname());
            }
            myAcceptanceList.add(lightningMyAcceptanceVO);
        }
        Pageable pageable = PageRequest.of(page, selectSize);
        if (isSelectAll) {
            pageable = PageRequest.of(page, total.intValue());
        }
        return new org.springframework.data.domain.PageImpl<>(myAcceptanceList, pageable, total);
    }

    @Override
    public List<LightningIssueRelevantUserVO> getRelevantUserByIssueId(Integer issueId) {
        List<LightningIssueRelevantUserEntity> allByIssueId = relevantUserRepository.findAllByIssueId(issueId);
        if (CollectionUtils.isEmpty(allByIssueId)) {
            return new ArrayList<>();
        }
        // ??????????????????
        List<LightningIssueRelevantUserVO> relevantUserVos = new ArrayList<>();
        Set<Integer> userId = new HashSet<>();
        for (LightningIssueRelevantUserEntity everyUser : allByIssueId) {
            Integer everyUserId = everyUser.getUserId();
            LightningIssueRelevantUserVO relevantUserVO = new LightningIssueRelevantUserVO();
            userId.add(everyUserId);
            relevantUserVO.setUserId(everyUserId);
            relevantUserVos.add(relevantUserVO);
        }
        // ????????????????????????
        Map<Integer, PassportUserInfoDTO> userMap = passportFeignManager.getUserInfoMapFromRedis(userId);
        // ??????????????????-????????????
        Map<Integer, UserGroupSimpleDTO> userGroupFromCache = userManager.searchUserGroupFromCache(userId);
        relevantUserVos.forEach(relevantUser -> {
                    Integer relevantUserUserId = relevantUser.getUserId();
                    PassportUserInfoDTO user = userMap.getOrDefault(relevantUserUserId, null);
                    if (Objects.nonNull(user)) {
                        relevantUser.setNickName(user.getNickname());
                        relevantUser.setAvatar(user.getAvatar());
                    }
                    UserGroupSimpleDTO userGroup = userGroupFromCache.getOrDefault(relevantUserUserId, null);
                    if (Objects.nonNull(userGroup)) {
                        relevantUser.setGroupDesc(userGroup.getGroups().get(0).getGroupDecs());
                    }
                }
        );


        return relevantUserVos;
    }

    /**
     * ???????????????????????????id???redis
     *
     * @param userId ??????id
     */
    private void loadUserIgnoreIssue(Integer userId) {
        QLightningUserIgnoreIssueEntity ignoreIssueEntity = QLightningUserIgnoreIssueEntity.lightningUserIgnoreIssueEntity;
        List<Integer> issueIds = queryFactory.select(ignoreIssueEntity.issueId).from(ignoreIssueEntity).where(ignoreIssueEntity.userId.eq(userId)).fetch();
        Set<Integer> issues = new HashSet<>(issueIds);
        String redisKey = RedisKeyConstants.IGNORE_ISSUE + userId;
        redisTemplate.delete(redisKey);
        if (!issues.isEmpty()) {
            redisTemplate.opsForSet().add(redisKey, issues.toArray(new Integer[0]));
            redisTemplate.expire(redisKey, 30, TimeUnit.DAYS);
        }
    }

    /**
     * ??????????????????????????????????????????????????????????????????
     *
     * @param issueId   ??????id
     * @param userId    ??????id
     * @param createdBy ?????????id
     * @return LightningUserIgnoreIssueEntity??????
     */
    private LightningUserIgnoreIssueEntity addUserIgnoreIssue(Integer issueId, Integer userId, Integer createdBy) {
        LightningUserIgnoreIssueEntity userIgnoreIssueEntity = new LightningUserIgnoreIssueEntity();
        userIgnoreIssueEntity.setIssueId(issueId);
        userIgnoreIssueEntity.setUserId(userId);
        userIgnoreIssueEntity.setCreatedBy(createdBy);
        userIgnoreIssueEntity.setCreatedOn(new Date());
        userIgnoreIssueEntity.setLastUpdatedBy(createdBy);
        userIgnoreIssueEntity.setLastUpdatedOn(new Date());
        return userIgnoreIssueEntity;
    }

    /**
     * ??????????????????????????????????????????????????????
     *
     * @param issueId   ??????id
     * @param userId    ??????id
     * @param createdBy ?????????id
     * @return ??????LightningIssueRelevantUselrEntity??????
     */
    private LightningIssueRelevantUserEntity addIssueRelevantUser(Integer issueId, Integer userId, Integer createdBy) {
        LightningIssueRelevantUserEntity relevantUserEntity = new LightningIssueRelevantUserEntity();
        relevantUserEntity.setIssueId(issueId);
        relevantUserEntity.setUserId(userId);
        relevantUserEntity.setCreatedBy(createdBy);
        relevantUserEntity.setCreatedOn(new Date());
        relevantUserEntity.setLastUpdatedBy(createdBy);
        relevantUserEntity.setLastUpdatedOn(new Date());
        return relevantUserEntity;
    }

    @Override
    public List<LightningIssueCategoryVO> selectCategory(boolean includeAvatar) {

        // ????????????
        List<LightningIssueCategoryVO> result = new ArrayList<>();

        // ????????????????????????
        QLightningIssueCategoryEntity qCategory = QLightningIssueCategoryEntity.lightningIssueCategoryEntity;
        QDutyRuleEntity qRule = QDutyRuleEntity.dutyRuleEntity;
        List<Tuple> categoryList = queryFactory.select(qCategory.id, qCategory.name, qCategory.userId, qCategory.userName, qCategory.ruleId, qRule.type, qCategory.sort, qCategory.status)
                .from(qCategory).leftJoin(qRule).on(qCategory.ruleId.eq(qRule.id))
                .where(qCategory.status.eq(YesOrNoEnum.YES.getCode())).fetch();
        if (CollectionUtils.isEmpty(categoryList)) {
            return result;
        }

        // ?????????????????????
        List<Integer> dutyByDayCategoryIds = categoryList.stream()
                .filter(d -> DutyRuleTypeEnum.DUTY_BY_DAY.getCode().equals(d.get(qRule.type)))
                .map(t -> t.get(qCategory.id)).collect(Collectors.toList());

        // ???????????? - ?????? - ??????????????? ??? k-??????id v-???????????? ?????????redis???
        Map<Integer, Integer> dutyMap = lightningIssueConfigManager.queryTodayDutyByDayUser(dutyByDayCategoryIds);

        // ?????? ?????????????????????
        Set<Integer> userIds = new HashSet<>();
        categoryList.forEach(c -> {
            LightningIssueCategoryVO categoryVO = new LightningIssueCategoryVO();
            Integer categoryId = c.get(qCategory.id);
            categoryVO.setId(categoryId);
            categoryVO.setName(c.get(qCategory.name));
            categoryVO.setUserId(c.get(qCategory.userId));
            categoryVO.setUserName(c.get(qCategory.userName));
            Integer type = c.get(qRule.type);
            categoryVO.setType(type);
            if (DutyRuleTypeEnum.DUTY_BY_DAY.getCode().equals(type) && dutyMap.containsKey(categoryId)) {
                categoryVO.setUserId(dutyMap.get(categoryId));
            }
            Integer userId = categoryVO.getUserId();
            // 0 ??? ????????????
            if (userId != null && userId != 0) {
                userIds.add(userId);
            }
            result.add(categoryVO);
        });

        if (includeAvatar) {
            List<PassportUserInfoDTO> users = passportFeignManager.getUserInfoListFromRedis(userIds);
            result.forEach(vo -> {
                users.stream().filter(user -> user.getId().equals(vo.getUserId())).findFirst().ifPresent(user -> {
                    vo.setUserName(user.getNickname());
                    vo.setAvatar(user.getAvatar());
                });
            });
        }
        return result;
    }

    @Override
    public void clearRedisCache(List<Integer> userIds) {
        if (CollectionUtils.isNotEmpty(userIds)) {
            lightningIssueManager.clearRedisCache(userIds);
        }
    }

    @Override
    public Set<Integer> queryRedisMyAcceptCache(Integer userId) {
        return lightningIssueManager.queryRedisMyAcceptCache(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeAutoConfirmIssue() {

        // ??????
        List<Integer> needCloseIdList = new ArrayList<>();
        List<LightningIssueLogEntity> autoCloseLogList = new ArrayList<>(10);

        // ?????????????????????????????????
        QLightningIssueApplyEntity qApply = QLightningIssueApplyEntity.lightningIssueApplyEntity;
        QLightningIssueGroupEntity qGroup = QLightningIssueGroupEntity.lightningIssueGroupEntity;
        List<Tuple> autoConfirmIssueList = queryFactory.selectDistinct(qApply.id, qApply.lastUpdatedOn, qGroup.groupId)
                .from(qApply).leftJoin(qGroup).on(qApply.id.eq(qGroup.issueId)).fetch();
        if (CollectionUtils.isEmpty(autoConfirmIssueList)) {
            return;
        }

        // ????????????????????????
        autoConfirmIssueList.forEach(issue -> {
            LocalDateTime lastUpdatedOn = TimeUtil.date2LocalDateTime(Objects.requireNonNull(issue.get(qApply.lastUpdatedOn)));
            if (lastUpdatedOn.plusMonths(1L).isBefore(LocalDateTime.now())) {
                // ??????id
                Integer issueId = issue.get(qApply.id);
                try {
                    // ?????????????????????
                    String groupId = issue.get(qGroup.groupId);
                    if (StringUtils.isBlank(groupId)) {
                        log.error("??????id???????????? - ??????id: {}", issueId);
                        return;
                    }
                    chatWebSocketService.closeGroup(Long.valueOf(groupId), issueId);
                    // ?????????????????????id
                    needCloseIdList.add(issueId);
                    // ??????????????????
                    autoCloseLogList.add(lightningIssueLogManager.saveIssueLog(issueId, LightningIssueLogActionEnum.AUTO_CLOSE.getCode(), -2));
                } catch (Exception e) {
                    log.error("?????????????????? - ??????id :???{}  -  e : {} ", issueId, e);
                }
            }
        });

        // ?????? ???????????????????????????????????????????????????
        issueApplyRepository.updateApplyAutoConfirm(needCloseIdList, YesOrNoEnum.NO.getCode(), new Date());
        logRepository.saveAll(autoCloseLogList);
    }
}
