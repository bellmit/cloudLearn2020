package com.ruigu.rbox.workflow.service.listener;

import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.workflow.exceptions.VerificationFailedException;
import com.ruigu.rbox.workflow.model.entity.NoticeTemplateEntity;
import com.ruigu.rbox.workflow.model.entity.OperationLogEntity;
import com.ruigu.rbox.workflow.model.entity.WorkflowDefinitionEntity;
import com.ruigu.rbox.workflow.model.entity.WorkflowHistoryEntity;
import com.ruigu.rbox.workflow.model.enums.NoticeConfigState;
import com.ruigu.rbox.workflow.model.enums.InstanceEvent;
import com.ruigu.rbox.workflow.model.enums.ResponseCode;
import com.ruigu.rbox.workflow.model.vo.MessageInfoVO;
import com.ruigu.rbox.workflow.repository.WorkflowDefinitionRepository;
import com.ruigu.rbox.workflow.service.NoticeConfigService;
import com.ruigu.rbox.workflow.service.OperationLogService;
import com.ruigu.rbox.workflow.service.WorkflowInstanceService;
import com.ruigu.rbox.workflow.strategy.context.SendNoticeContext;
import com.ruigu.rbox.workflow.supports.NoticeContentUtil;
import lombok.extern.slf4j.Slf4j;
import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.ExecutionListener;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @author alan.zhao
 */
@Slf4j
@Service
public class ExecutionEndListenerImpl implements ExecutionListener {

    private static final long serialVersionUID = -8409042677977991162L;

    @Resource
    private OperationLogService operationLogService;

    @Resource
    private NoticeConfigService noticeConfigService;

    @Resource
    private WorkflowDefinitionRepository workflowDefinitionRepository;

    @Resource
    private WorkflowInstanceService instanceService;

    @Resource
    private NoticeContentUtil noticeContentUtil;

    @Resource
    private SendNoticeContext sendNoticeContext;

    @Override
    public void notify(DelegateExecution delegateExecution) {
        log.debug("============================== ???????????????????????? ==================================");
        OperationLogEntity logEntity = new OperationLogEntity();
        try {
            WorkflowHistoryEntity historyEntity = instanceService.moveToHistory(delegateExecution.getProcessInstanceId(), 0L);
            logEntity.setDefinitionId(historyEntity.getDefinitionId());
            logEntity.setContent("[ " + historyEntity.getName() + " ] ????????????");
            // ?????????????????????
            List<NoticeTemplateEntity> noticeTemplate = noticeConfigService.getNoticeTemplate(NoticeConfigState.DEFINITION, historyEntity.getDefinitionId(), InstanceEvent.INSTANCE_END.getCode());
            if (CollectionUtils.isEmpty(noticeTemplate)) {
                return;
            }
            // ??????????????????
            WorkflowDefinitionEntity definition = workflowDefinitionRepository.findById(historyEntity.getDefinitionId())
                    .orElseThrow(() -> new VerificationFailedException(ResponseCode.ERROR.getCode(), "?????????????????????????????????????????????????????????????????????????????????????????????"));
            String instanceId = delegateExecution.getProcessInstanceId();
            // ??????????????????
            Map<String, Object> variables = delegateExecution.getVariables();
            // ???????????? ????????????
            noticeTemplate.forEach(template -> {
                MessageInfoVO message = noticeContentUtil.translateDefinitionTemplate(template, definition, instanceId, variables);
                message.setTargets(Collections.singleton(historyEntity.getCreatedBy().intValue()));
                message.setNoticeEventType(InstanceEvent.INSTANCE_END.getCode());
                // ?????????????????????????????????????????????
                sendNoticeContext.send(template, message);
            });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            // ????????????
            Integer userId = UserHelper.getUserId();
            logEntity.setCreatedBy(userId);
            logEntity.setLastUpdatedBy(userId);
            logEntity.setCreatedOn(new Date());
            logEntity.setLastUpdatedOn(new Date());
            logEntity.setTaskId(String.valueOf(InstanceEvent.INSTANCE_END.getCode()));
            logEntity.setInstanceId(delegateExecution.getProcessInstanceId());
            logEntity.setEvent(InstanceEvent.INSTANCE_END.getCode().toString());
            operationLogService.log(logEntity);
        }
    }
}
