package com.ruigu.rbox.workflow.controller.timer;

import com.ruigu.rbox.cloud.kanai.util.TimeUtil;
import com.ruigu.rbox.workflow.feign.ScmFeignClient;
import com.ruigu.rbox.workflow.model.ServerResponse;
import com.ruigu.rbox.workflow.model.dto.StockChangeSkuNotEmptyDTO;
import com.ruigu.rbox.workflow.model.entity.*;
import com.ruigu.rbox.workflow.model.enums.NoticeConfigState;
import com.ruigu.rbox.workflow.model.enums.InstanceEvent;
import com.ruigu.rbox.workflow.model.enums.ResponseCode;
import com.ruigu.rbox.workflow.model.enums.StorageEnum;
import com.ruigu.rbox.workflow.model.request.StockChangeLastApplyRequest;
import com.ruigu.rbox.workflow.model.vo.MessageInfoVO;
import com.ruigu.rbox.workflow.model.vo.WorkflowInstanceVO;
import com.ruigu.rbox.workflow.repository.TaskRepository;
import com.ruigu.rbox.workflow.repository.WorkflowDefinitionRepository;
import com.ruigu.rbox.workflow.service.NoticeLogService;
import com.ruigu.rbox.workflow.service.NoticeConfigService;
import com.ruigu.rbox.workflow.service.QuestNoticeService;
import com.ruigu.rbox.workflow.service.WorkflowInstanceService;
import com.ruigu.rbox.workflow.strategy.context.SendNoticeContext;
import com.ruigu.rbox.workflow.supports.NoticeContentUtil;
import lombok.extern.slf4j.Slf4j;
import org.activiti.engine.ProcessEngine;
import org.activiti.engine.history.HistoricVariableInstance;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author liqingtian
 * @date 2019/11/03 16:07
 */
@Slf4j
@RestController
@RequestMapping("/stock")
public class StockTimerController {

    @Resource
    private NoticeContentUtil noticeContentUtil;

    @Resource
    private NoticeLogService noticeLogService;

    @Resource
    private SendNoticeContext sendNoticeContext;

    @Resource
    private NoticeConfigService noticeConfigService;

    @Resource
    private ScmFeignClient scmFeignClient;

    @Resource
    private WorkflowInstanceService workflowInstanceService;

    @Resource
    private WorkflowDefinitionRepository workflowDefinitionRepository;

    @Resource
    private ProcessEngine processEngine;

    @Resource
    private TaskRepository taskRepository;

    @Resource
    private QuestNoticeService questNoticeService;

    @Value("${rbox.workflow.definition.stock}")
    private String stockDefinitionKey;

    @GetMapping("/not/use/timeout")
    public ServerResponse notUseTimeout() {
        // ????????????????????????
        ServerResponse<List<StockChangeSkuNotEmptyDTO>> skuRecordResponse = scmFeignClient.getThisMonthSkuNotEmpty();
        if (skuRecordResponse.getCode() != ResponseCode.SUCCESS.getCode()) {
            return ServerResponse.fail(ResponseCode.INTERNAL_ERROR.getCode(), "????????????????????????????????????");
        }
        List<StockChangeSkuNotEmptyDTO> skuRecord = skuRecordResponse.getData();
        if (CollectionUtils.isEmpty(skuRecord)) {
            return ServerResponse.ok();
        }
        List<StockChangeLastApplyRequest> requests = new ArrayList<>();
        skuRecord.forEach(r -> {
            StockChangeLastApplyRequest request = new StockChangeLastApplyRequest();
            request.setSkuCode(r.getSkuCode());
            request.setStorageId(r.getStorageId());
            requests.add(request);
        });
        // ??????????????????????????????
        ServerResponse<List<StockChangeApplyEntity>> lastAddRecordsResponse = scmFeignClient.batchGetLastRecords(requests);
        List<StockChangeApplyEntity> lastAddRecords = lastAddRecordsResponse.getData();
        if (lastAddRecordsResponse.getCode() != ResponseCode.SUCCESS.getCode() || CollectionUtils.isEmpty(lastAddRecords)) {
            return ServerResponse.fail(ResponseCode.INTERNAL_ERROR.getCode(), "?????????????????????????????????????????????????????????");
        }
        LocalDateTime now = LocalDateTime.now();
        Set<Integer> leaders = new HashSet<>();
        StringBuilder leaderNoticeContent = new StringBuilder();
        for (StockChangeSkuNotEmptyDTO sku : skuRecord) {
            Integer skuCode = sku.getSkuCode();
            Integer skuSurplusCount = sku.getCount();
            Integer storageId = sku.getStorageId();
            StockChangeApplyEntity lastAddRecord = lastAddRecords.stream()
                    .filter(r -> skuCode.equals(r.getSkuCode()) && storageId.equals(r.getStorageId()))
                    .findFirst().orElse(null);
            if (lastAddRecord == null) {
                log.error("| - 48??????????????????????????????????????????????????????:" + skuCode + " ??????????????????????????????");
                continue;
            }
            if (StringUtils.isBlank(lastAddRecord.getDefinitionId()) || StringUtils.isBlank(lastAddRecord.getInstanceId())) {
                log.debug("| - 48???????????????????????????????????????????????????????????????????????????????????????11???5??????????????????,??????????????????????????????");
                continue;
            }
            WorkflowDefinitionEntity definition = workflowDefinitionRepository
                    .findById(lastAddRecord.getDefinitionId())
                    .orElse(null);
            if (definition == null) {
                log.error("| - 48??????????????????????????????????????????????????????:" + skuCode + " ?????????????????????????????????????????????");
                continue;
            }
            List<NoticeTemplateEntity> noticeTemplate = noticeConfigService.getNoticeTemplate(
                    NoticeConfigState.DEFINITION,
                    definition.getId(),
                    InstanceEvent.TIME_OUT.getCode());
            if (CollectionUtils.isEmpty(noticeTemplate)) {
                continue;
            }
            LocalDateTime actualTime = TimeUtil.date2LocalDateTime(lastAddRecord.getActualTime());
            // ??????48?????? ??????????????????
            if (actualTime.plusDays(2).isBefore(now)) {
                // ???????????????????????????
                WorkflowInstanceVO instance = workflowInstanceService.getInstanceById(lastAddRecord.getInstanceId());
                if (instance == null) {
                    log.error("| - 48?????????????????????????????????????????? ??????ID:" + lastAddRecord.getId() + " ????????????????????????");
                    continue;
                }
                // ???????????????
                Set<Integer> targets;
                if (CollectionUtils.isEmpty(leaders)) {
                    List<TaskEntity> tasks = taskRepository.findAllByInstanceId(instance.getId());
                    List<List<String>> taskUserList = tasks.stream()
                            .map(t -> Arrays.asList(t.getCandidateUsers().split(",")))
                            .collect(Collectors.toList());
                    int[] users = taskUserList.stream()
                            .flatMapToInt(childList -> childList.stream().mapToInt(Integer::new)).toArray();
                    targets = new HashSet<>(Arrays.asList(ArrayUtils.toObject(users)));
                    leaders = targets;
                }
                Duration duration = Duration.between(actualTime, now);
                Long timeoutHours = duration.toHours();
                // ??????????????????
                // ?????????????????????????????????
                List<NoticeEntity> timeoutNotice = noticeLogService.getLastTimeoutNoticeByInstanceId(instance.getId());
                if (CollectionUtils.isNotEmpty(timeoutNotice)) {
                    NoticeEntity lastTimeoutNotice = timeoutNotice.get(0);
                    // ??????????????? ????????????????????????
                    LocalDateTime createdOn = TimeUtil.date2LocalDateTime(lastTimeoutNotice.getCreatedOn());
                    if (createdOn.plusHours(4).isBefore(now)) {
                        // ??????????????????
                        send(noticeTemplate, definition, instance,
                                Collections.singletonList(lastAddRecord.getCreatedBy()),
                                leaderNoticeContent, skuCode, skuSurplusCount, storageId, timeoutHours);
                    }
                } else {
                    // ??????????????? ????????????????????????
                    send(noticeTemplate, definition, instance,
                            Collections.singletonList(lastAddRecord.getCreatedBy()),
                            leaderNoticeContent, skuCode, skuSurplusCount, storageId, timeoutHours);
                }
            }
        }
        // ????????????
        sendNoticeToLeader(leaderNoticeContent, leaders);
        return ServerResponse.ok();
    }

    private void sendNoticeToLeader(StringBuilder leaderNoticeContent, Collection<Integer> leaders) {
        int timeInterval = 12;
        if (leaderNoticeContent.length() == 0) {
            return;
        }
        List<NoticeEntity> leaderNotice = noticeLogService.getStockChangeTimeoutLeaderNotice(stockDefinitionKey);
        if (CollectionUtils.isNotEmpty(leaderNotice)) {
            LocalDateTime lastTime = TimeUtil.date2LocalDateTime(leaderNotice.get(0).getCreatedOn());
            // ????????????12????????????
            if (lastTime.plusHours(timeInterval).isBefore(LocalDateTime.now())) {
                leaderNoticeContent.append("?????????48??????????????????????????????");
                questNoticeService.sendWeiXinTextNotice(null, leaderNoticeContent.toString(), leaders);
            }
        } else {
            leaderNoticeContent.append("?????????48??????????????????????????????");
            questNoticeService.sendWeiXinTextNotice(null, leaderNoticeContent.toString(), leaders);
        }
    }

    @GetMapping("/change/result/notice")
    public ServerResponse changeResultNotice() {
        List<NoticeEntity> unsentNotice = noticeLogService.getUnsentNotice(stockDefinitionKey, InstanceEvent.SERVER_TASK.getCode());
        if (CollectionUtils.isEmpty(unsentNotice)) {
            return ServerResponse.ok();
        }
        // ??????????????????
        Set<String> targetSet = unsentNotice.stream().map(NoticeEntity::getTargets).collect(Collectors.toSet());
        if (CollectionUtils.isEmpty(targetSet)) {
            return ServerResponse.ok();
        }
        for (String target : targetSet) {
            int count = 0;
            List<NoticeEntity> noticeList = unsentNotice.stream()
                    .filter(n -> StringUtils.isNotBlank(n.getTargets()) && n.getTargets().equals(target))
                    .collect(Collectors.toList());
            Set<Integer> targets = new HashSet<>();
            // ???????????????
            String[] userIds = target.split(",");
            for (String id : userIds) {
                targets.add(Integer.valueOf(id));
            }
            // ??????????????????????????????????????????
            List<NoticeEntity> hasBeenSent = new ArrayList<>();
            StringBuilder allContent = new StringBuilder();
            for (NoticeEntity notice : noticeList) {
                // ????????????
                allContent.append(notice.getContent()).append("\n\n");
                notice.setStatus((byte) 1);
                hasBeenSent.add(notice);
                count++;
                if (count % 10 == 0) {
                    try {
                        questNoticeService.sendWeiXinTextNotice(null, allContent.toString().trim(), targets);
                    } finally {
                        // ??????????????? ??????????????????
                        noticeLogService.batchUpdate(hasBeenSent);
                        allContent = new StringBuilder();
                        hasBeenSent = new ArrayList<>();
                    }
                }
            }
            if (CollectionUtils.isNotEmpty(hasBeenSent)) {
                try {
                    questNoticeService.sendWeiXinTextNotice(null, allContent.toString().trim(), targets);
                } finally {
                    // ??????????????? ??????????????????
                    noticeLogService.batchUpdate(hasBeenSent);
                }
            }
        }
        return ServerResponse.ok();
    }

    private void send(List<NoticeTemplateEntity> noticeTemplate,
                      WorkflowDefinitionEntity definition,
                      WorkflowInstanceVO instance,
                      Collection<Integer> targets,
                      StringBuilder leaderNoticeContent,
                      Integer skuCode,
                      Integer skuSurplusCount,
                      Integer storageId,
                      Long hours) {

        List<HistoricVariableInstance> list = processEngine.getHistoryService()
                .createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId()).list();
        Map<String, Object> varMap = new HashMap<>(16);
        if (CollectionUtils.isNotEmpty(list)) {
            for (HistoricVariableInstance var : list) {
                varMap.put(var.getVariableName(), var.getValue());
            }
        } else {
            varMap = processEngine.getRuntimeService().getVariables(instance.getId());
        }
        varMap.put("skuCode", skuCode);
        varMap.put("storageId", storageId);
        varMap.put("skuSurplusCount", skuSurplusCount);
        varMap.put("timeoutHours", hours);
        Map<String, Object> finalParams = varMap;

        String instanceId = instance.getId();
        noticeTemplate.forEach(template -> {
            MessageInfoVO message = noticeContentUtil.translateDefinitionTemplate(template, definition, instanceId, finalParams);
            message.setTargets(targets);
            message.setNoticeEventType(InstanceEvent.TIME_OUT.getCode());
            sendNoticeContext.send(template, message);
        });

        leaderNoticeContent.append("???????????????").append(skuCode).append("\n");
        leaderNoticeContent.append("???????????????").append(StorageEnum.getValueByCode(storageId)).append("\n\n");
    }
}
