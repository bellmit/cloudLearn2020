package com.ruigu.rbox.workflow.service.timer;

import com.alibaba.fastjson.JSONObject;
import com.ruigu.rbox.cloud.kanai.util.TimeUtil;
import com.ruigu.rbox.workflow.feign.PassportFeignClient;
import com.ruigu.rbox.workflow.model.ServerResponse;
import com.ruigu.rbox.workflow.model.dto.BaseTaskInfoDTO;
import com.ruigu.rbox.workflow.model.entity.*;
import com.ruigu.rbox.workflow.model.enums.NoticeConfigState;
import com.ruigu.rbox.workflow.model.enums.InstanceEvent;
import com.ruigu.rbox.workflow.model.enums.ResponseCode;
import com.ruigu.rbox.workflow.model.enums.TimeoutParam;
import com.ruigu.rbox.workflow.model.vo.MessageInfoVO;
import com.ruigu.rbox.workflow.model.vo.TaskVO;
import com.ruigu.rbox.workflow.model.vo.UserGroupLeaderVO;
import com.ruigu.rbox.workflow.model.vo.WorkflowInstanceVO;
import com.ruigu.rbox.workflow.repository.WorkDayRepository;
import com.ruigu.rbox.workflow.repository.WorkflowDefinitionRepository;
import com.ruigu.rbox.workflow.service.*;
import com.ruigu.rbox.workflow.strategy.context.SendNoticeContext;
import com.ruigu.rbox.workflow.supports.ConvertClassUtil;
import com.ruigu.rbox.workflow.supports.NoticeContentUtil;
import lombok.extern.slf4j.Slf4j;
import org.activiti.engine.TaskService;
import org.activiti.engine.task.Task;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * @author liqingtian
 * @date 2019/08/21 21:11
 */

@Slf4j
@Component
@Configuration
public class TimeoutNoticeTimer {

    @Autowired
    private WorkflowTaskService workflowTaskService;

    @Autowired
    private WorkflowInstanceService workflowInstanceService;

    @Autowired
    private WorkflowDefinitionRepository workflowDefinitionRepository;

    @Autowired
    private PassportFeignClient passportFeignClient;

    @Autowired
    private NoticeLogService noticeLogService;

    @Autowired
    private SendNoticeContext sendNoticeContext;

    @Autowired
    private UserGroupService userGroupService;

    @Autowired
    private WorkModeService workModeService;

    @Autowired
    private WorkDayRepository workDayRepository;

    @Autowired
    private NoticeConfigService noticeConfigService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private NoticeContentUtil noticeContentUtil;

    @Value("${task.timeout.repeat.send.interval:}")
    private String repeatSendInterval;

    private final String TIME_OUT = "timeout";
    private final String LEADER = "leader";

    /**
     * ?????????????????????????????????????????? ????????????????????????
     */
    public void sendTimeoutNotice() {
        try {
            List<TaskVO> tasks = workflowTaskService.findUnfinishedTask();
            if (CollectionUtils.isEmpty(tasks)) {
                return;
            }
            // ?????????????????????????????????????????????????????????
            List<WorkModeEntity> modes = workModeService.getWorkModeToday();
            if (CollectionUtils.isEmpty(modes)) {
                return;
            }
            Map<String, Object> modeMap = new HashMap<>(16);
            modes.forEach(mode -> {
                modeMap.put(mode.getCode(), mode);
            });
            tasks.forEach(task -> {
                // ?????? ??????????????????
                Task activityTask = taskService.createTaskQuery().taskId(task.getId()).singleResult();
                if (activityTask == null) {
                    log.error("???????????????????????????????????????????????????activity??????????????????????????????ID:" + task.getId());
                    return;
                }
                NodeEntity node = workflowTaskService.queryNodeByTask(activityTask);
                if (node == null) {
                    log.error("????????????????????????????????????????????????????????????Node?????????????????????ID:" + task.getId());
                    return;
                }
                // ????????????????????????????????????
                List<NoticeTemplateEntity> noticeTemplates = noticeConfigService.getNoticeTemplate(
                        NoticeConfigState.NODE,
                        node.getId(),
                        InstanceEvent.TIME_OUT.getCode());
                if (CollectionUtils.isEmpty(noticeTemplates)) {
                    return;
                }

                // ????????????????????????
                if (StringUtils.isBlank(task.getDueTime())) {
                    return;
                }
                // ?????????????????????????????????
                String letter = "[^a-z]*";
                if ("".equals(task.getDueTime().replaceFirst(letter, ""))) {
                    return;
                }
                // ???????????????????????????
                WorkflowInstanceVO instanceById = workflowInstanceService.getInstanceById(task.getInstanceId());
                if (instanceById == null) {
                    log.error("???????????????????????????????????????????????????????????????????????????????????????ID:" + task.getId());
                    return;
                }
                // ??????????????????
                NoticeEntity timeoutNotice = noticeLogService.getLastTimeoutNoticeByTaskId(task.getId());
                if (timeoutNotice == null) {
                    LocalDateTime lastCreateOn = TimeUtil.date2LocalDateTime(timeoutNotice.getCreatedOn());
                    // ????????????(?????????2)
                    Integer time = 2;
                    if (StringUtils.isNotBlank(repeatSendInterval)) {
                        time = Integer.valueOf(repeatSendInterval);
                    }
                    if (lastCreateOn.plusHours(time).isBefore(LocalDateTime.now())) {
                        Map<String, Collection<Integer>> targets = getTargets(task, modeMap);
                        Collection<Integer> timeout = targets.get(TIME_OUT);
                        Collection<Integer> leader = targets.get(LEADER);
                        send(timeout, leader, timeoutNotice, instanceById, task, noticeTemplates, null);
                    }
                    return;
                }
                // ??????????????? ?????????????????????
                Map<String, Collection<Integer>> targets = getTargets(task, modeMap);
                Collection<Integer> timeout = targets.get(TIME_OUT);
                Collection<Integer> leader = targets.get(LEADER);
                send(timeout, leader, null, instanceById, task, noticeTemplates, activityTask.getProcessVariables());
            });
        } catch (Exception e) {
            log.error("??????????????????????????????", e);
        }
    }

    private Map<String, Collection<Integer>> getTargets(TaskVO task, Map<String, Object> modeMap) {

        Map<String, Collection<Integer>> targets = new HashMap<>(16);
        Set<Integer> timeoutIds = new HashSet<>();
        Set<Integer> leaders = new HashSet<>();
        targets.put(TIME_OUT, timeoutIds);
        targets.put(LEADER, leaders);

        if (StringUtils.isBlank(task.getCandidateUsers())) {
            return targets;
        }
        String split = ",";
        String[] ids = task.getCandidateUsers().split(split);
        List<Integer> userIds = new ArrayList<>();
        if (StringUtils.isNotBlank(task.getCandidateGroups()) && task.getCandidateGroups().split(split).length > 0) {
            userIds = userGroupService.getUserListByGroupsInt(Arrays.asList(task.getCandidateGroups().split(split)));
        }
        Set<String> userSet = new HashSet<>();
        userIds.forEach(id -> {
            userSet.add(String.valueOf(id));
        });
        userSet.addAll(Arrays.asList(ids));

        ServerResponse<List<UserGroupLeaderVO>> deptLeaderResponse = null;
        List<UserGroupLeaderVO> userDeptInfo = new ArrayList<>();
        try {
            deptLeaderResponse = passportFeignClient.getDeptLeaderInfoList(userSet);
            if (deptLeaderResponse.getCode() == ResponseCode.SUCCESS.getCode()) {
                userDeptInfo = deptLeaderResponse.getData();
            } else {
                log.error("??????????????????????????????????????????????????????????????????????????????????????????" + JSONObject.toJSONString(deptLeaderResponse));
            }
        } catch (Exception e) {
            log.error("??????????????????????????????????????????????????????????????????????????????????????????", e);
        }

        // ????????????
        String dueTime = task.getDueTime();
        // ??????
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime createDateTime = TimeUtil.date2LocalDateTime(task.getCreatedOn());
        // ???????????????????????????
        String regex = "[^a-z]*";
        char c = dueTime.replaceFirst(regex, "").charAt(0);
        int index = dueTime.indexOf(c);
        Integer num = Integer.valueOf(dueTime.substring(0, index));
        String devDeptState = "dev";
        String otherDeptState = "other";
        userDeptInfo.forEach(info -> {
            if (info.getGroupIds() != null && info.getGroupIds().size() > 0) {
                WorkModeEntity workMode = new WorkModeEntity();
                if (1 == info.getIsDevGroup()) { // ??????
                    if (!modeMap.containsKey(devDeptState)) {
                        return;
                    }
                    workMode = (WorkModeEntity) modeMap.get(devDeptState);
                } else {
                    if (!modeMap.containsKey(otherDeptState)) {
                        return;
                    }
                    workMode = (WorkModeEntity) modeMap.get(otherDeptState);
                }

                LocalTime startTime = workMode.getStartTime().toLocalTime();
                LocalTime endTime = workMode.getEndTime().toLocalTime();

                if (c == TimeoutParam.M.getText() || c == TimeoutParam.H.getText()) {
                    long dayCount = workDayRepository.getSubDays(createDateTime, now);
                    // ??????
                    long subTime = 0;
                    if (dayCount > 0) {
                        subTime += (dayCount - 1) * 24 * 60 * 60;
                        LocalDateTime workEndTime = LocalDateTime.of(createDateTime.toLocalDate(), endTime);
                        LocalDateTime workStartTime = LocalDateTime.of(now.toLocalDate(), startTime);
                        subTime += createDateTime.until(workEndTime, ChronoUnit.SECONDS);
                        subTime += workStartTime.until(now, ChronoUnit.SECONDS);
                    } else {
                        subTime += createDateTime.until(now, ChronoUnit.SECONDS);
                    }
                    long time = 0;
                    long unit = 0;
                    if (c == TimeoutParam.M.getText()) {
                        unit = 1;
                    } else if (c == TimeoutParam.H.getText()) {
                        unit = 60;
                    }
                    time += num * unit * 60;
                    if (subTime >= time) {
                        targets.get(TIME_OUT).add(info.getUserId());
                        targets.get(LEADER).addAll(info.getLeaderIds());
                    }
                } else {
                    LocalDateTime lastDate = null;
                    if (c == TimeoutParam.D.getText()) {
                        List<WorkDayEntity> upToDateList = workDayRepository.getUpToDate(createDateTime, num);
                        // ????????????
                        WorkDayEntity workDayEntity = upToDateList.get(upToDateList.size() - 1);
                        lastDate = LocalDateTime.of(TimeUtil.date2LocalDate(new Date(workDayEntity.getDateOfYear().getTime())), createDateTime.toLocalTime());
                    } else if (c == TimeoutParam.W.getText()) {
                        lastDate = createDateTime.plusWeeks(num);
                    } else if (c == TimeoutParam.I.getText()) {
                        if (num > 1) {
                            List<WorkDayEntity> upToDateList = workDayRepository.getUpToDate(createDateTime, num);
                            // ????????????
                            WorkDayEntity workDayEntity = upToDateList.get(upToDateList.size() - 2);
                            lastDate = LocalDateTime.of(TimeUtil.date2LocalDate(new Date(workDayEntity.getDateOfYear().getTime())), endTime);
                        } else {
                            lastDate = LocalDateTime.of(createDateTime.toLocalDate(), endTime);
                        }
                    }
                    if (now.isAfter(lastDate)) {
                        targets.get(TIME_OUT).add(info.getUserId());
                        targets.get(LEADER).addAll(info.getLeaderIds());
                    }
                }
            }
        });
        return targets;
    }

    private void send(Collection<Integer> target,
                      Collection<Integer> leader,
                      NoticeEntity timeoutNotice,
                      WorkflowInstanceVO instance,
                      TaskVO task,
                      List<NoticeTemplateEntity> noticeTemplates,
                      Map<String, Object> variables) {
        // ??????????????? ?????????????????????
        if (CollectionUtils.isEmpty(target)) {
            return;
        }
        WorkflowDefinitionEntity definition = workflowDefinitionRepository.findById(instance.getDefinitionId()).orElse(null);
        if (definition == null) {
            return;
        }

        BaseTaskInfoDTO baseTaskInfoDTO = ConvertClassUtil.convertToBaseTaskInfoDTO(task);
        if (timeoutNotice != null) {
            // ???????????????
            noticeTemplates.forEach(template -> {
                MessageInfoVO message = new MessageInfoVO();
                message.setTitle(timeoutNotice.getTitle());
                message.setDescription(timeoutNotice.getContent());
                message.setUrl(timeoutNotice.getNoticeUrl());
                message.setButtonConfig(template.getButtonConfig());
                message.setTaskId(task.getId());
                message.setDefinitionId(timeoutNotice.getDefinitionId());
                message.setInstanceId(timeoutNotice.getInstanceId());
                message.setTargets(target);
                message.setLeaders(leader);
                message.setNoticeEventType(InstanceEvent.TIME_OUT.getCode());
                sendNoticeContext.send(template, message);
            });
        } else {
            noticeTemplates.forEach(template -> {
                MessageInfoVO message = noticeContentUtil.translateNodeTemplate(template, definition, baseTaskInfoDTO,
                        variables);
                message.setTargets(target);
                message.setLeaders(leader);
                message.setNoticeEventType(InstanceEvent.TIME_OUT.getCode());
                sendNoticeContext.send(template, message);
            });
        }
    }
}
