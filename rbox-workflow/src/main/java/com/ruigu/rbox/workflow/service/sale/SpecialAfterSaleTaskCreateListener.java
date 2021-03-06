package com.ruigu.rbox.workflow.service.sale;

import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.cloud.kanai.util.JsonUtil;
import com.ruigu.rbox.workflow.exceptions.GlobalRuntimeException;
import com.ruigu.rbox.workflow.feign.PassportFeignClient;
import com.ruigu.rbox.workflow.model.ServerResponse;
import com.ruigu.rbox.workflow.model.dto.BaseTaskInfoDTO;
import com.ruigu.rbox.workflow.model.dto.PassportUserInfoDTO;
import com.ruigu.rbox.workflow.model.entity.*;
import com.ruigu.rbox.workflow.model.enums.*;
import com.ruigu.rbox.workflow.model.vo.MessageInfoVO;
import com.ruigu.rbox.workflow.model.vo.WorkflowInstanceVO;
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
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author liqingtian
 * @date 2020/08/12 18:48
 */
@Slf4j
@Service
public class SpecialAfterSaleTaskCreateListener implements TaskListener {

    @Resource
    private WorkflowTaskService taskService;

    @Resource
    private WorkflowInstanceService instanceService;

    @Resource
    private WorkflowDefinitionRepository workflowDefinitionRepository;

    @Resource
    private OperationLogService operationLogService;

    @Resource
    private SendNoticeContext sendNoticeContext;

    @Resource
    private NoticeConfigService noticeConfigService;

    @Resource
    private NoticeContentUtil noticeContentUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void notify(DelegateTask delegateTask) {
        log.debug("============================== ???????????????????????? ==================================");

        // ????????????????????????
        NodeEntity node = taskService.queryNode(delegateTask);
        if (null == node) {
            log.error("?????????????????????????????????.TaskDefinitionKey:{},ProcessDefinitionId:{}",
                    delegateTask.getTaskDefinitionKey(),
                    delegateTask.getProcessDefinitionId());
            return;
        }
        // ??????????????????
        WorkflowDefinitionEntity definition = workflowDefinitionRepository.findById(node.getModelId())
                .orElse(null);
        if (definition == null) {
            log.error("??????????????????????????????????????????????????????????????????????????????");
            return;
        }

        OperationLogEntity logEntity = new OperationLogEntity();
        try {
            Map<String, Object> variables = delegateTask.getVariables();

            // ??????????????????????????????
            Integer currentNodeId = (Integer) variables.get(SpecialAfterSaleUseVariableEnum.CURRENT_NODE_ID.getCode());
            String taskApprover = (String) variables.get(SpecialAfterSaleUseVariableEnum.CURRENT_NODE_USER.getCode() + currentNodeId);
            List<Integer> taskApproverIdList = JsonUtil.parseArray(taskApprover, Integer.class);
            if (CollectionUtils.isEmpty(taskApproverIdList)) {
                log.error("????????????????????????????????????????????????????????????");
                throw new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "??????????????????????????????");
            }
            List<String> candidateUsers = taskApproverIdList.stream().map(String::valueOf).collect(Collectors.toList());
            delegateTask.addCandidateUsers(candidateUsers);

            // ??????????????????
            WorkflowInstanceVO instance = instanceService.getInstanceById(delegateTask.getProcessInstanceId());
            String instanceName = String.valueOf(variables.get(InstanceVariableParam.INSTANCE_NAME.getText()));
            if (instance == null) {
                instance = new WorkflowInstanceVO();
                instance.setHistory(false);
                instance.setName(instanceName);
                instance.setCreatedOn(new Date());
            }

            TaskEntity task = taskService.insertTask(delegateTask, node, candidateUsers, definition, instance);
            logEntity.setContent("[ " + definition.getName() + " ] [ " + task.getName() + " ] ????????????");

            // ?????????????????????
            List<NoticeTemplateEntity> noticeTemplate = noticeConfigService
                    .getNoticeTemplate(NoticeConfigState.NODE,
                            node.getId(),
                            InstanceEvent.TASK_CREATE.getCode());
            if (CollectionUtils.isEmpty(noticeTemplate)) {
                return;
            }

            // ???????????? ????????????
            BaseTaskInfoDTO baseTaskInfoDTO = ConvertClassUtil.convertToBaseTaskInfoDTO(task);
            noticeTemplate.forEach(template -> {
                MessageInfoVO message = noticeContentUtil.translateNodeTemplate(template, definition, baseTaskInfoDTO, variables);
                message.setTargets(taskApproverIdList);
                message.setNoticeEventType(InstanceEvent.TASK_CREATE.getCode());
                // ?????????????????????????????????????????????
                sendNoticeContext.send(template, message);
            });
        } catch (Exception e) {
            log.error("???????????????????????????e:{}", e);
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), e.getMessage());
        } finally {
            // ????????????????????????
            Integer userId = UserHelper.getUserId();
            logEntity.setCreatedBy(userId);
            logEntity.setLastUpdatedBy(userId);
            logEntity.setCreatedOn(new Date());
            logEntity.setLastUpdatedOn(new Date());
            logEntity.setTaskId(delegateTask.getId());
            logEntity.setInstanceId(delegateTask.getProcessInstanceId());
            logEntity.setDefinitionId(definition.getId());
            logEntity.setShowStatus(LightningApplyStatus.TO_BE_ACCEPTED.getCode());
            operationLogService.log(logEntity);
        }
    }
}
