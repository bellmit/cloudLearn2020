package com.ruigu.rbox.workflow.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ruigu.rbox.cloud.kanai.model.YesOrNoEnum;
import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.cloud.kanai.util.JsonUtil;
import com.ruigu.rbox.workflow.exceptions.GlobalRuntimeException;
import com.ruigu.rbox.workflow.exceptions.VerificationFailedException;
import com.ruigu.rbox.workflow.feign.WeixinFeignClient;
import com.ruigu.rbox.workflow.manager.PassportFeignManager;
import com.ruigu.rbox.workflow.model.ServerResponse;
import com.ruigu.rbox.workflow.model.UpdateStatusMsg;
import com.ruigu.rbox.workflow.model.bpmn.Button;
import com.ruigu.rbox.workflow.model.dto.BaseTaskInfoDTO;
import com.ruigu.rbox.workflow.model.dto.InstanceTaskInfoDTO;
import com.ruigu.rbox.workflow.model.dto.PassportUserInfoDTO;
import com.ruigu.rbox.workflow.model.entity.*;
import com.ruigu.rbox.workflow.model.enums.*;
import com.ruigu.rbox.workflow.model.request.MyTaskRequest;
import com.ruigu.rbox.workflow.model.request.TaskForm;
import com.ruigu.rbox.workflow.model.request.TaskFormItem;
import com.ruigu.rbox.workflow.model.response.Form;
import com.ruigu.rbox.workflow.model.response.TaskDetail;
import com.ruigu.rbox.workflow.model.vo.*;
import com.ruigu.rbox.workflow.repository.*;
import com.ruigu.rbox.workflow.service.*;
import com.ruigu.rbox.workflow.strategy.context.SendNoticeContext;
import com.ruigu.rbox.workflow.supports.*;
import lombok.extern.slf4j.Slf4j;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.TaskService;
import org.activiti.engine.delegate.DelegateTask;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.task.IdentityLink;
import org.activiti.engine.task.Task;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author alan.zhao
 */
@Slf4j
@Service
public class WorkflowTaskServiceImpl implements WorkflowTaskService {

    @Resource
    private RepositoryService repositoryService;

    @Resource
    private NodeRepository nodeRepository;

    @Resource
    private TaskService taskService;

    @Resource
    private TaskCommentService taskCommentService;

    @Resource
    private TaskNativeRepository taskNativeRepository;

    @Resource
    private TaskRepository taskRepository;

    @Resource
    private OperationLogService operationLogService;

    @Resource
    private WorkflowInstanceRepository workflowInstanceRepository;

    @Resource
    private WorkflowInstanceService workflowInstanceService;

    @Resource
    private WorkflowDefinitionRepository workflowDefinitionRepository;

    @Resource
    private NoticeLogService noticeLogService;

    @Resource
    private UserGroupService userGroupService;

    @Resource
    private FormDataUtil formDataUtil;

    @Resource
    private PassportFeignManager passportFeignManager;

    @Resource
    private WeixinFeignClient weixinFeignClient;

    @Resource
    private NoticeConfigService noticeConfigService;

    @Resource
    private NoticeContentUtil noticeContentUtil;

    @Resource
    private SendNoticeContext sendNoticeContext;

    @Resource
    private WorkflowFormRepository workflowFormRepository;

    @Value("${rbox.msg.weixin.agentId}")
    private String agentId;

    @Value("${rbox.workflow.definition.lightning}")
    private String lightningKey;

    @Override
    public TaskDetail detail(String id) throws Exception {
        TaskEntity taskEntity = taskRepository.findById(id)
                .orElseThrow(() -> new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "???????????????"));
        TaskDetail detail = new TaskDetail();
        ObjectUtil.extendObject(detail, taskEntity, true);
        // ??????form
        Form form = new Form();
        form.setDefinition(detail.getFormContent());
        detail.setFormContent(null);
        // ?????????????????????
        WorkflowInstanceVO instance = workflowInstanceService.getInstanceById(taskEntity.getInstanceId());
        if (instance == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????");
        }
        // ??????????????????
        JSONObject content = JSON.parseObject(form.getDefinition());
        if (content != null && content.getJSONArray(FormContentParam.LIST.getText()) != null && !content.getJSONArray(FormContentParam.LIST.getText()).isEmpty()) {
            List<Map<String, Object>> formDataList = formDataUtil.getFormDefinition(content.getJSONArray(FormContentParam.LIST.getText()));
            // ????????????data
            formDataUtil.setFormData(formDataList, instance);
            form.setData(formDataUtil.getDataCode(formDataList));
        }
        detail.setOrderNumber(instance.getBusinessKey());
        detail.setProcessName(instance.getName());
        detail.setProcessCreatedOn(instance.getCreatedOn());
        detail.setProcessId(instance.getId());
        detail.setForm(form);
        detail.setBusinessUrl(instance.getBusinessUrl());
        detail.setMobileBusinessUrl(instance.getMobileBusinessUrl());
        detail.setPcBusinessUrl(instance.getPcBusinessUrl());
        // ????????????????????? ??? ???????????????
        Set<Integer> ids = new HashSet<>();
        Integer instanceCreatedBy = instance.getCreatedBy().intValue();
        ids.add(instanceCreatedBy);
        Integer taskSubmitBy = 0;
        if (taskEntity.getStatus() > TaskState.RUNNING.getState()) {
            taskSubmitBy = taskEntity.getSubmitBy().intValue();
            ids.add(taskSubmitBy);
        }

        List<PassportUserInfoDTO> userList = passportFeignManager.getUserInfoListFromRedis(ids);

        if (CollectionUtils.isNotEmpty(userList)) {
            userList.forEach(user -> {
                String nickName = user.getNickname();
                Integer userId = user.getId();
                if (userId.longValue() == instance.getCreatedBy()) {
                    detail.setProcessCreatedBy(nickName);
                    Map<String, Object> creatorMap = new HashMap<>(4);
                    creatorMap.put("creator", nickName);
                    if (detail.getForm().getData() == null) {
                        detail.getForm().setData(creatorMap);
                    } else {
                        detail.getForm().getData().putAll(creatorMap);
                    }
                }
                if (userId.longValue() == (taskEntity.getSubmitBy() == null ? -1 : taskEntity.getSubmitBy())) {
                    detail.setSubmitName(nickName);
                }
            });
        }

        // ???????????????????????????
        Form initialForm = new Form();
        WorkflowFormEntity formDefinition = workflowFormRepository.findTopBydefinitionId(instance.getDefinitionId());
        if (formDefinition != null && formDefinition.getFormContent() != null) {
            initialForm.setDefinition(formDefinition.getFormContent());
            JSONObject formContent = JSONObject.parseObject(formDefinition.getFormContent());
            JSONArray list = formContent.getJSONArray(FormContentParam.LIST.getText());
            List<Map<String, Object>> formDataList = formDataUtil.getFormDefinition(list);
            if (formDataList != null && !formDataList.isEmpty()) {
                formDataUtil.setFormData(formDataList, instance);
                initialForm.setData(formDataUtil.getDataCode(formDataList));
            }
            detail.setInitialForm(initialForm);
        }

        // ????????????????????????
        List<TaskCommentVO> comments = taskCommentService.getCommentByTaskId(taskEntity.getId());
        detail.setComments(comments);
        return detail;
    }

//    @Override
//    @Transactional(rollbackFor = Exception.class)
//    public String saveTask(TaskForm taskForm, boolean submit, boolean batch, Long userId) {
//        return saveTask(taskForm, submit, batch, false, userId);
//    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String saveTask(TaskForm taskForm, boolean submit, boolean batch, boolean autoBegin, Long userId) {
        if (StringUtils.isBlank(taskForm.getId())) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????id????????????");
        }
        TaskEntity old = taskRepository.findById(taskForm.getId())
                .orElseThrow(() -> new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????" + (submit ? "??????" : "??????") + "?????????????????????"));
        WorkflowInstanceEntity instance = workflowInstanceRepository.selectInstanceByTaskId(taskForm.getId());
        if (instance == null) {
            throw new VerificationFailedException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????????????????????????????");
        }
        if (instance.getStatus() == InstanceState.SLEEP.getState()) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????" + (submit ? "??????" : "??????"));
        }
        boolean isApproval = old.getApprovalNode() == TaskSubmitState.PASS.getCode();
        NoticeTemplateEntity template = null;
        String buttonConfig = null;
        if (isApproval) {
            List<NoticeTemplateEntity> templates = noticeConfigService.getNoticeTemplate(NoticeConfigState.NODE,
                    old.getNodeId(), InstanceEvent.TASK_CREATE.getCode());
            if (CollectionUtils.isNotEmpty(templates)) {
                template = templates.stream().filter(t -> NoticeType.WEIXIN_CHANNEL.getState() == t.getChannel()
                        && NoticeType.TASK_CARD.getState() == t.getType()).findFirst().orElse(null);
                if (template != null) {
                    buttonConfig = template.getButtonConfig();
                }
            }
            // ???????????????????????????
            if (StringUtils.isBlank(buttonConfig)) {
                buttonConfig = old.getNoticeContent();
            }
            if (StringUtils.isBlank(buttonConfig)) {
                throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????????????????????????????");
            }
        }
        if (old.getStatus() == TaskState.UNTREATED.getState()) {
            if (!isApproval && !autoBegin) {
                throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????????????????????????????????????????" + (submit ? "??????" : "??????"));
            }
        }
        if (old.getStatus() >= TaskState.COMPLETED.getState()) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????????????????????????????" + (submit ? "??????" : "??????"));
        }
        TaskEntity task = new TaskEntity();
        task.setId(taskForm.getId());
        task.setSubmitBy(userId);
        task.setSubmitTime(new Date());
        Map<String, Object> variables = new HashMap<>(16);
        if (submit && batch && isApproval) {
            variables = getApprovalNodeParam(buttonConfig, taskForm.getApprovalValue());
            task.setData(JSON.toJSONString(variables));
        } else {
            if (taskForm.getFormData() != null) {
                for (TaskFormItem item : taskForm.getFormData()) {
                    variables.put(item.getName(), item.getValue());
                }
                task.setData(JSON.toJSONString(variables));
            }
        }
        if (submit) {
            setApprovalNodeStatus(buttonConfig, task, taskForm, isApproval);
        }
        saveIgnoreNull(task, userId);
        if (submit) {
            taskService.claim(taskForm.getId(), userId.toString());
            taskService.complete(taskForm.getId(), variables, true);
            // ??????complete?????????????????? ?????????????????????????????????,???????????????
            if (template != null) {
                log.debug("============================== ???????????????????????? ============================");
                // ????????????
                try {
                    ServerResponse serverResponse = updateTaskCardStatus(old, taskForm, template.getButtonConfig());
                    if (serverResponse.getCode() != ResponseCode.SUCCESS.getCode()) {
                        log.error("??????ID:{} ??????????????????????????????????????????{} ", old.getId(), serverResponse);
                    }
                } catch (Exception e) {
                    log.error("??????????????????????????????????????????", e);
                }
            }
        }
        return taskForm.getId();
    }

    @Override
    public NodeEntity queryNode(DelegateTask delegateTask) {
        String taskDefinitionKey = delegateTask.getTaskDefinitionKey();
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().processDefinitionId(delegateTask.getProcessDefinitionId()).singleResult();
        String deploymentId = processDefinition.getDeploymentId();
        return nodeRepository.fromDeploymentInfo(deploymentId, taskDefinitionKey);
    }

    @Override
    public NodeEntity queryNodeByTask(Task activityTask) {
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().processDefinitionId(activityTask.getProcessDefinitionId()).singleResult();
        String deploymentId = processDefinition.getDeploymentId();
        return nodeRepository.fromDeploymentInfo(deploymentId, activityTask.getTaskDefinitionKey());
    }

    private NodeEntity queryNodeByTaskId(String taskId) {
        Task activityTask = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (activityTask == null) {
            return null;
        }
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().processDefinitionId(activityTask.getProcessDefinitionId()).singleResult();
        String deploymentId = processDefinition.getDeploymentId();
        return nodeRepository.fromDeploymentInfo(deploymentId, activityTask.getTaskDefinitionKey());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskEntity insertTask(DelegateTask delegateTask, NodeEntity nodeEntity, List<String> candidateUsers, WorkflowDefinitionEntity definition, WorkflowInstanceVO instance) {
        String instanceId = delegateTask.getProcessInstanceId();
        Long userId = UserHelper.getUserId() != null ? UserHelper.getUserId().longValue() : 0;
        TaskEntity task = new TaskEntity();
        task.setId(delegateTask.getId());
        task.setNodeId(nodeEntity.getId());
        task.setModelId(nodeEntity.getModelId());
        task.setGraphId(nodeEntity.getGraphId());
        task.setName(nodeEntity.getName());
        task.setType(nodeEntity.getType());
        task.setDescription(nodeEntity.getDescription());
        task.setDueTime(nodeEntity.getDueTime());
        if (candidateUsers != null && candidateUsers.size() > 0) {
            task.setCandidateUsers(StringUtils.join(candidateUsers, Symbol.COMMA.getValue()));
        } else {
            task.setCandidateUsers(nodeEntity.getCandidateUsers());
        }
        task.setCandidateGroups(nodeEntity.getCandidateGroups());
        task.setFormLocation(nodeEntity.getFormLocation());
        task.setFormContent(nodeEntity.getFormContent());
        task.setNoticeContent(nodeEntity.getNoticeContent());
        task.setNoticeType(nodeEntity.getNoticeType());
        task.setBusinessUrl(nodeEntity.getBusinessUrl());
        task.setApprovalNode(nodeEntity.getApprovalNode());
        task.setRemarks(nodeEntity.getRemarks());
        task.setStatus(TaskState.UNTREATED.getState());
        task.setInstanceId(instanceId);
        task.setCreatedBy(userId);
        task.setCreatedOn(new Date());
        task.setLastUpdatedBy(userId);
        task.setLastUpdatedOn(new Date());
        if (StringUtils.isNotBlank(nodeEntity.getSummary()) && nodeEntity.getApprovalNode() == 1) {
            BaseTaskInfoDTO baseTaskInfoDTO = ConvertClassUtil.convertToBaseTaskInfoDTO(task);
            Map<String, Object> paramMap = noticeContentUtil.returnNodeTemplateParamValue(baseTaskInfoDTO, delegateTask.getVariables());
            paramMap.put(InstanceVariableParam.INITIAL_CODE.getText(), definition.getInitialCode());
            task.setSummary(ElUtil.fill(paramMap, nodeEntity.getSummary()).replaceAll("\\n", "<br>"));
        }
        taskRepository.save(task);
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transfer(String taskId, List<Integer> userIds, Integer userId) {
        // ????????????????????????
        OperationLogEntity logEntity = new OperationLogEntity();

        List<IdentityLink> identityLinks = taskService.getIdentityLinksForTask(taskId);
        for (IdentityLink link : identityLinks) {
            if (link.getUserId() != null) {
                taskService.deleteCandidateUser(taskId, link.getUserId());
            }
            if (link.getGroupId() != null) {
                taskService.deleteCandidateGroup(taskId, link.getGroupId());
            }
        }

        Set<Integer> targets = new HashSet<>();
        for (Integer id : userIds) {
            taskService.addCandidateUser(taskId, id.toString());
            targets.add(id);
        }

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setCandidateUsers(StringUtils.join(userIds, ","));
        task.setCandidateGroups(null);
        saveIgnoreNull(task, userId.longValue());

        Task activityTask = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (activityTask == null) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "????????????????????????????????????????????????????????????");
        }
        TaskEntity taskEntity = getTaskEntityById(taskId);
        if (taskEntity == null) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "????????????????????????????????????????????????????????????");
        }
        WorkflowDefinitionEntity definition = workflowDefinitionRepository.findByTaskId(taskId);
        if (definition == null) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "????????????????????????????????????????????????????????????");
        }
        logEntity.setCreatedBy(userId);
        logEntity.setLastUpdatedBy(userId);
        logEntity.setCreatedOn(new Date());
        logEntity.setLastUpdatedOn(new Date());
        logEntity.setDefinitionId(definition.getId());
        logEntity.setTaskId(taskId);
        logEntity.setInstanceId(activityTask.getProcessInstanceId());
        logEntity.setContent("[ " + definition.getName() + " ] [ " + taskEntity.getName() + " ] ????????????????????????ID: " + StringUtils.join(userIds, ","));
        operationLogService.log(logEntity);

        // ???????????????????????????
        if (CollectionUtils.isEmpty(targets)) {
            return;
        }
        // ?????????????????????
        List<NoticeTemplateEntity> noticeTemplate = noticeConfigService
                .getNoticeTemplate(NoticeConfigState.NODE,
                        taskEntity.getNodeId(),
                        InstanceEvent.TASK_COMPLETE.getCode());
        if (CollectionUtils.isEmpty(noticeTemplate)) {
            return;
        }

        // ??????????????????
        WorkflowInstanceVO instance = workflowInstanceService.getInstanceById(activityTask.getProcessInstanceId());
        if (instance == null) {
            log.error("????????????????????????????????????????????????");
            return;
        }

        // ???????????? ????????????
        BaseTaskInfoDTO baseTaskInfoDTO = ConvertClassUtil.convertToBaseTaskInfoDTO(task);
        noticeTemplate.forEach(template -> {
            // ???title ??? content??????
            MessageInfoVO message = noticeContentUtil.translateNodeTemplate(template, definition, baseTaskInfoDTO, activityTask.getProcessVariables());
            message.setTargets(targets);
            message.setNoticeEventType(InstanceEvent.TASK_TRANSFER.getCode());
            // ?????????????????????????????????????????????
            sendNoticeContext.send(template, message);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveIgnoreNull(TaskEntity entity, Long userId) {
        Optional<TaskEntity> optionalTaskEntity = taskRepository.findById(entity.getId());
        if (optionalTaskEntity.isPresent()) {
            TaskEntity last = optionalTaskEntity.get();
            ObjectUtil.extendObject(last, entity, true);
            taskRepository.save(last);
        } else {
            throw new VerificationFailedException(400, "???????????????");
        }
    }

    @Override
    public List<TaskVO> findUnfinishedTask() {
        // ????????????????????????????????????
        return taskNativeRepository.selectUnfinishedTask();
    }

    @Override
    public Page<TaskVO> getAllTaskByCandidateId(MyTaskRequest myTaskRequest) {
        Integer userId = UserHelper.getUserId();
        ValidResult validResult = ValidUtil.validFields(myTaskRequest,
                new String[]{"pageNum"},
                new Object[][]{
                        {ValidUtil.NON_NEGATIVE_INTEGER}
                });
        if (!validResult.valid) {
            throw new VerificationFailedException(400, validResult.message);
        }
        List<BusinessParamEntity> businessParams = myTaskRequest.getBusinessParams();
        Page<TaskVO> tasks = taskNativeRepository.selectAllTaskByCandidateId(userId, myTaskRequest, businessParams);
        setCreator(tasks.getContent());
        return tasks;
    }

    @Override
    public TaskEntity getCurrentTaskByDefinitionKeyAndBusinessKey(String definitionKey, String businessKey) {
        return taskRepository.findCurrentTaskByDefinitionKeyAndBusinessKey(definitionKey, businessKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateBeginStatus(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????ID????????????");
        }
        taskRepository.findById(taskId)
                .orElseThrow(() -> new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "???????????????"));
        taskRepository.updateBeginStatus(taskId, new Date());

        // ????????????
        WorkflowInstanceVO instance = workflowInstanceService.getInstanceByTaskId(taskId);
        if (instance == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????????????????");
        }
        Date now = new Date();
        OperationLogEntity logEntity = new OperationLogEntity();
        logEntity.setEvent(String.valueOf(InstanceEvent.TASK_BEGIN.getCode()));
        logEntity.setContent("[ " + instance.getDefinitionName() + " ] [ " + instance.getName() + " ] ????????????");
        logEntity.setTaskId(taskId);
        logEntity.setInstanceId(instance.getId());
        logEntity.setDefinitionId(instance.getDefinitionId());
        logEntity.setCreatedBy(UserHelper.getUserId());
        logEntity.setCreatedOn(now);
        logEntity.setLastUpdatedBy(UserHelper.getUserId());
        logEntity.setLastUpdatedOn(now);
        logEntity.setShowStatus(LightningApplyStatus.TO_BE_ACCEPTED.getCode());
        operationLogService.log(logEntity);
    }

    @Override
    public void updateBeginStatusByBusinessKey(String definitionKey, String businessKey) throws Exception {
        if (StringUtils.isBlank(definitionKey) || StringUtils.isBlank(businessKey)) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "????????????");
        }
        WorkflowInstanceEntity instance = workflowInstanceRepository.findByDefinitionCodeAndBusinessKey(definitionKey, businessKey);
        if (instance == null || !instance.getStatus().equals(InstanceState.RUNNING.getState())) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????");
        }
        // ????????????
        TaskEntity notBeginTask = taskRepository.findByInstanceIdAndStatus(instance.getId(), TaskState.UNTREATED.getState());
        if (notBeginTask == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????????????????");
        }
        updateBeginStatus(notBeginTask.getId());
    }

    @Override
    public List<TaskVO> getAllTaskByInstanceId(String instanceId) {
        if (StringUtils.isBlank(instanceId)) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????id????????????");
        }
        return taskRepository.selectAllTaskByInstanceId(instanceId);
    }

    @Override
    public List<TaskEntity> getAllTaskEntityByInstanceId(String instanceId) {
        return taskRepository.findAllByInstanceId(instanceId);
    }

    @Override
    public TaskEntity getTaskEntityById(String taskId) {
        return taskRepository.findById(taskId).orElse(null);
    }

    @Override
    public List<TaskEntity> getIncompleteTaskByInstanceIds(List<String> instanceIds) {
        return taskRepository.selectIncompleteTaskByInstanceIds(instanceIds);
    }

    @Override
    public TaskEntity getCurrentTaskByInstanceId(String instanceId) {
        return taskRepository.findCurrentTaskByInstanceId(instanceId);
    }

    @Override
    public List<InstanceTaskInfoDTO> getCurrentTaskIdByBusinessKey(String definitionKey, List<String> businessKeys, Integer userId) {
        return taskRepository.findCurrentTaskIdByBusinessKeyIn(definitionKey, businessKeys, String.valueOf(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTaskBeginStatus(TaskEntity task) {
        if (task == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????");
        }
        task.setBeginTime(new Date());
        task.setStatus(YesOrNoEnum.YES.getCode());
        taskRepository.save(task);
    }

    private void setApprovalNodeStatus(String buttonConfig, TaskEntity task, TaskForm taskForm, boolean isApproval) {
        if (isApproval) {
            Map<String, Object> btnKey = getButtonKey(taskForm, buttonConfig);
            Collection<Object> values = btnKey.values();
            String passValue = String.valueOf(TaskSubmitState.PASS.getCode());
            if (values.iterator().hasNext()) {
                Object value = values.iterator().next();
                boolean flag = true;
                if (value instanceof Integer) {
                    if (Integer.parseInt(String.valueOf(value)) != TaskSubmitState.PASS.getCode()) {
                        flag = false;
                    }
                } else if (value instanceof Double) {
                    if (!Double.valueOf(String.valueOf(value)).equals(Double.valueOf(passValue))) {
                        flag = false;
                    }
                } else {
                    if (!passValue.equals(String.valueOf(value))) {
                        flag = false;
                    }
                }
                if (flag) {
                    task.setStatus(TaskState.APPROVAL.getState());
                } else {
                    task.setStatus(TaskState.REJECT.getState());
                }
            }
        } else {
            task.setStatus(TaskState.COMPLETED.getState());
        }
    }

    private ServerResponse updateTaskCardStatus(TaskEntity task, TaskForm taskForm, String buttonConfig) {
        if (task == null || taskForm == null) {
            return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????");
        }
        if (StringUtils.isBlank(buttonConfig)) {
            return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????");
        }
        try {
            NoticeEntity notice = noticeLogService.getNoticeTaskId(task.getId());
            UpdateStatusMsg msg = new UpdateStatusMsg();
            // ??????????????????id
            msg.setTaskId(notice.getTaskIdWeixin());
            msg.setAgentId(Integer.valueOf(agentId));
            // ??????key
            Map<String, Object> btnKey = getButtonKey(taskForm, buttonConfig);
            if (btnKey.isEmpty() || btnKey.size() > 1) {
                return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????????????????????????????????????????????????????");
            }
            msg.setClickedKey(btnKey.keySet().iterator().next());
            // ???????????????
            String[] ids = new String[0], groups;
            List<Integer> userIds = new ArrayList<>();
            if (StringUtils.isNotBlank(task.getCandidateUsers())) {
                ids = task.getCandidateUsers().split(Symbol.COMMA.getValue());
            }
            if (StringUtils.isNotBlank(task.getCandidateGroups())) {
                groups = task.getCandidateGroups().split(Symbol.COMMA.getValue());
                if (groups.length > 0) {
                    userIds = userGroupService.getUserListByGroupsInt(Arrays.asList(groups));
                }
            }
            for (String id : ids) {
                userIds.add(Integer.valueOf(id));
            }
            if (userIds.size() < 1) {
                return ServerResponse.ok();
            }
            Set<Integer> userSet = new HashSet<>(userIds);
            List<PassportUserInfoDTO> userList = passportFeignManager.getUserInfoListFromRedis(userSet);
            if (CollectionUtils.isNotEmpty(userList)) {
                List<String> userIdList = userList.stream().map(PassportUserInfoDTO::getWxWorkUserId).collect(Collectors.toList());
                msg.setUserIds(userIdList);
            }
            return weixinFeignClient.sendUpdateStatusMsg(msg);
        } catch (Exception e) {
            log.error("????????????????????????????????????????????????{}", e);
        }
        return ServerResponse.fail("????????????");
    }

    private Map<String, Object> getButtonKey(TaskForm taskForm, String buttonConfig) {
        Map<String, Object> btnKeyMap = new HashMap<>(4);
        List<TaskFormItem> formDataList = taskForm.getFormData();
        List<Button> buttons = JsonUtil.parseArray(buttonConfig, Button.class);
        if (CollectionUtils.isNotEmpty(formDataList)) {
            formDataList.forEach(data ->
                    buttons.forEach(btn -> {
                        if (!data.getName().equals(btn.getVarName())) {
                            return;
                        }
                        // ??????????????????
                        String btnKey = btn.getKey();
                        String varValue = btn.getVarValue();
                        int type = btn.getVarType();

                        if (type == DataType.OTHER_DATA.getState() || type == DataType.STRING_DATA.getState()) {
                            String dataValue = String.valueOf(data.getValue());
                            if (dataValue.equals(varValue)) {
                                btnKeyMap.put(btnKey, dataValue);
                            }
                        } else if (type == DataType.INTEGER_DATA.getState()) {
                            Integer dataValue = Integer.valueOf(String.valueOf(data.getValue()));
                            Integer btnValue = Integer.valueOf(varValue);
                            if (dataValue.equals(btnValue)) {
                                btnKeyMap.put(btnKey, dataValue);
                            }
                        } else if (type == DataType.DOUBLE_DATA.getState()) {
                            Double dataValue = Double.valueOf(String.valueOf(data.getValue()));
                            Double btnValue = Double.valueOf(varValue);
                            if (dataValue.equals(btnValue)) {
                                btnKeyMap.put(btnKey, dataValue);
                            }
                        }
                    })
            );
        } else if (StringUtils.isNotBlank(taskForm.getApprovalValue())) {
            buttons.stream()
                    .filter(button -> taskForm.getApprovalValue().equals(button.getVarValue()))
                    .findFirst().ifPresent(button -> btnKeyMap.put(button.getKey(), taskForm.getApprovalValue()));
        }
        return btnKeyMap;
    }

    private Map<String, Object> getApprovalNodeParam(String buttonConfig, String value) {
        Map<String, Object> var = new HashMap<>(16);
        if (StringUtils.isBlank(buttonConfig)) {
            return var;
        }
        List<Button> buttons = JsonUtil.parseArray(buttonConfig, Button.class);
        String name = buttons.get(0).getVarName();
        Integer type = buttons.get(0).getVarType();
        if (type == DataType.OTHER_DATA.getState() || type == DataType.STRING_DATA.getState()) {
            var.put(name, value);
        } else if (type == DataType.INTEGER_DATA.getState()) {
            var.put(name, Integer.valueOf(value));
        } else if (type == DataType.DOUBLE_DATA.getState()) {
            var.put(name, Double.valueOf(value));
        }
        return var;
    }

    private void setCreator(List<TaskVO> taskList) {
        if (CollectionUtils.isEmpty(taskList)) {
            return;
        }
        List<Integer> ids = taskList.stream()
                .distinct()
                .map(t -> t.getInstanceCreatedBy().intValue())
                .collect(Collectors.toList());

        Map<Integer, PassportUserInfoDTO> userInfoMap = passportFeignManager.getUserInfoMapFromRedis(ids);

        taskList.forEach(task -> {
                    PassportUserInfoDTO user = userInfoMap.get(task.getInstanceCreatedBy().intValue());
                    if (user != null) {
                        task.setInstanceCreator(user.getNickname());
                    }
                }
        );
    }
}