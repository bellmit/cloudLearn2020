package com.ruigu.rbox.workflow.service.hr;

import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.cloud.kanai.util.JsonUtil;
import com.ruigu.rbox.workflow.feign.PassportFeignClient;
import com.ruigu.rbox.workflow.feign.handler.HrFeignHandler;
import com.ruigu.rbox.workflow.model.ServerResponse;
import com.ruigu.rbox.workflow.model.dto.BaseTaskInfoDTO;
import com.ruigu.rbox.workflow.model.dto.PassportUserInfoDTO;
import com.ruigu.rbox.workflow.model.entity.*;
import com.ruigu.rbox.workflow.model.enums.*;
import com.ruigu.rbox.workflow.model.request.LeaveReportTaskReq;
import com.ruigu.rbox.workflow.model.vo.MessageInfoVO;
import com.ruigu.rbox.workflow.model.vo.UserGroupLeaderVO;
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
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;

/**
 * @author alan.zhao
 */
@SuppressWarnings("ALL")
@Slf4j
@Service
public class LeaveApproveTaskCreateListenerImpl implements TaskListener {
    private static final long serialVersionUID = 1L;

    @Resource
    private WorkflowTaskService taskService;

    @Resource
    private TaskRepository taskRepository;

    @Resource
    private WorkflowInstanceService instanceService;

    @Resource
    private WorkflowDefinitionRepository workflowDefinitionRepository;

    @Resource
    private OperationLogService operationLogService;

    @Resource
    private UserGroupService userGroupService;

    @Resource
    private PassportFeignClient passportFeignClient;

    @Resource
    private SendNoticeContext sendNoticeContext;

    @Resource
    private NoticeConfigService noticeConfigService;

    @Resource
    private NoticeContentUtil noticeContentUtil;

    @Resource
    private HrFeignHandler hrFeignHandler;

    @Override
    public void notify(DelegateTask delegateTask) {
        log.debug("============================== ???????????????????????? ==================================");
        OperationLogEntity logEntity = new OperationLogEntity();
        try {
            Map<String, Object> variables = delegateTask.getVariables();
            String instanceName = String.valueOf(variables.get(InstanceVariableParam.INSTANCE_NAME.getText()));
            Long instanceCreatorId = (Long) (variables.get(InstanceVariableParam.INSTANCE_CREATOR_ID.getText()));
            String businessKey = String.valueOf(variables.get(InstanceVariableParam.BUSINESS_KEY.getText()));
            Integer applyId = (Integer) delegateTask.getVariable("applyId");

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
            // ??????????????????
            WorkflowInstanceVO instance = instanceService.getInstanceById(delegateTask.getProcessInstanceId());
            if (instance == null) {
                instance = new WorkflowInstanceVO();
                instance.setHistory(false);
                instance.setName(instanceName);
                instance.setCreatedOn(new Date());
            }

            // ??????remarks
            StringBuilder remarks = new StringBuilder(node.getName() + ",");
            remarks.append(instance.getName() + ",");
            remarks.append(definition.getName() + ",");
            if (StringUtils.isNotBlank(businessKey)) {
                remarks.append(businessKey + ",");
            }
            ServerResponse<List<PassportUserInfoDTO>> userInfoResponse = passportFeignClient.getUserMsgByIds(Collections.singletonList(instanceCreatorId.intValue()));
            if (userInfoResponse.getCode() == ResponseCode.SUCCESS.getCode()) {
                List<PassportUserInfoDTO> userList = userInfoResponse.getData();
                if (CollectionUtils.isNotEmpty(userList)) {
                    remarks.append(userList.get(0).getNickname());
                }
            }
            node.setRemarks(remarks.toString());

            List<Integer> targets = new ArrayList<>();
            List<String> usersClone = new ArrayList<>();
            List<Integer> approver = JsonUtil.parseArray(delegateTask.getVariable("approver").toString(), Integer.class);
            if (CollectionUtils.isNotEmpty(approver)) {
                List<String> users = new ArrayList<>();
                for (Integer userId : approver) {
                    if (CandidateUserType.CREATOR.getState() == userId) {
                        users.add(instanceCreatorId.toString());
                        usersClone.add(instanceCreatorId.toString());
                        targets.add(instanceCreatorId.intValue());
                    } else if (CandidateUserType.CREATOR_LEADER.getState() == userId) {
                        List<String> list = new ArrayList<>();
                        list.add(instanceCreatorId.toString());
                        ServerResponse<List<UserGroupLeaderVO>> response = passportFeignClient.getDeptLeaderInfoList(list);
                        if (response.getCode() == ResponseCode.SUCCESS.getCode()) {
                            List<UserGroupLeaderVO> leaders = response.getData();
                            for (UserGroupLeaderVO leaderVO : leaders) {
                                if (leaderVO.getLeaderIds() != null && leaderVO.getLeaderIds().size() > 0) {
                                    for (Integer leaderId : leaderVO.getLeaderIds()) {
                                        users.add(leaderId.toString());
                                        usersClone.add(leaderId.toString());
                                        targets.add(leaderId);
                                    }
                                }
                            }
                        }
                    } else {
                        users.add(userId.toString());
                        usersClone.add(userId.toString());
                        targets.add(Integer.valueOf(userId));
                    }
                }
                delegateTask.addCandidateUsers(users);
            }

            TaskEntity task = taskService.insertTask(delegateTask, node, usersClone, definition, instance);

            //??????????????????
            LeaveReportTaskReq req = new LeaveReportTaskReq();
            req.setApplyId(applyId);
            req.setTaskId(task.getId());
            req.setUserId(approver);
            req.setStatus(0);

            hrFeignHandler.applyTask(req);

            logEntity.setContent("[ " + definition.getName() + " ] [ " + task.getName() + " ] ????????????");
            logEntity.setDefinitionId(definition.getId());
            if (CollectionUtils.isEmpty(targets)) {
                return;
            }
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
                message.setTargets(targets);
                message.setNoticeEventType(InstanceEvent.TASK_CREATE.getCode());
                // ?????????????????????????????????????????????
                sendNoticeContext.send(template, message);
            });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            // ????????????????????????
            Integer userId = UserHelper.getUserId();
            logEntity.setCreatedBy(userId);
            logEntity.setLastUpdatedBy(userId);
            logEntity.setCreatedOn(new Date());
            logEntity.setLastUpdatedOn(new Date());
            logEntity.setTaskId(delegateTask.getId());
            logEntity.setInstanceId(delegateTask.getProcessInstanceId());
            logEntity.setEvent(InstanceEvent.TASK_CREATE.getCode().toString());
            operationLogService.log(logEntity);
        }
    }
}