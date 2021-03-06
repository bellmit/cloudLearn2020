package com.ruigu.rbox.workflow.service.hr;


import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.workflow.exceptions.VerificationFailedException;
import com.ruigu.rbox.workflow.model.dto.BaseTaskInfoDTO;
import com.ruigu.rbox.workflow.model.entity.*;
import com.ruigu.rbox.workflow.model.enums.InstanceEvent;
import com.ruigu.rbox.workflow.model.enums.NoticeConfigState;
import com.ruigu.rbox.workflow.model.vo.MessageInfoVO;
import com.ruigu.rbox.workflow.model.vo.WorkflowInstanceVO;
import com.ruigu.rbox.workflow.repository.TaskRepository;
import com.ruigu.rbox.workflow.repository.WorkflowDefinitionRepository;
import com.ruigu.rbox.workflow.service.*;
import com.ruigu.rbox.workflow.strategy.context.SendNoticeContext;
import com.ruigu.rbox.workflow.supports.ConvertClassUtil;
import com.ruigu.rbox.workflow.supports.NoticeContentUtil;
import lombok.extern.slf4j.Slf4j;
import org.activiti.engine.delegate.DelegateTask;
import org.activiti.engine.delegate.TaskListener;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * @author alan.zhao
 */
@Slf4j
@Service
public class LeaveApproveTaskDeleteListenerImpl implements TaskListener {

    private static final long serialVersionUID = -2353362566754781715L;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private NoticeConfigService noticeConfigService;

    @Autowired
    private WorkflowDefinitionRepository workflowDefinitionRepository;

    @Autowired
    private WorkflowInstanceService instanceService;

    @Autowired
    private NoticeContentUtil noticeContentUtil;

    @Autowired
    private WorkflowTaskService taskService;

    @Autowired
    private SendNoticeContext sendNoticeContext;

    @Autowired
    private UserGroupService userGroupService;

    @Autowired
    private TaskRepository taskRepository;

    @Override
    public void notify(DelegateTask delegateTask) {
        log.debug("============================== ???????????????????????? ==================================");
        OperationLogEntity logEntity = new OperationLogEntity();
        try {
            Map<String, Object> variables = delegateTask.getVariables();
            NodeEntity node = taskService.queryNode(delegateTask);
            if (null == node) {
                log.error("?????????????????????????????????.TaskDefinitionKey:{},ProcessDefinitionId:{}",
                        delegateTask.getTaskDefinitionKey(),
                        delegateTask.getProcessDefinitionId());
                return;
            }
            // ??????????????????
            WorkflowDefinitionEntity definition = workflowDefinitionRepository.findById(node.getModelId())
                    .orElseThrow(() -> new VerificationFailedException(400, "??????????????????????????????????????????????????????????????????????????????"));

            logEntity.setContent("[ " + definition.getName() + " ] [ " + node.getName() + " ] ????????????");
            logEntity.setDefinitionId(definition.getId());

            // ?????????????????????
            List<NoticeTemplateEntity> noticeTemplate = noticeConfigService
                    .getNoticeTemplate(NoticeConfigState.NODE,
                            node.getId(),
                            InstanceEvent.TASK_DELETE.getCode());
            if (CollectionUtils.isEmpty(noticeTemplate)) {
                return;
            }

            Set<Integer> targets = new HashSet<>();
            if (StringUtils.isNotBlank(node.getCandidateUsers())) {
                String[] users = node.getCandidateUsers().split(",");
                for (String user : users) {
                    targets.add(Integer.valueOf(user));
                }
            }
            if (StringUtils.isNotBlank(node.getCandidateGroups())) {
                String[] groups = node.getCandidateUsers().split(",");
                List<Integer> grouptargets = userGroupService.getUserListByGroupsInt(Arrays.asList(groups));
                targets.addAll(grouptargets);
            }
            if (CollectionUtils.isEmpty(targets)) {
                return;
            }

            TaskEntity task = taskRepository.findById(delegateTask.getId()).orElse(null);
            if (task == null) {
                return;
            }
            // ??????????????????
            WorkflowInstanceVO instance = instanceService.getInstanceById(delegateTask.getProcessInstanceId());
            if (instance == null) {
                log.error("????????????????????????????????????????????????");
                return;
            }

            // ???????????? ????????????
            BaseTaskInfoDTO baseTaskInfoDTO = ConvertClassUtil.convertToBaseTaskInfoDTO(task);
            noticeTemplate.forEach(template -> {
                // ???title ??? content??????
                MessageInfoVO message = noticeContentUtil.translateNodeTemplate(template, definition, baseTaskInfoDTO, variables);
                // ?????????????????????????????????????????????
                message.setTargets(targets);
                message.setNoticeEventType(InstanceEvent.TASK_DELETE.getCode());
                sendNoticeContext.send(template, message);
            });
        } catch (Exception e) {
            log.error("????????????????????????", e);
        } finally {
            Integer userId = UserHelper.getUserId();
            logEntity.setCreatedBy(userId);
            logEntity.setLastUpdatedBy(userId);
            logEntity.setCreatedOn(new Date());
            logEntity.setLastUpdatedOn(new Date());
            logEntity.setTaskId(delegateTask.getId());
            logEntity.setInstanceId(delegateTask.getProcessInstanceId());
            logEntity.setEvent(InstanceEvent.TASK_DELETE.getCode().toString());
            operationLogService.log(logEntity);
        }
    }
}
