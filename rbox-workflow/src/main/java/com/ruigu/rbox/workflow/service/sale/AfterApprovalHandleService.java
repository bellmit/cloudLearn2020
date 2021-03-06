package com.ruigu.rbox.workflow.service.sale;

import com.ruigu.rbox.cloud.kanai.model.YesOrNoEnum;
import com.ruigu.rbox.workflow.exceptions.GlobalRuntimeException;
import com.ruigu.rbox.workflow.manager.PassportFeignManager;
import com.ruigu.rbox.workflow.manager.SpecialAfterSaleLogManager;
import com.ruigu.rbox.workflow.model.dto.PassportUserInfoDTO;
import com.ruigu.rbox.workflow.model.entity.SpecialAfterSaleApplyEntity;
import com.ruigu.rbox.workflow.model.entity.SpecialAfterSaleCcEntity;
import com.ruigu.rbox.workflow.model.entity.SpecialAfterSaleReviewEntity;
import com.ruigu.rbox.workflow.model.enums.*;
import com.ruigu.rbox.workflow.repository.SpecialAfterSaleApplyApproverRepository;
import com.ruigu.rbox.workflow.repository.SpecialAfterSaleApplyRepository;
import com.ruigu.rbox.workflow.repository.SpecialAfterSaleCcRepository;
import com.ruigu.rbox.workflow.repository.SpecialAfterSaleReviewRepository;
import com.ruigu.rbox.workflow.service.QuestNoticeService;
import com.ruigu.rbox.workflow.service.SpecialAfterSaleQuotaService;
import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.JavaDelegate;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author liqingtian
 * @date 2020/08/11 12:55
 */
@Service
public class AfterApprovalHandleService implements JavaDelegate {

    @Resource
    private SpecialAfterSaleApplyRepository specialAfterSaleApplyRepository;
    @Resource
    private SpecialAfterSaleApplyApproverRepository specialAfterSaleApplyApproverRepository;
    @Resource
    private SpecialAfterSaleLogManager specialAfterSaleLogManager;
    @Resource
    private SpecialAfterSaleQuotaService specialAfterSaleQuotaService;
    @Resource
    private SpecialAfterSaleReviewRepository specialAfterSaleReviewRepository;
    @Resource
    private SpecialAfterSaleCcRepository specialAfterSaleCcRepository;
    @Resource
    private QuestNoticeService questNoticeService;
    @Resource
    private PassportFeignManager passportFeignManager;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void execute(DelegateExecution delegateExecution) {

        Long applyId = delegateExecution.getVariable(SpecialAfterSaleUseVariableEnum.APPLY_ID.getCode(), Long.class);
        SpecialAfterSaleApplyEntity apply = specialAfterSaleApplyRepository.findById(applyId)
                .orElseThrow(() -> new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "??????????????????"));
        if (Objects.nonNull(apply.getApprovalTime()) || Objects.nonNull(apply.getApprovalUserId())) {
            return;
        }
        // ??????????????????
        boolean isPass = false;
        Integer taskStatus = delegateExecution.getVariable(WorkflowStatusFlag.TASK_STATUS.getName(), Integer.class);
        if (taskStatus == null || taskStatus == TaskState.REJECT.getState()) {
            apply.setStatus(YesOrNoOrDefaultEnum.NO.getCode());
        } else if (isPass = taskStatus == TaskState.APPROVAL.getState()) {
            apply.setStatus(YesOrNoOrDefaultEnum.YES.getCode());
        }
        specialAfterSaleApplyRepository.save(apply);
        // ????????????????????????
        specialAfterSaleQuotaService.markSuccessOrFail(applyId, apply.getStatus());
        // ?????????
        final int systemId = -1;
        specialAfterSaleLogManager.createActionLog(applyId, SpecialAfterSaleLogActionEnum.END.getValue(), null, SpecialAfterSaleLogActionEnum.END.getCode(), null, YesOrNoEnum.YES.getCode(), systemId);
        // ?????????????????????
        specialAfterSaleApplyApproverRepository.deleteByApplyId(applyId);
        // ??????????????????(????????????????????????)
        if (isPass) {
            SpecialAfterSaleReviewEntity review = specialAfterSaleReviewRepository.findById(apply.getConfigId()).orElse(null);
            if (Objects.nonNull(review)) {
                String ccIds = review.getCcIds();
                if (StringUtils.isNotBlank(ccIds)) {
                    String[] split = StringUtils.split(ccIds, Symbol.COMMA.getValue());
                    if (Objects.nonNull(split)) {
                        List<Integer> ccList = Arrays.stream(split).map(Integer::valueOf).collect(Collectors.toList());
                        specialAfterSaleCcRepository.saveAll(buildCcRecord(applyId, ccList));
                        // ????????????
                        questNoticeService.sendTextCardMultipleApp(EnvelopeChannelEnum.WORKFLOW, getCcNoticeBody(delegateExecution), ccList);
                    }
                }
            }
        }
    }

    private List<SpecialAfterSaleCcEntity> buildCcRecord(Long applyId, List<Integer> userIds) {
        List<SpecialAfterSaleCcEntity> result = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        userIds.forEach(id -> {
            SpecialAfterSaleCcEntity r = new SpecialAfterSaleCcEntity();
            r.setApplyId(applyId);
            r.setUserId(id);
            r.setCreatedAt(now);
            result.add(r);
        });
        return result;
    }

    private Map<String, Object> getCcNoticeBody(DelegateExecution delegateExecution) {
        Long applyId = delegateExecution.getVariable(SpecialAfterSaleUseVariableEnum.APPLY_ID.getCode(), Long.class);
        // ?????????id
        Integer applyUserId = delegateExecution.getVariable(SpecialAfterSaleUseVariableEnum.APPLY_USER_ID.getCode(), Integer.class);
        PassportUserInfoDTO applyUserInfo = passportFeignManager.getUserInfoFromRedis(applyUserId);
        String applyUserName = applyUserInfo.getNickname();
        // ????????????
        String applyTime = delegateExecution.getVariable(SpecialAfterSaleUseVariableEnum.APPLY_TIME.getCode(), String.class);
        // ????????????
        String totalAmount = delegateExecution.getVariable(SpecialAfterSaleUseVariableEnum.APPLY_TOTAL_AMOUNT.getCode(), String.class);
        // ??????
        String title = applyUserName + "?????????????????????";
        String content = "???????????????" + totalAmount + "\n" + "???????????????" + applyTime;
        String url = "";
        Map<String, Object> body = new HashMap<>();
        body.put(NoticeParam.TITLE.getDesc(), title);
        body.put(NoticeParam.CONTENT.getDesc(), content);
        body.put(NoticeParam.URL.getDesc(), url);
        return body;
    }
}
