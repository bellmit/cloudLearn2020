package com.ruigu.rbox.workflow.service.hr;

import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.workflow.exceptions.VerificationFailedException;
import com.ruigu.rbox.workflow.feign.handler.HrFeignHandler;
import com.ruigu.rbox.workflow.model.dto.BaseTaskInfoDTO;
import com.ruigu.rbox.workflow.model.entity.*;
import com.ruigu.rbox.workflow.model.enums.InstanceEvent;
import com.ruigu.rbox.workflow.model.enums.NoticeConfigState;
import com.ruigu.rbox.workflow.model.enums.ResponseCode;
import com.ruigu.rbox.workflow.model.enums.WorkflowStatusFlag;
import com.ruigu.rbox.workflow.model.request.LeaveReportTaskUpdateReq;
import com.ruigu.rbox.workflow.model.vo.MessageInfoVO;
import com.ruigu.rbox.workflow.model.vo.WorkflowInstanceVO;
import com.ruigu.rbox.workflow.repository.TaskRepository;
import com.ruigu.rbox.workflow.repository.WorkflowDefinitionRepository;
import com.ruigu.rbox.workflow.service.NoticeConfigService;
import com.ruigu.rbox.workflow.service.OperationLogService;
import com.ruigu.rbox.workflow.service.WorkflowInstanceService;
import com.ruigu.rbox.workflow.service.WorkflowTaskService;
import com.ruigu.rbox.workflow.strategy.context.SendNoticeContext;
import com.ruigu.rbox.workflow.supports.ConvertClassUtil;
import com.ruigu.rbox.workflow.supports.NoticeContentUtil;
import lombok.extern.slf4j.Slf4j;
import org.activiti.engine.delegate.DelegateTask;
import org.activiti.engine.delegate.TaskListener;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;

/**
 * @author liqingtian
 * @date 2019/08/21 15:33
 */
@Slf4j
@Service
public class LeaveApproveTaskCompleteListenerImpl implements TaskListener {

    private static final long serialVersionUID = 1L;

    @Resource
    private OperationLogService operationLogService;

    @Resource
    private NoticeConfigService noticeConfigService;

    @Resource
    private WorkflowDefinitionRepository workflowDefinitionRepository;

    @Resource
    private WorkflowInstanceService instanceService;

    @Resource
    private WorkflowTaskService taskService;

    @Resource
    private TaskRepository taskRepository;

    @Resource
    private SendNoticeContext sendNoticeContext;

    @Resource
    private NoticeContentUtil noticeContentUtil;

    @Resource
    private HrFeignHandler hrFeignHandler;

    @Override
    public void notify(DelegateTask delegateTask) {
        log.debug("============================== ???????????????????????? ==================================");
        OperationLogEntity logEntity = new OperationLogEntity();
        Map<String, Object> variables = delegateTask.getVariables();
        Integer taskStatus = null;
        if (variables.containsKey(WorkflowStatusFlag.TASK_STATUS.getName())) {
            taskStatus = (Integer) variables.get(WorkflowStatusFlag.TASK_STATUS.getName());
        }
        try {
            NodeEntity node = taskService.queryNode(delegateTask);
            if (null == node) {
                log.error("?????????????????????????????????.TaskDefinitionKey:{},ProcessDefinitionId:{}",
                        delegateTask.getTaskDefinitionKey(),
                        delegateTask.getProcessDefinitionId());
                return;
            }
            // ??????????????????
            WorkflowDefinitionEntity definition = workflowDefinitionRepository.findById(node.getModelId())
                    .orElseThrow(() -> new VerificationFailedException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????????????????????????????????????????????????????????????????"));
            logEntity.setContent("[ " + definition.getName() + " ] [ " + node.getName() + " ] ????????????");
            logEntity.setDefinitionId(definition.getId());

            // ??????????????????
            TaskEntity task = taskRepository.findById(delegateTask.getId())
                    .orElseThrow(() -> new VerificationFailedException(ResponseCode.INTERNAL_ERROR.getCode(),
                            "????????????????????????????????????????????????"));
            // ??????????????????
            WorkflowInstanceVO instance = instanceService.getInstanceById(delegateTask.getProcessInstanceId());
            if (instance == null) {
                log.error("????????????????????????????????????????????????");
                return;
            }

            //????????????????????????
            Integer applyId = (Integer) variables.get("applyId");
            Integer userId = UserHelper.getUserId();
            int approved = Integer.parseInt(variables.get("approved").toString());
            int status;
            if (approved == 1) {
                status = 3;
            } else {
                status = 4;
            }

            LeaveReportTaskUpdateReq req = new LeaveReportTaskUpdateReq();
            req.setTaskId(task.getId());
            req.setApplyId(applyId);
            req.setUserId(userId);
            req.setStatus(status);

            hrFeignHandler.updateApplyTask(req);

            // ?????????????????????
            List<NoticeTemplateEntity> noticeTemplate = noticeConfigService
                    .getNoticeTemplate(NoticeConfigState.NODE,
                            node.getId(),
                            InstanceEvent.TASK_COMPLETE.getCode());
            if (CollectionUtils.isEmpty(noticeTemplate)) {
                return;
            }

            // ?????????
            Set<Integer> targets = new HashSet<>();
            targets.add(instance.getCreatedBy().intValue());
            if (instance.getOwnerId() != null) {
                targets.add(instance.getOwnerId().intValue());
            }
            if (CollectionUtils.isEmpty(targets)) {
                return;
            }

            // ???????????? ????????????
            BaseTaskInfoDTO baseTaskInfoDTO = ConvertClassUtil.convertToBaseTaskInfoDTO(task);
            noticeTemplate.forEach(template -> {
                MessageInfoVO message = noticeContentUtil.translateNodeTemplate(template, definition, baseTaskInfoDTO, variables);
                message.setTargets(targets);
                message.setNoticeEventType(InstanceEvent.TASK_COMPLETE.getCode());
                // ?????????????????????????????????????????????
                sendNoticeContext.send(template, message);
            });

        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            // ????????????
            Integer userId = UserHelper.getUserId();
            logEntity.setEvent(InstanceEvent.TASK_COMPLETE.getCode().toString());
            logEntity.setShowStatus(taskStatus);
            logEntity.setCreatedBy(userId);
            logEntity.setLastUpdatedBy(userId);
            logEntity.setCreatedOn(new Date());
            logEntity.setLastUpdatedOn(new Date());
            logEntity.setTaskId(delegateTask.getId());
            logEntity.setInstanceId(delegateTask.getProcessInstanceId());
            operationLogService.log(logEntity);
        }
    }
}
