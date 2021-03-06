package com.ruigu.rbox.workflow.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.cloud.kanai.util.JsonUtil;
import com.ruigu.rbox.workflow.exceptions.GlobalRuntimeException;
import com.ruigu.rbox.workflow.exceptions.VerificationFailedException;
import com.ruigu.rbox.workflow.manager.PassportFeignManager;
import com.ruigu.rbox.workflow.model.bpmn.Button;
import com.ruigu.rbox.workflow.model.dto.BaseTaskInfoDTO;
import com.ruigu.rbox.workflow.model.dto.PassportUserInfoDTO;
import com.ruigu.rbox.workflow.model.entity.*;
import com.ruigu.rbox.workflow.model.enums.*;
import com.ruigu.rbox.workflow.model.request.*;
import com.ruigu.rbox.workflow.model.response.Form;
import com.ruigu.rbox.workflow.model.vo.*;
import com.ruigu.rbox.workflow.repository.*;
import com.ruigu.rbox.workflow.service.*;
import com.ruigu.rbox.workflow.service.listener.GlobalActivitiEventListener;
import com.ruigu.rbox.workflow.strategy.context.SendNoticeContext;
import com.ruigu.rbox.workflow.supports.*;
import lombok.extern.slf4j.Slf4j;
import org.activiti.api.process.model.ProcessInstance;
import org.activiti.api.process.model.builders.ProcessPayloadBuilder;
import org.activiti.api.process.runtime.ProcessRuntime;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.runtime.Execution;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author alan.zhao
 * @date 2019/09/02
 */
@Slf4j
@Service
public class WorkflowInstanceServiceImpl implements WorkflowInstanceService {
    @Resource
    private TaskCommentService taskCommentService;

    @Resource
    private BusinessParamRepository businessParamRepository;

    @Resource
    private WorkflowInstanceRepository workflowInstanceRepository;

    @Resource
    private WorkflowHistoryRepository workflowHistoryRepository;

    @Resource
    private WorkflowFormRepository workflowFormRepository;

    @Resource
    private TaskRepository taskRepository;

    @Resource
    private TaskNativeRepository taskNativeRepository;

    @Resource
    private WorkflowDefinitionRepository definitionRepository;

    @Resource
    private GlobalActivitiEventListener globalActivitiEventListener;

    @Resource
    private ProcessRuntime processRuntime;

    @Resource
    private RuntimeService runtimeService;

    @Resource
    private RepositoryService repositoryService;

    @Resource
    private FormDataUtil formDataUtil;

    @Resource
    private OperationLogRepository operationLogRepository;

    @Resource
    private NoticeContentUtil noticeContentUtil;

    @Resource
    private NoticeConfigService noticeConfigService;

    @Resource
    private SendNoticeContext sendNoticeContext;

    @Resource
    private OperationLogService operationLogService;

    @Resource
    private PassportFeignManager passportFeignManager;

    @Value("${rbox.msg.weixin.agentId}")
    private String agentId;

    @PostConstruct
    public void init() {
        runtimeService.addEventListener(globalActivitiEventListener);
        log.info("?????????????????????");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String start(StartInstanceRequest request, Long userId) {
        // ???????????????????????????????????????
        WorkflowDefinitionEntity workflowDefinitionEntity = definitionRepository.latestReleased(request.getKey());
        if (workflowDefinitionEntity == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????[key=" + request.getKey() + "]????????????????????????");
        }
        String definitionId = workflowDefinitionEntity.getId();
        String definitionName = workflowDefinitionEntity.getName();

        // ??????????????????
        WorkflowFormEntity form = workflowFormRepository.findByDefinitionId(definitionId);
        Map<String, Integer> paramOperation = new HashMap<>(16);
        if (form != null && StringUtils.isNotBlank(form.getSelectFormContent())) {
            paramOperation = formDataUtil.getParamOperation(form.getSelectFormContent());
            if (paramOperation.isEmpty()) {
                throw new VerificationFailedException(ResponseCode.ERROR.getCode(), "??????????????????JSON????????????????????????????????????SQL????????????");
            }
        }

        // activiti ????????? ??????????????????
        List<ProcessDefinition> list = repositoryService.createProcessDefinitionQuery().deploymentId(workflowDefinitionEntity.getDeploymentId()).list();
        ProcessDefinition processDefinition = list.get(0);
        String instanceName = StringUtils.isNotBlank(request.getName()) ? request.getName() : definitionName;

        // ???????????????????????????????????? ?????????????????????????????????
        Map<String, Object> variables = request.getVariables();
        if (variables == null) {
            variables = new HashMap<>(16);
        }
        String businessKey = request.getBusinessKey();
        Date instanceStartDate = new Date();
        variables.put(InstanceVariableParam.DEFINITION_ID.getText(), definitionId);
        variables.put(InstanceVariableParam.DEFINITION_NAME.getText(), definitionName);
        variables.put(InstanceVariableParam.INSTANCE_CREATOR_ID.getText(), userId);
        variables.put(InstanceVariableParam.INSTANCE_NAME.getText(), instanceName);
        variables.put(InstanceVariableParam.INSTANCE_CREATE_TIME.getText(), instanceStartDate);
        variables.put(InstanceVariableParam.BUSINESS_KEY.getText(), businessKey);

        // ??????????????????????????????
        Map<String, Object> businessParams = request.getBusinessParams();
        if (businessParams != null && !businessParams.isEmpty()) {
            variables.put(InstanceVariableParam.BUSINESS_PARAM.getText(), JSONObject.toJSONString(request.getBusinessParams()));
        }
        ProcessInstance processInstance = processRuntime.start(ProcessPayloadBuilder
                .start()
                .withProcessDefinitionKey(processDefinition.getKey())
                .withName(instanceName)
                .withBusinessKey(businessKey)
                .withVariables(variables)
                .build());
        String instanceId = processInstance.getId();

        // activity????????? ??????????????????????????? ????????????????????????????????? workflow_instance ?????????
        WorkflowInstanceEntity ownInstance = buildOwnInstance(processInstance, workflowDefinitionEntity, request, request.getOwnerId(), instanceStartDate.getTime());
        workflowInstanceRepository.save(ownInstance);

        List<BusinessParamEntity> paramEntityList = new ArrayList<>();
        Map<String, Integer> finalParamOperation = paramOperation;
        if (variables.containsKey(InstanceVariableParam.BUSINESS_PARAM.getText())) {
            businessParams.keySet().forEach(paramKey -> {
                BusinessParamEntity businessParamEntity = new BusinessParamEntity();
                businessParamEntity.setInstanceId(processInstance.getId());
                businessParamEntity.setDefinitionCode(workflowDefinitionEntity.getInitialCode());
                businessParamEntity.setParamKey(paramKey);
                businessParamEntity.setParamValue(String.valueOf(businessParams.get(paramKey)));
                businessParamEntity.setFindWay(finalParamOperation.get(paramKey));
                paramEntityList.add(businessParamEntity);
            });
            businessParamRepository.saveAll(paramEntityList);
        }

        // ????????????
        OperationLogEntity logEntity = new OperationLogEntity();
        String event = String.valueOf(InstanceEvent.INSTANCE_START.getCode());
        logEntity.setContent("[ " + definitionName + " ] ????????????");
        logEntity.setCreatedBy(userId.intValue());
        logEntity.setLastUpdatedBy(userId.intValue());
        logEntity.setCreatedOn(instanceStartDate);
        logEntity.setLastUpdatedOn(instanceStartDate);
        // ??????????????????id,????????????????????????id(?????????????????????????????????????????????)
        logEntity.setTaskId(event);
        logEntity.setEvent(event);
        logEntity.setDefinitionId(definitionId);
        logEntity.setInstanceId(instanceId);
        operationLogService.log(logEntity);

        // ?????????????????? (????????????)
        List<NoticeTemplateEntity> noticeTemplate = noticeConfigService
                .getNoticeTemplate(NoticeConfigState.DEFINITION,
                        definitionId,
                        InstanceEvent.INSTANCE_START.getCode());
        if (CollectionUtils.isEmpty(noticeTemplate)) {
            return instanceId;
        }

        // ???????????? ????????????
        Map<String, Object> finalVariables = variables;
        // ????????????
        noticeTemplate.forEach(template -> {
            MessageInfoVO message = noticeContentUtil.translateDefinitionTemplate(template, workflowDefinitionEntity, instanceId, finalVariables);
            message.setTargets(Collections.singletonList(userId.intValue()));
            message.setNoticeEventType(InstanceEvent.INSTANCE_START.getCode());
            // ?????????????????????????????????????????????
            sendNoticeContext.send(template, message);
        });
        return instanceId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DefinitionAndInstanceIdVO startExternalCall(StartInstanceRequest request, Integer userId) {
        String definitionKey = request.getKey();

        // ???????????????????????????????????????
        WorkflowDefinitionEntity workflowDefinitionEntity = definitionRepository.latestReleased(definitionKey);
        if (workflowDefinitionEntity == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????[key=" + definitionKey + "]????????????????????????");
        }
        String definitionId = workflowDefinitionEntity.getId();
        String definitionName = workflowDefinitionEntity.getName();

        // activiti ????????? ??????????????????
        List<ProcessDefinition> list = repositoryService.createProcessDefinitionQuery().deploymentId(workflowDefinitionEntity.getDeploymentId()).list();
        ProcessDefinition processDefinition = list.get(0);
        String instanceName = StringUtils.isNotBlank(request.getName()) ? request.getName() : definitionName;

        // ???????????????????????????????????? ?????????????????????????????????
        String businessKey = request.getBusinessKey();
        Date instanceStartDate = new Date();
        Map<String, Object> variables = request.getVariables();
        if (variables == null) {
            variables = new HashMap<>(16);
        }
        variables.put(InstanceVariableParam.DEFINITION_ID.getText(), definitionId);
        variables.put(InstanceVariableParam.DEFINITION_NAME.getText(), definitionName);
        variables.put(InstanceVariableParam.INSTANCE_CREATOR_ID.getText(), userId.longValue());
        variables.put(InstanceVariableParam.INSTANCE_NAME.getText(), instanceName);
        variables.put(InstanceVariableParam.INSTANCE_CREATE_TIME.getText(), instanceStartDate);
        variables.put(InstanceVariableParam.BUSINESS_KEY.getText(), businessKey);

        ProcessInstance processInstance = processRuntime.start(ProcessPayloadBuilder
                .start()
                .withProcessDefinitionKey(processDefinition.getKey())
                .withName(instanceName)
                .withBusinessKey(businessKey)
                .withVariables(variables)
                .build());
        String instanceId = processInstance.getId();
        // activity????????? ??????????????????????????? ????????????????????????????????? workflow_instance ?????????
        WorkflowInstanceEntity ownInstance = buildOwnInstance(processInstance, workflowDefinitionEntity, request, request.getOwnerId(), instanceStartDate.getTime());
        workflowInstanceRepository.save(ownInstance);
        // ????????????
        OperationLogEntity logEntity = new OperationLogEntity();
        String event = String.valueOf(InstanceEvent.INSTANCE_START.getCode());
        logEntity.setContent("[ " + definitionName + " ] ????????????");
        logEntity.setCreatedBy(userId);
        logEntity.setLastUpdatedBy(userId);
        logEntity.setCreatedOn(instanceStartDate);
        logEntity.setLastUpdatedOn(instanceStartDate);
        // ??????????????????id,????????????????????????id(?????????????????????????????????????????????)
        logEntity.setTaskId(event);
        logEntity.setEvent(event);
        logEntity.setDefinitionId(definitionId);
        logEntity.setInstanceId(instanceId);
        operationLogService.log(logEntity);

        // ?????????????????? (????????????)
        List<NoticeTemplateEntity> noticeTemplate = noticeConfigService
                .getNoticeTemplate(NoticeConfigState.DEFINITION,
                        definitionId,
                        InstanceEvent.INSTANCE_START.getCode());
        if (CollectionUtils.isEmpty(noticeTemplate)) {
            return new DefinitionAndInstanceIdVO(definitionId, instanceId, businessKey);
        }

        // ???????????? ????????????
        for (NoticeTemplateEntity template : noticeTemplate) {
            MessageInfoVO message = noticeContentUtil.translateDefinitionTemplate(template, workflowDefinitionEntity, instanceId, variables);
            message.setTargets(Collections.singletonList(userId));
            message.setNoticeEventType(InstanceEvent.INSTANCE_START.getCode());
            // ?????????????????????????????????????????????
            sendNoticeContext.send(template, message);
        }

        return new DefinitionAndInstanceIdVO(definitionId, instanceId, businessKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DefinitionAndInstanceIdVO lightningStart(StartLightningInstanceRequest request, Long userId) {
        // ???????????????????????????????????????
        WorkflowDefinitionEntity workflowDefinitionEntity = definitionRepository.latestReleased(request.getKey());
        if (workflowDefinitionEntity == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????[key=" + request.getKey() + "]????????????????????????");
        }
        String definitionId = workflowDefinitionEntity.getId();
        String definitionName = workflowDefinitionEntity.getName();

        // ??????????????????
        WorkflowFormEntity form = workflowFormRepository.findByDefinitionId(definitionId);
        Map<String, Integer> paramOperation = new HashMap<>(16);
        if (form != null && StringUtils.isNotBlank(form.getSelectFormContent())) {
            paramOperation = formDataUtil.getParamOperation(form.getSelectFormContent());
            if (paramOperation.isEmpty()) {
                throw new VerificationFailedException(ResponseCode.ERROR.getCode(), "??????????????????JSON????????????????????????????????????SQL????????????");
            }
        }

        InstanceInfoRequest lightningInstanceInfo = request.getInstanceInfo();
        // activiti ????????? ??????????????????
        List<ProcessDefinition> list = repositoryService.createProcessDefinitionQuery().deploymentId(workflowDefinitionEntity.getDeploymentId()).list();
        ProcessDefinition processDefinition = list.get(0);
        String instanceName = StringUtils.isNotBlank(lightningInstanceInfo.getName()) ? lightningInstanceInfo.getName() : definitionName;

        // ???????????????????????????????????? ?????????????????????????????????
        Map<String, Object> variables = lightningInstanceInfo.getVariables();
        if (variables == null) {
            variables = new HashMap<>(16);
        }
        String businessKey = lightningInstanceInfo.getBusinessKey();
        Date instanceStartDate = new Date();
        variables.put(InstanceVariableParam.DEFINITION_ID.getText(), definitionId);
        variables.put(InstanceVariableParam.DEFINITION_NAME.getText(), definitionName);
        variables.put(InstanceVariableParam.INSTANCE_CREATOR_ID.getText(), userId);
        variables.put(InstanceVariableParam.INSTANCE_NAME.getText(), instanceName);
        variables.put(InstanceVariableParam.INSTANCE_CREATE_TIME.getText(), instanceStartDate);
        variables.put(InstanceVariableParam.BUSINESS_KEY.getText(), businessKey);

        // ??????????????????????????????
        Map<String, Object> businessParams = lightningInstanceInfo.getBusinessParams();
        if (businessParams != null && !businessParams.isEmpty()) {
            variables.put(InstanceVariableParam.BUSINESS_PARAM.getText(), JSONObject.toJSONString(lightningInstanceInfo.getBusinessParams()));
        }
        ProcessInstance processInstance = processRuntime.start(ProcessPayloadBuilder
                .start()
                .withProcessDefinitionKey(processDefinition.getKey())
                .withName(instanceName)
                .withBusinessKey(businessKey)
                .withVariables(variables)
                .build());
        String instanceId = processInstance.getId();

        // activity????????? ??????????????????????????? ????????????????????????????????? workflow_instance ?????????
        WorkflowInstanceEntity ownInstance = buildOwnInstance(processInstance, workflowDefinitionEntity, request, lightningInstanceInfo.getOwnerId(), instanceStartDate.getTime());
        workflowInstanceRepository.save(ownInstance);

        List<BusinessParamEntity> paramEntityList = new ArrayList<>();
        Map<String, Integer> finalParamOperation = paramOperation;
        if (variables.containsKey(InstanceVariableParam.BUSINESS_PARAM.getText())) {
            businessParams.keySet().forEach(paramKey -> {
                BusinessParamEntity businessParamEntity = new BusinessParamEntity();
                businessParamEntity.setInstanceId(instanceId);
                businessParamEntity.setDefinitionCode(workflowDefinitionEntity.getInitialCode());
                businessParamEntity.setParamKey(paramKey);
                businessParamEntity.setParamValue(String.valueOf(businessParams.get(paramKey)));
                businessParamEntity.setFindWay(finalParamOperation.get(paramKey));
                paramEntityList.add(businessParamEntity);
            });
            businessParamRepository.saveAll(paramEntityList);
        }

        // ????????????
        OperationLogEntity logEntity = new OperationLogEntity();
        String event = String.valueOf(InstanceEvent.INSTANCE_START.getCode());
        logEntity.setContent("[ " + definitionName + " ] ????????????");
        logEntity.setCreatedBy(userId.intValue());
        logEntity.setLastUpdatedBy(userId.intValue());
        logEntity.setCreatedOn(instanceStartDate);
        logEntity.setLastUpdatedOn(instanceStartDate);
        // ??????????????????id,????????????????????????id(?????????????????????????????????????????????)
        logEntity.setTaskId(event);
        logEntity.setEvent(event);
        logEntity.setDefinitionId(definitionId);
        logEntity.setInstanceId(instanceId);
        logEntity.setShowStatus(LightningApplyStatus.START.getCode());
        operationLogService.log(logEntity);

        // ?????????????????? (????????????)
        List<NoticeTemplateEntity> noticeTemplate = noticeConfigService
                .getNoticeTemplate(NoticeConfigState.DEFINITION,
                        definitionId,
                        InstanceEvent.INSTANCE_START.getCode());
        if (CollectionUtils.isEmpty(noticeTemplate)) {
            return new DefinitionAndInstanceIdVO(definitionId, instanceId, request.getInstanceInfo().getBusinessKey());
        }

        // ???????????? ????????????
        Map<String, Object> finalVariables = variables;
        // ????????????
        noticeTemplate.forEach(template -> {
            MessageInfoVO message = noticeContentUtil.translateDefinitionTemplate(template, workflowDefinitionEntity, instanceId, finalVariables);
            message.setTargets(Collections.singletonList(userId.intValue()));
            message.setNoticeEventType(InstanceEvent.INSTANCE_START.getCode());
            // ?????????????????????????????????????????????
            sendNoticeContext.send(template, message);
        });
        return new DefinitionAndInstanceIdVO(definitionId, instanceId, request.getInstanceInfo().getBusinessKey());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<DefinitionAndInstanceIdVO> lightningBatchStart(BatchStartLightningInstanceRequest request) throws Exception {
        // ????????????????????????
        List<InstanceInfoRequest> startLightningRequestList = request.getInstanceInfoList();
        List<String> businessKeyList = startLightningRequestList.stream()
                .map(InstanceInfoRequest::getBusinessKey).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(businessKeyList)) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "????????????BusinessKey??????");
        }

        // ?????????businessKey???????????????????????????
        List<String> existingKeys = getEffectiveInstanceByBusinessKey(businessKeyList);
        if (CollectionUtils.isNotEmpty(existingKeys)) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), existingKeys + "????????????????????????????????????????????????");
        }

        List<InstanceInfoRequest> lightningInstanceInfoList = request.getInstanceInfoList();
        // ????????????????????????
        StartLightningInstanceRequest singleRequest = new StartLightningInstanceRequest();
        singleRequest.setCreatorId(request.getCreatorId());
        singleRequest.setKey(request.getKey());
        singleRequest.setSourcePlatform(request.getSourcePlatform());
        singleRequest.setSourcePlatformUserId(request.getSourcePlatformUserId());
        singleRequest.setSourcePlatformUserName(request.getSourcePlatformUserName());
        // ????????????
        List<DefinitionAndInstanceIdVO> list = new ArrayList<>();
        // ??????????????????????????????
        lightningInstanceInfoList.forEach(instanceInfo -> {
            singleRequest.setInstanceInfo(instanceInfo);
            DefinitionAndInstanceIdVO definitionAndInstanceId = lightningStart(singleRequest, UserHelper.getUserId().longValue());
            list.add(definitionAndInstanceId);
        });
        return list;
    }

    private WorkflowInstanceEntity buildOwnInstance(ProcessInstance instance,
                                                    WorkflowDefinitionEntity definition,
                                                    StartInstanceExtRequest request,
                                                    Long ownerId,
                                                    Long instanceStartDate) {
        WorkflowInstanceEntity instanceEntity = new WorkflowInstanceEntity();
        instanceEntity.setId(instance.getId());
        instanceEntity.setBusinessKey(instance.getBusinessKey());
        instanceEntity.setName(instance.getName());
        instanceEntity.setDefinitionId(definition.getId());
        instanceEntity.setDefinitionCode(definition.getInitialCode());
        instanceEntity.setDefinitionVersion(definition.getVersion().intValue());
        instanceEntity.setBusinessUrl(definition.getBusinessUrl());
        instanceEntity.setMobileBusinessUrl(definition.getMobileBusinessUrl());
        instanceEntity.setPcBusinessUrl(definition.getPcBusinessUrl());
        if (ownerId != null) {
            instanceEntity.setOwnerId(ownerId);
        } else {
            instanceEntity.setOwnerId(request.getCreatorId());
        }
        instanceEntity.setSourcePlatform(request.getSourcePlatform());
        instanceEntity.setSourcePlatformUserId(request.getSourcePlatformUserId());
        instanceEntity.setSourcePlatformUserName(request.getSourcePlatformUserName());
        instanceEntity.setStatus(InstanceState.RUNNING.getState());
        instanceEntity.setStartTime(new Date(instanceStartDate));
        instanceEntity.setCreatedBy(request.getCreatorId());
        instanceEntity.setCreatedOn(new Date(instanceStartDate));
        instanceEntity.setLastUpdatedBy(request.getCreatorId());
        instanceEntity.setLastUpdatedOn(new Date(instanceStartDate));
        return instanceEntity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String suspend(String instanceId, Long userId) throws Exception {
        WorkflowInstanceEntity entity = workflowInstanceRepository.findTopById(instanceId);
        if (!entity.getStatus().equals(InstanceState.RUNNING.getState())) {
            throw new Exception("????????????????????????????????????????????????");
        }
        runtimeService.suspendProcessInstanceById(entity.getId());
        entity.setStatus(InstanceState.SLEEP.getState());
        entity.setLastUpdatedBy(userId);
        entity.setLastUpdatedOn(new Date());
        workflowInstanceRepository.save(entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String activate(WorkflowInstanceEntity request, Long userId) throws Exception {
        if (!request.getStatus().equals(InstanceState.SLEEP.getState())) {
            throw new Exception("????????????????????????????????????????????????");
        }
        runtimeService.activateProcessInstanceById(request.getId());
        request.setStatus(InstanceState.RUNNING.getState());
        request.setLastUpdatedBy(userId);
        request.setLastUpdatedOn(new Date());
        workflowInstanceRepository.save(request);
        return request.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(DeleteInstanceRequest request, Long userId) {
        String instanceId = request.getInstanceId();
        WorkflowInstanceEntity entity = workflowInstanceRepository.findTopById(instanceId);
        if (entity == null) {
            throw new VerificationFailedException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "??????????????????id???????????????????????????");
        }
        runtimeService.deleteProcessInstance(instanceId, request.getDeleteReason());
        // ????????????????????????
        entity.setStatus(InstanceState.INVALID.getState());
        entity.setDeleteReason(request.getDeleteReason());
        workflowInstanceRepository.save(entity);
        // ??????????????????
        List<TaskEntity> allTasks = taskRepository.findAllByInstanceId(instanceId);
        allTasks.forEach(t -> t.setStatus(TaskState.INVALID.getState()));
        taskRepository.saveAll(allTasks);
        // ????????????????????????????????????
        moveToHistory(instanceId, userId);
    }

    @Override
    public Page<WorkflowInstanceVO> search(SearchInstanceRequest request) throws Exception {
        vaildSearchRequest(request);
        return searchAllInstances(request);
    }

    @Override
    public WorkflowInstanceEntity findInstanceById(String instanceId) {
        return workflowInstanceRepository.findById(instanceId).orElse(null);
    }

    @Override
    public List<TaskVO> getInstanceListByCandidateId() {
        Integer candidateId = UserHelper.getUserId();
        List<TaskVO> instanceList = taskNativeRepository.selectTaskInstanceList(candidateId);
        List<TaskVO> hisInstanceList = taskNativeRepository.selectHisTaskInstanceList(candidateId);
        instanceList.addAll(hisInstanceList);
        return instanceList;
    }

    @Override
    public Page<WorkflowInstanceVO> searchOwn(SearchInstanceRequest request) {
        vaildSearchRequest(request);
        return searchMyInstances(request, 1);
    }

    @Override
    public Page<WorkflowInstanceVO> searchMySubmit(SearchInstanceRequest request) throws Exception {
        vaildSearchRequest(request);
        return searchMyInstances(request, 2);
    }

    @Override
    public List<WorkflowInstanceEntity> getIncompleteInstanceByDefinitionKeyAndTaskStatusIn(String key, List<Integer> taskStatusList) {
        return workflowInstanceRepository.getIncompleteInstanceByDefinitionKeyAndTaskStatusIn(key, taskStatusList);
    }

    @Override
    public List<WorkflowInstanceWithTaskEntity> getIncompleteInstanceWithTaskByDefinitionKeyAndTaskStatusIn(String key, List<Integer> taskStatusList) {
        return workflowInstanceRepository.getIncompleteInstanceWithTaskByDefinitionKeyAndTaskStatusIn(key, taskStatusList);
    }

    @Override
    public WorkflowInstanceVO getInstanceById(String instanceId) {
        if (StringUtils.isBlank(instanceId)) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????ID??????");
        }
        return getInstanceVoById(instanceId);
    }

    @Override
    public WorkflowInstanceVO getInstanceByTaskId(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????ID??????");
        }
        return getInstanceVoByTaskId(taskId);
    }

    @Override
    public InstanceDetailVO getInstanceDetailInfo(String instanceId) throws Exception {
        if (StringUtils.isBlank(instanceId)) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "??????ID????????????");
        }
        InstanceDetailVO instanceDetailVO = new InstanceDetailVO();
        Set<Integer> userIdSet = new HashSet<>();

        // ?????????????????????
        WorkflowInstanceVO instance = getInstanceVoById(instanceId);
        if (instance == null) {
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "?????????????????????????????????");
        }
        // ????????????????????????

        instanceDetailVO.setInstance(instance);

        // ??????????????????
        List<TaskCommentVO> comments = taskCommentService.getCommentByInstanceId(instanceId);
        instanceDetailVO.setComments(comments);

        // ????????????????????????
        WorkflowFormEntity formDefinition = workflowFormRepository.findTopBydefinitionId(instance.getDefinitionId());

        if (formDefinition == null) {
            instanceDetailVO.setForm(null);
            return instanceDetailVO;
        }
        List<TaskVO> tasks = taskRepository.selectAllTaskByInstanceId(instanceId);
        List<String> taskIds = new ArrayList<>();
        tasks.forEach(task -> {
            if (task.getSubmitBy() != null) {
                userIdSet.add(task.getSubmitBy().intValue());
            }
            taskIds.add(task.getId());
        });
        List<OperationLogEntity> operationLogs = operationLogRepository.findAllByInstanceIdAndStatusAndTaskIdNotIn(instanceId, 1, taskIds);
        operationLogs.forEach(log -> {
            if (!String.valueOf(InstanceEvent.INSTANCE_START.getCode()).equals(log.getTaskId())
                    && !String.valueOf(InstanceEvent.INSTANCE_END.getCode()).equals(log.getTaskId())) {
                TaskVO task = new TaskVO();
                // ?????????????????????????????? ????????????ID???-1
                task.setId("-1");
                task.setSubmitBy(log.getCreatedBy() != null ? log.getCreatedBy().longValue() : null);
                task.setCreatedOn(log.getCreatedOn());
                task.setSubmitTime(log.getCreatedOn());
                task.setName(log.getContent());
                task.setStatus(log.getStatus());
                userIdSet.add(log.getCreatedBy());
                tasks.add(task);
            }
        });
        instanceDetailVO.setTasks(tasks);
        JSONObject formContent = JSONObject.parseObject(formDefinition.getFormContent());
        JSONArray list = formContent.getJSONArray(FormContentParam.LIST.getText());
        List<Map<String, Object>> formDataList = formDataUtil.getFormDefinition(list);
        if (formDataList.isEmpty()) {
            instanceDetailVO.setForm(null);
            return instanceDetailVO;
        }
        // ????????????data
        formDataUtil.setFormData(formDataList, instance);
        // ??????????????????
        Form form = new Form();
        form.setDefinition(formDefinition.getFormContent());
        form.setData(formDataUtil.getDataCode(formDataList));
        instanceDetailVO.setForm(form);
        userIdSet.add(instance.getCreatedBy().intValue());
        // ??????feign????????????
        List<PassportUserInfoDTO> userList = passportFeignManager.getUserInfoListFromRedis(userIdSet);
        if (CollectionUtils.isNotEmpty(userList)) {
            tasks.forEach(task -> {
                if (task.getSubmitBy() == null) {
                    return;
                }
                userList.stream()
                        .filter(u -> task.getSubmitBy() == u.getId().longValue())
                        .findFirst().ifPresent(user -> task.setSubmitter(user.getNickname()));
            });
            // ???????????????????????????
            userList.stream()
                    .filter(u -> instance.getCreatedBy() == u.getId().longValue())
                    .findFirst().ifPresent(user -> instance.setCreator(user.getNickname()));
        }
        tasks.sort(Comparator.comparing(TaskVO::getCreatedOn));
        return instanceDetailVO;
    }

    @Override
    public List<PurchaseOrderStatusVO> getPurchaseProcess(String businessKey) {
        WorkflowInstanceEntity instanceEntity = workflowInstanceRepository.findTopByBusinessKey(businessKey);
        if (instanceEntity == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????");
        }
        List<PurchaseOrderStatusVO> vos = new ArrayList<>();
        List<OperationLogEntity> entities = operationLogRepository.findByInstanceIdAndStatus(instanceEntity.getId(), 1);
        if (entities == null) {
            throw new VerificationFailedException(ResponseCode.INTERNAL_ERROR.getCode(), "?????????????????????????????????");
        }
        for (OperationLogEntity entity : entities) {
            PurchaseOrderStatusVO vo = new PurchaseOrderStatusVO();
            vo.setProcess(entity.getContent());
            vo.setCreatedOn(entity.getCreatedOn());
            vos.add(vo);
        }
        return vos;
    }

    @Override
    public List<String> getEffectiveInstanceByBusinessKey(List<String> businessKeys) {
        List<WorkflowHistoryEntity> hisInstances = workflowHistoryRepository.findAllByBusinessKeyInAndStatusNot(businessKeys, InstanceState.INVALID.getState());
        List<WorkflowInstanceEntity> instances = workflowInstanceRepository.findAllByBusinessKeyInAndStatusNot(businessKeys, InstanceState.INVALID.getState());
        List<String> existingKeys = hisInstances.stream().distinct().map(WorkflowHistoryEntity::getBusinessKey).collect(Collectors.toList());
        existingKeys.addAll(instances.stream().distinct().map(WorkflowInstanceEntity::getBusinessKey).collect(Collectors.toList()));
        return existingKeys;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeInstanceById(String instanceId) {
        Integer userId = UserHelper.getUserId();
        // ?????????????????????????????????
        WorkflowInstanceEntity instance = workflowInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "??????????????????????????????"));
        Long createdBy = instance.getCreatedBy();
        if (userId != createdBy.intValue()) {
            throw new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "??????????????????????????????????????????");
        }
        instance.setStatus(InstanceState.INVALID.getState());
        workflowInstanceRepository.save(instance);
        moveToHistory(instance, userId.longValue());
        // ???????????????
        List<TaskEntity> allTask = taskRepository.findAllByInstanceId(instanceId);
        allTask.forEach(t -> t.setStatus(TaskState.INVALID.getState()));
        taskRepository.saveAll(allTask);
        // ??????activity??????
        runtimeService.deleteProcessInstance(instanceId, null);
        // ?????????????????????
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                List<NoticeTemplateEntity> noticeTemplate = noticeConfigService
                        .getNoticeTemplate(NoticeConfigState.DEFINITION,
                                instance.getDefinitionId(),
                                InstanceEvent.INSTANCE_REVOKE.getCode());
                if (CollectionUtils.isEmpty(noticeTemplate)) {
                    return;
                }
                // ????????????
                WorkflowDefinitionEntity definition = definitionRepository.findByInstanceId(instanceId);
                // ????????????
                Map<String, Object> variables = runtimeService.getVariables(instanceId);
                // ???????????????
                allTask.sort(Comparator.comparing(TaskEntity::getCreatedOn).reversed());
                String lastTaskUser = allTask.get(0).getCandidateUsers();
                List<Integer> target = Stream.of(StringUtils.split(lastTaskUser, Symbol.COMMA.getValue()))
                        .map(Integer::valueOf).collect(Collectors.toList());
                // ??????????????????
                noticeTemplate.forEach(template -> {
                    MessageInfoVO message = noticeContentUtil.translateDefinitionTemplate(template, definition, instanceId, variables);
                    message.setTargets(target);
                    message.setNoticeEventType(InstanceEvent.INSTANCE_REVOKE.getCode());
                    // ?????????????????????????????????????????????
                    sendNoticeContext.send(template, message);
                });
            }
        });
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> revokeLightningInstanceById(List<RevokeRequest> revokeRequestList) {
        Integer userId = UserHelper.getUserId();
        if (userId == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????ID??????");
        }
        List<String> instanceIds = new ArrayList<>();
        Map<String, RevokeRequest> revokeMap = new HashMap<>(16);
        for (RevokeRequest req : revokeRequestList) {
            String instanceId = req.getInstanceId();
            if (StringUtils.isNotBlank(instanceId)) {
                revokeMap.put(instanceId, req);
                instanceIds.add(instanceId);
            }
        }
        List<String> cannotRevokeIds = new ArrayList<>();
        // ??????????????????
        List<WorkflowHistoryEntity> hisInstances = workflowHistoryRepository.findAllByIdIn(instanceIds);
        if (CollectionUtils.isNotEmpty(hisInstances)) {
            cannotRevokeIds.addAll(hisInstances.stream().map(WorkflowHistoryEntity::getId).collect(Collectors.toSet()));
        }
        // ?????????????????????
        List<String> runningInstanceIds = instanceIds.stream().filter(id -> !cannotRevokeIds.contains(id))
                .distinct().collect(Collectors.toList());
        if (CollectionUtils.isEmpty(runningInstanceIds)) {
            return cannotRevokeIds;
        }
        // ??????????????????
        List<WorkflowInstanceEntity> runningInstance = workflowInstanceRepository.findAllByIdInAndCreatedBy(runningInstanceIds, userId.longValue());
        if (CollectionUtils.isEmpty(runningInstance)) {
            cannotRevokeIds.addAll(runningInstanceIds);
            return cannotRevokeIds;
        }
        if (runningInstanceIds.size() > runningInstance.size()) {
            List<String> hasInfoInstanceIds = runningInstance.stream()
                    .map(WorkflowInstanceEntity::getId)
                    .collect(Collectors.toList());
            cannotRevokeIds.addAll(runningInstanceIds.stream()
                    .filter(id -> !hasInfoInstanceIds.contains(id))
                    .collect(Collectors.toSet()));
        }
        // ????????????????????????
        List<WorkflowDefinitionEntity> definitions = definitionRepository.findAllByIdIn(runningInstance.stream()
                .map(WorkflowInstanceEntity::getDefinitionId).collect(Collectors.toList()));
        // ?????????????????????
        workflowInstanceRepository.updateInstanceStatusById(runningInstanceIds, InstanceState.INVALID.getState());
        taskRepository.updateRevokeStatus(runningInstanceIds, TaskState.INVALID.getState());
        // ??????????????????????????????????????????
        Map<String, WorkflowDefinitionEntity> definitionMap = new HashMap<>(16);
        definitions.forEach(definition -> definitionMap.put(definition.getId(), definition));
        for (WorkflowInstanceEntity instance : runningInstance) {
            String instanceId = instance.getId();
            WorkflowDefinitionEntity definition = definitionMap.get(instance.getDefinitionId());
            List<NoticeTemplateEntity> noticeTemplate = noticeConfigService
                    .getNoticeTemplate(NoticeConfigState.DEFINITION,
                            definition.getId(),
                            InstanceEvent.INSTANCE_REVOKE.getCode());
            if (CollectionUtils.isEmpty(noticeTemplate)) {
                continue;
            }
            Map<String, Object> variables = runtimeService.getVariables(instanceId);
            RevokeRequest req = revokeMap.get(instanceId);
            if (req != null) {
                variables.put(InstanceVariableParam.REVOCATION_REASON.getText(), req.getRevokeReason());
            }
            Integer receiverId = Integer.valueOf(String.valueOf(variables.get(InstanceVariableParam.RECEIVER.getText())));
            noticeTemplate.forEach(template -> {
                MessageInfoVO message = noticeContentUtil.translateDefinitionTemplate(template, definition, instanceId, variables);
                message.setTargets(Collections.singletonList(receiverId));
                message.setNoticeEventType(InstanceEvent.INSTANCE_REVOKE.getCode());
                // ?????????????????????????????????????????????
                sendNoticeContext.send(template, message);
            });
            OperationLogEntity logEntity = new OperationLogEntity();
            logEntity.setInstanceId(instanceId);
            logEntity.setEvent(String.valueOf(InstanceEvent.INSTANCE_REVOKE.getCode()));
            logEntity.setContent("[ " + instanceId + " ] " + "????????????");
            logEntity.setCreatedOn(new Date());
            logEntity.setCreatedBy(userId);
            logEntity.setLastUpdatedOn(new Date());
            logEntity.setLastUpdatedBy(userId);
            operationLogService.log(logEntity);
            runtimeService.deleteProcessInstance(instanceId, null);
        }
        return cannotRevokeIds;
    }

    @Override
    public List<WorkflowInstanceEntity> getSelfEffectiveInstanceByBusinessKey(List<String> businessKeys, Long userId) {
        return workflowInstanceRepository.findAllByBusinessKeyInAndStatusNotAndCreatedBy(businessKeys, InstanceState.INVALID.getState(), userId);
    }

    @Override
    public List<WorkflowInstanceEntity> getSelfEffectiveInstanceById(List<String> instanceIds, Long userId) {
        return workflowInstanceRepository.findAllByIdInAndStatusNotAndCreatedBy(instanceIds,
                InstanceState.INVALID.getState(), userId);
    }

    private void vaildSearchRequest(SearchInstanceRequest request) {
        ValidResult validResult = ValidUtil.validFields(request,
                new String[]{"name", "definitionId", "status", "pageIndex", "pageSize"},
                new Object[][]{
                        {new ValidUtil.StringLength(0, 30)},
                        {(ValidPlugin) (field, value) -> {
                            ValidResult v = new ValidResult();
                            if (StringUtils.isBlank(value) || definitionRepository.existsById(value)) {
                                v.valid = true;
                            } else {
                                v.message = "?????????id=" + value + "???????????????";
                                v.valid = false;
                            }
                            return v;
                        }},
                        {ValidUtil.NON_NEGATIVE_INTEGER},
                        {ValidUtil.REQUIRED, ValidUtil.NON_NEGATIVE_INTEGER},
                        {ValidUtil.REQUIRED, ValidUtil.NON_NEGATIVE_INTEGER},
                });
        if (!validResult.valid) {
            throw new VerificationFailedException(400, validResult.message);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowHistoryEntity moveToHistory(String id, Long userId) {
        WorkflowInstanceEntity workflowInstance = workflowInstanceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("??????instance?????????id:" + id));
        workflowInstance.setLastUpdatedBy(userId);
        workflowInstance.setLastUpdatedOn(new Date());
        WorkflowHistoryEntity historyEntity = new WorkflowHistoryEntity();
        ObjectUtil.extendObject(historyEntity, workflowInstance, true);
        workflowHistoryRepository.save(historyEntity);
        workflowInstanceRepository.deleteById(workflowInstance.getId());
        return historyEntity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowHistoryEntity moveToHistory(WorkflowInstanceEntity instance, Long userId) {
        instance.setLastUpdatedBy(userId);
        instance.setLastUpdatedOn(new Date());
        WorkflowHistoryEntity historyEntity = new WorkflowHistoryEntity();
        ObjectUtil.extendObject(historyEntity, instance, true);
        workflowHistoryRepository.save(historyEntity);
        workflowInstanceRepository.deleteById(instance.getId());
        return historyEntity;
    }

    private WorkflowInstanceVO getInstanceVoById(String instanceId) {
        WorkflowInstanceVO instance = workflowInstanceRepository.selectInstanceById(instanceId);
        if (instance == null) {
            WorkflowInstanceVO hisInstance = workflowHistoryRepository.selectInstanceById(instanceId);
            if (hisInstance == null) {
                return null;
            }
            hisInstance.setHistory(true);
            return hisInstance;
        }
        instance.setHistory(false);
        return instance;
    }

    private WorkflowInstanceVO getInstanceVoByTaskId(String taskId) {
        WorkflowInstanceVO instance = workflowInstanceRepository.selectInstanceVOByTaskId(taskId);
        if (instance == null) {
            WorkflowInstanceVO hisInstance = workflowHistoryRepository.selectInstanceVOByTaskId(taskId);
            if (hisInstance == null) {
                return null;
            }
            hisInstance.setHistory(true);
            return hisInstance;
        }
        instance.setHistory(false);
        return instance;
    }

    private Page<WorkflowInstanceVO> searchAllInstances(SearchInstanceRequest request) {
        Set<Integer> ids = new HashSet<>();
        Pageable pageable = PageRequest.of(request.getPageIndex(), request.getPageSize());
        Page<WorkflowInstanceVO> instances;
        if (request.getHistory() == 1) {
            instances = workflowInstanceRepository.selectInstances(request, pageable);
            instances.getContent().forEach(instance -> {
                instance.setHistory(false);
                ids.add(instance.getOwnerId().intValue());
            });
        } else {
            instances = workflowHistoryRepository.selectInstances(request, pageable);
            instances.getContent().forEach(instance -> {
                instance.setHistory(true);
                ids.add(instance.getOwnerId().intValue());
            });
        }
        List<PassportUserInfoDTO> userList = passportFeignManager.getUserInfoListFromRedis(ids);
        if (CollectionUtils.isNotEmpty(userList)) {
            instances.getContent().forEach(i ->
                    userList.stream()
                            .filter(u -> u.getId().longValue() == i.getOwnerId()).findFirst()
                            .ifPresent(user -> i.setOwnerName(user.getNickname()))
            );
        }
        return instances;
    }

    private Page<WorkflowInstanceVO> searchMyInstances(SearchInstanceRequest request, Integer type) {
        Integer runInstanceState = 1, ownState = 1, createState = 2;
        Pageable pageable = PageRequest.of(request.getPageIndex(), request.getPageSize());
        Long userId = UserHelper.getUserId().longValue();
        Page<WorkflowInstanceVO> instances = null;
        Set<Integer> ids = new HashSet<>();
        if (request.getHistory().equals(runInstanceState)) {
            if (type.equals(ownState)) {
                instances = workflowInstanceRepository.selectOwnerInstances(request, userId, pageable);
            } else if (type.equals(createState)) {
                instances = workflowInstanceRepository.selectCreateInstances(request, userId, pageable);
            }
            if (instances != null) {
                instances.getContent().forEach(instance -> {
                    instance.setHistory(false);
                    ids.add(instance.getCreatedBy().intValue());
                    ids.add(instance.getOwnerId().intValue());
                });
            }
        } else {
            if (type.equals(ownState)) {
                instances = workflowHistoryRepository.selectOwnerInstances(request, userId, pageable);
            } else if (type.equals(createState)) {
                instances = workflowHistoryRepository.selectCreateInstances(request, userId, pageable);
            }
            if (instances != null) {
                instances.getContent().forEach(instance -> {
                    instance.setHistory(true);
                    ids.add(instance.getCreatedBy().intValue());
                    ids.add(instance.getOwnerId().intValue());
                });
            }
        }

        List<PassportUserInfoDTO> userList = passportFeignManager.getUserInfoListFromRedis(ids);
        if (CollectionUtils.isNotEmpty(userList)) {
            Map<String, String> nameMap = new HashMap<>(16);
            userList.forEach(item ->
                    nameMap.put(item.getId().toString(), item.getNickname())
            );
            instances.getContent().forEach(instance ->
                    userList.forEach(user -> {
                        if (instance.getOwnerId() != null) {
                            instance.setOwnerName(nameMap.get(instance.getOwnerId().toString()));
                        }
                        if (instance.getCreatedBy() != null) {
                            instance.setCreator(nameMap.get(instance.getCreatedBy().toString()));
                        }
                    })
            );
        }
        return instances;
    }

    @Override
    public void revokeIssueInstanceById(String signalName, String executionId) {
        runtimeService.signalEventReceived(signalName);
        //??????????????????   ???????????????????????????????????????
        List<Execution> executions = runtimeService.createExecutionQuery()
                .signalEventSubscriptionName(signalName)
                .list();
        for (Execution execution : executions
        ) {
            System.out.println(execution.getName() + "***" + execution.getId());
        }
    }

    @Override
    public void sendConfirmIsSolveSignal(ConfirmSolveSignalRequest confirmSolveSignalRequest) {
        String instanceId = confirmSolveSignalRequest.getInstanceId();
        Map<String, Object> valueMap = confirmSolveSignalRequest.getVariables();
        if (!valueMap.containsKey(WorkflowStatusFlag.TASK_STATUS.getName())) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????");
        }
        String signalName = "confirmSolve";
        List<Execution> executionsExistEvent = runtimeService.createExecutionQuery().processInstanceId(instanceId).signalEventSubscriptionName(signalName).list();
        if (CollectionUtils.isEmpty(executionsExistEvent)) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????ID????????????????????????????????????ID??????");
        }
        try {
            // ??????????????? ?????????????????????
            executionsExistEvent.forEach(execution -> {
                runtimeService.setVariables(execution.getId(), valueMap);
            });
            // ???????????????????????????
            executionsExistEvent.forEach(execution -> {
                runtimeService.signalEventReceived(signalName, execution.getId());
            });
        } catch (Exception e) {
            log.error("{}", e);
            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "????????????,???????????????");
        }
    }

//    private void updateConfirmCard(String instanceId, Integer taskStatus) {
//        // ?????????????????????????????????
//        // ??????????????????
//        WorkflowDefinitionEntity definition = definitionRepository.findByInstanceId(instanceId);
//        if (definition == null) {
//            log.error("??????????????????????????????????????????instanceId:{}", instanceId);
//            return;
//        }
//        Map<String, Object> variables = runtimeService.getVariables(instanceId);
//        String uuid = (String) variables.get(InstanceVariableParam.CONFIRM_NOTICE_FLAGE.getText());
//        Long creatorId = (Long) variables.get(InstanceVariableParam.INSTANCE_CREATOR_ID.getText());
//        String nodeId = definition.getId() + Symbol.COLON.getValue() + "ServiceTask_1841drq";
//        List<NoticeTemplateEntity> noticeTemplates = noticeConfigService.getNoticeTemplate(NoticeConfigState.NODE,
//                nodeId, InstanceEvent.SERVER_TASK.getCode());
//        // ????????????????????????
//        noticeTemplates.stream().filter(template -> template.getChannel() == NoticeType.WEIXIN_CHANNEL.getState()
//                && template.getType() == NoticeType.TASK_CARD.getState())
//                .findFirst().ifPresent(t -> {
//            String buttonConfig = t.getButtonConfig();
//            if (StringUtils.isBlank(buttonConfig)) {
//                log.error("?????????????????????????????????");
//                return;
//            }
//            ServerResponse<List<PassportUserInfoDTO>> userInfoResponse = passportFeignClient.getUserMsgByIds(Collections.singleton(creatorId.intValue()));
//            List<PassportUserInfoDTO> userInfoList = userInfoResponse.getData();
//            if (userInfoResponse.getCode() != ResponseCode.SUCCESS.getCode() || CollectionUtils.isEmpty(userInfoList)) {
//                return;
//            }
//            String userId = userInfoList.get(0).getWxWorkUserId();
//            UpdateStatusMsg msg = new UpdateStatusMsg();
//            msg.setUserIds(Collections.singletonList(userId));
//            msg.setAgentId(Integer.valueOf(agentId));
//            NoticeEntity confirmNotice = noticeLogService.getNoticeByInstanceIdAndTaskIdAndType(instanceId, uuid, InstanceEvent.SERVER_TASK.getCode(), creatorId.toString());
//            if (confirmNotice == null) {
//                return;
//            }
//            msg.setTaskId(confirmNotice.getTaskIdWeixin());
//            String btnKey = getClickBtnKey(buttonConfig, taskStatus);
//            if (StringUtils.isBlank(btnKey)) {
//                return;
//            }
//            msg.setClickedKey(btnKey);
//            ServerResponse serverResponse = weixinFeignClient.sendUpdateStatusMsg(msg);
//            log.info(JsonUtil.toJsonString(serverResponse));
//        });
//    }

    @Override
    public void sendSignal(String signalName, String executionId) {
        runtimeService.signalEventReceived(signalName, executionId);
    }

    /**
     * ????????????????????????businessKey
     */
    @Override
    public void urgeCurrentTaskByBusinessKey(String definitionKey, String businessKey) {
        TaskEntity currentTask = taskRepository.findCurrentTaskByDefinitionKeyAndBusinessKey(definitionKey, businessKey);
        if (currentTask == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(),
                    "??????????????????????????????????????????????????????");
        }
        urgeTask(currentTask, InstanceEvent.URGE.getCode());
    }

    @Override
    public void urgeTask(TaskEntity currentTask, Integer type) {
        String candidateUser = currentTask.getCandidateUsers();
        if (StringUtils.isBlank(candidateUser)) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(),
                    "???????????????????????????????????????????????????????????????????????????????????????");
        }
        List<NoticeTemplateEntity> noticeTemplates = noticeConfigService.getNoticeTemplate(NoticeConfigState.NODE,
                currentTask.getNodeId(), type);
        if (CollectionUtils.isEmpty(noticeTemplates)) {
            throw new VerificationFailedException(ResponseCode.INTERNAL_ERROR.getCode(),
                    "?????????????????????????????????????????????");
        }
        String instanceId = currentTask.getInstanceId();
        // ??????????????????
        WorkflowDefinitionEntity definition = definitionRepository.findByInstanceId(currentTask.getInstanceId());
        BaseTaskInfoDTO baseTaskInfoDTO = ConvertClassUtil.convertToBaseTaskInfoDTO(currentTask);
        // ????????????
        Map<String, Object> variables = runtimeService.getVariables(instanceId);
        noticeTemplates.forEach(template -> {
            MessageInfoVO message = noticeContentUtil.translateNodeTemplate(template, definition, baseTaskInfoDTO, variables);
            message.setTargets(Collections.singletonList(Integer.valueOf(candidateUser)));
            message.setNoticeEventType(type);
            sendNoticeContext.send(template, message);
        });
    }
}
