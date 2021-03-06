package com.ruigu.rbox.workflow.service.timer;

import com.ruigu.rbox.cloud.kanai.model.YesOrNoEnum;
import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.cloud.kanai.util.JsonUtil;
import com.ruigu.rbox.cloud.kanai.util.TimeUtil;
import com.ruigu.rbox.workflow.exceptions.VerificationFailedException;
import com.ruigu.rbox.workflow.manager.LightningIssueConfigManager;
import com.ruigu.rbox.workflow.manager.PassportFeignManager;
import com.ruigu.rbox.workflow.model.dto.PassportUserInfoDTO;
import com.ruigu.rbox.workflow.model.entity.DutyPlanEntity;
import com.ruigu.rbox.workflow.model.entity.DutyRuleEntity;
import com.ruigu.rbox.workflow.model.entity.DutyWeekPlanEntity;
import com.ruigu.rbox.workflow.model.entity.LightningIssueCategoryEntity;
import com.ruigu.rbox.workflow.model.enums.ResponseCode;
import com.ruigu.rbox.workflow.repository.DutyPlanRepository;
import com.ruigu.rbox.workflow.repository.DutyRuleRepository;
import com.ruigu.rbox.workflow.repository.DutyWeekPlanRepository;
import com.ruigu.rbox.workflow.repository.LightningIssueCategoryRepository;
import com.ruigu.rbox.workflow.service.QuestNoticeService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author liqingtian
 * @date 2020/07/13 10:20
 */
@Slf4j
@Service
public class ReplaceDutyUserTimer {

    @Resource
    private DutyRuleRepository dutyRuleRepository;

    @Resource
    private DutyPlanRepository dutyPlanRepository;

    @Resource
    private DutyWeekPlanRepository dutyWeekPlanRepository;

    @Resource
    private LightningIssueCategoryRepository lightningIssueCategoryRepository;

    @Resource
    private LightningIssueConfigManager lightningIssueConfigManager;

    @Resource
    private QuestNoticeService questNoticeService;

    @Value("${rbox.workflow.technical.duty-role-code}")
    private String dutyRoleCode;

    @Resource
    private PassportFeignManager passportFeignManager;

    public void replace() {
        log.info(" ================================  ???????????????????????????  ================================ ");

        log.info(" ================================  ??????????????????  ================================ ");
        List<DutyPlanEntity> todayDutyPlanList = dutyPlanRepository.findAllByDutyDate(LocalDateTime.of(LocalDate.now(), LocalTime.MIN));
        if (CollectionUtils.isNotEmpty(todayDutyPlanList)) {
            Map<Integer, DutyPlanEntity> planMap = new HashMap<>(16);
            todayDutyPlanList.forEach(p -> planMap.put(p.getRuleId(), p));
            // ??????????????????
            List<LightningIssueCategoryEntity> dayCategorys = lightningIssueCategoryRepository.findAllByRuleIdInAndStatus(planMap.keySet(), YesOrNoEnum.YES.getCode());
            if (CollectionUtils.isNotEmpty(dayCategorys)) {
                dayCategorys.forEach(c -> {
                    DutyPlanEntity dutyPlanEntity = planMap.getOrDefault(c.getRuleId(), null);
                    if (dutyPlanEntity != null) {
                        lightningIssueConfigManager.replaceDutyByDayUser(c.getId(), dutyPlanEntity.getPersonId());
                    }
                });
            }
        }

        log.info(" ================================  ??????????????????  ================================ ");
        Integer dayOfWeek = LocalDateTime.now().getDayOfWeek().getValue();
        List<DutyWeekPlanEntity> todayWeekPlanList = dutyWeekPlanRepository.findAllEnableByDayOfWeek(dayOfWeek);
        Map<Integer, DutyWeekPlanEntity> weekPlanMap = new HashMap<>(16);
        todayWeekPlanList.forEach(p -> weekPlanMap.put(p.getRuleId(), p));
        // ??????????????????
        List<LightningIssueCategoryEntity> weekCategorys = lightningIssueCategoryRepository.findAllByRuleIdInAndStatus(weekPlanMap.keySet(), YesOrNoEnum.YES.getCode());
        if (CollectionUtils.isNotEmpty(weekCategorys)) {
            weekCategorys.forEach(c -> {
                DutyWeekPlanEntity weekPlan = weekPlanMap.getOrDefault(c.getRuleId(), null);
                if (weekPlan != null) {
                    List<Integer> dutyUser = JsonUtil.parseArray(weekPlan.getUserIds(), Integer.class);
                    lightningIssueConfigManager.removeAndUpdateDutyWeekUser(c.getId(), dutyUser);
                }
            });
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void checkAndResetTechnicalDutyUser() {
        log.info("================= check and reset technical duty user =================");
        // check ??????????????????
        LocalDate dutyDate = LocalDate.now().plusWeeks(1);
        DutyPlanEntity technicalTodayDutyPlan = dutyPlanRepository.findTechnicalTodayDutyUser(YesOrNoEnum.YES.getCode(), LocalDateTime.of(dutyDate, LocalTime.MIN), YesOrNoEnum.YES.getCode());
        if (technicalTodayDutyPlan != null) {
            return;
        }
        log.info("================ start reset ===============");
        // 1. ????????????code?????????????????? ??? ???????????????????????????id
        List<PassportUserInfoDTO> dutyUserList = passportFeignManager.getListUserByRoleCode(dutyRoleCode);
        // ??????????????????????????????
        dutyUserList.removeIf(u -> u.getDeleted() == YesOrNoEnum.YES.getCode() || u.getStatus() == YesOrNoEnum.NO.getCode());
        if (CollectionUtils.isEmpty(dutyUserList)) {
            throw new VerificationFailedException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "??????????????????????????????????????????");
        }
        DutyRuleEntity technicalRule = dutyRuleRepository.findByScopeTypeAndIsPreDefined(YesOrNoEnum.YES.getCode(), YesOrNoEnum.YES.getCode());
        if (technicalRule == null) {
            throw new VerificationFailedException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "?????????????????????????????????????????????");
        }
        Integer ruleId = technicalRule.getId();
        // 2. ????????????
        Collections.shuffle(dutyUserList);
        // 3. ????????????????????????
        // ????????????
        // ???????????????????????????????????????????????????????????????
        DutyPlanEntity latestPlan = dutyPlanRepository.queryTechnicalLatestDutyPlan(YesOrNoEnum.YES.getCode(), YesOrNoEnum.YES.getCode());
        LocalDate latestDutyDate = latestPlan.getDutyDate().plusDays(1).toLocalDate();
        dutyDate = correctDutyDate(latestDutyDate, dutyDate);
        List<DutyPlanEntity> planList = new ArrayList<>();
        for (PassportUserInfoDTO user : dutyUserList) {
            // ??????????????????
            DutyPlanEntity plan = buildDutyPlan(ruleId);
            plan.setPersonId(user.getId());
            plan.setPersonName(user.getNickname());
            plan.setDutyDate(LocalDateTime.of(dutyDate, LocalTime.MIN));
            planList.add(plan);
            dutyDate = dutyDate.plusDays(1);
        }
        dutyPlanRepository.saveAll(planList);
        // 4. ????????????
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(TimeUtil.FORMAT_DATE);
                planList.forEach(p -> {
                    String dutyDate = formatter.format(p.getDutyDate());
                    String title = "???????????????????????????";
                    String content = "???????????????????????????????????????" + dutyDate;
                    try {
                        questNoticeService.sendEmailNotice(null, title, content, Collections.singleton(p.getPersonId()), null);
                    } catch (Exception e) {
                        log.error("????????????????????????");
                    }
                });
            }
        });
        log.info("end");
    }

    public void warmReminder() {
        // ??????????????????
        LocalDateTime tomorrow = LocalDate.now().plusDays(1).atStartOfDay();
        // ??????????????????????????????
        // 1. ?????????????????????
        List<DutyPlanEntity> allTomorrowDutyUser = dutyPlanRepository.findAllByDutyDate(tomorrow);
        // 2. ???????????????
        final int tomorrowDayOfWeek = tomorrow.getDayOfWeek().getValue();
        List<DutyWeekPlanEntity> allWeekDutyUser = dutyWeekPlanRepository.findAllEnableByDayOfWeek(tomorrowDayOfWeek);
        // 3. ???????????????????????????
        List<Integer> targets = new ArrayList<>();
        targets.addAll(allTomorrowDutyUser.stream()
                .map(DutyPlanEntity::getPersonId)
                .collect(Collectors.toList()));
        targets.addAll(allWeekDutyUser.stream()
                .filter(w -> StringUtils.isNotBlank(w.getUserIds()))
                .flatMap(w -> JsonUtil.parseArray(w.getUserIds(), Integer.class).stream())
                .collect(Collectors.toList()));
        // ????????????
        questNoticeService.sendWeiXinTextNotice(null, "????????????????????????\n???????????????????????????????????????????????????????????????????????????", targets);
    }

    private DutyPlanEntity buildDutyPlan(Integer ruleId) {
        Integer userId = UserHelper.getUserId();
        DutyPlanEntity plan = new DutyPlanEntity();
        plan.setRuleId(ruleId);
        plan.setCreatedBy(userId);
        plan.setCreatedOn(LocalDateTime.now());
        plan.setLastUpdatedBy(userId);
        plan.setLastUpdatedOn(LocalDateTime.now());
        return plan;
    }

    private LocalDate correctDutyDate(LocalDate latestDate, LocalDate planDutyDate) {
        // ????????????????????????
        LocalDate shouldDutyStartDate = latestDate.isBefore(planDutyDate) ? latestDate : planDutyDate;
        // ??????
        LocalDate now = LocalDate.now();
        // ??????????????????????????????????????????,??????????????????
        return shouldDutyStartDate.isBefore(now) ? now : shouldDutyStartDate;
    }
}
