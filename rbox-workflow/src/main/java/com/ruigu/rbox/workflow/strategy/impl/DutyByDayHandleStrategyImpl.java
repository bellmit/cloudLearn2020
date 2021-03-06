package com.ruigu.rbox.workflow.strategy.impl;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.ruigu.rbox.cloud.kanai.model.YesOrNoEnum;
import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.cloud.kanai.util.TimeUtil;
import com.ruigu.rbox.workflow.exceptions.VerificationFailedException;
import com.ruigu.rbox.workflow.manager.LightningIssueConfigManager;
import com.ruigu.rbox.workflow.manager.PassportFeignManager;
import com.ruigu.rbox.workflow.model.dto.DutyUserByDayDTO;
import com.ruigu.rbox.workflow.model.dto.DutyUserDetailDTO;
import com.ruigu.rbox.workflow.model.dto.DutyUserListDTO;
import com.ruigu.rbox.workflow.model.dto.PassportUserInfoDTO;
import com.ruigu.rbox.workflow.model.entity.DutyPlanEntity;
import com.ruigu.rbox.workflow.model.entity.DutyRuleEntity;
import com.ruigu.rbox.workflow.model.entity.LightningIssueCategoryEntity;
import com.ruigu.rbox.workflow.model.entity.QDutyPlanEntity;
import com.ruigu.rbox.workflow.model.enums.DutyRuleTypeEnum;
import com.ruigu.rbox.workflow.model.enums.ResponseCode;
import com.ruigu.rbox.workflow.model.request.DutyUserRequest;
import com.ruigu.rbox.workflow.repository.DutyPlanRepository;
import com.ruigu.rbox.workflow.repository.DutyRuleRepository;
import com.ruigu.rbox.workflow.repository.LightningIssueCategoryRepository;
import com.ruigu.rbox.workflow.strategy.DutyConfigHandleStrategy;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author liqingtian
 * @date 2020/06/04 16:02
 */
@Service
public class DutyByDayHandleStrategyImpl implements DutyConfigHandleStrategy {

    @Resource
    private DutyPlanRepository dutyPlanRepository;

    @Resource
    private DutyRuleRepository dutyRuleRepository;

    @Autowired
    private JPAQueryFactory queryFactory;

    @Resource
    private LightningIssueCategoryRepository lightningIssueCategoryRepository;

    @Resource
    private LightningIssueConfigManager lightningIssueConfigManager;

    @Resource
    private PassportFeignManager passportFeignManager;

    @Override
    public Boolean match(Integer dutyRuleType) {
        return DutyRuleTypeEnum.DUTY_BY_DAY.getCode().equals(dutyRuleType);
    }

    @Override
    public void save(DutyRuleEntity rule, DutyUserRequest dutyUserRequest) {
        List<DutyUserByDayDTO> dayList = dutyUserRequest.getDayList();
        List<LocalDate> dutyDateList = dayList.stream().map(DutyUserByDayDTO::getDutyDate).distinct().collect(Collectors.toList());
        if (dayList.size() > dutyDateList.size()) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????????????????");
        }
        dutyPlanRepository.saveAll(convertPlan(rule.getId(), dayList));
    }

    @Override
    public void update(DutyRuleEntity oldRule, DutyUserRequest request) {

        Integer ruleId = oldRule.getId();

        boolean todayChange = false;

        boolean enable = YesOrNoEnum.YES.getCode() == oldRule.getStatus();

        // ???????????????
        List<DutyPlanEntity> planList = convertPlanUniqueCheck(ruleId, request.getDayList());
        LocalDateTime today = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        DutyPlanEntity todayPlan = planList.stream()
                .filter(p -> p.getDutyDate().equals(today)).findFirst().orElse(null);
        if (todayPlan != null) {
            todayChange = true;
        }
        dutyPlanRepository.saveAll(planList);

        // ??????redis
        List<LightningIssueCategoryEntity> allCategory = lightningIssueCategoryRepository.findAllByRuleIdAndStatus(ruleId, YesOrNoEnum.YES.getCode());
        if (enable && todayChange) {
            Integer dutyUserId = todayPlan.getPersonId();
            allCategory.forEach(c -> lightningIssueConfigManager.replaceDutyByDayUser(c.getId(), dutyUserId));
        }
    }

    @Override
    public List<Integer> queryTodayDutyUser(Integer categoryId) {
        return Collections.singletonList(lightningIssueConfigManager.queryTodayDutyByDayUser(categoryId));
    }

    @Override
    public void onRule(DutyRuleEntity rule) {
        Integer ruleId = rule.getId();
        // ????????????????????????????????????????????????
        DutyPlanEntity todayPlan = dutyPlanRepository.findByRuleIdAndDutyDate(ruleId, LocalDateTime.of(LocalDate.now(), LocalTime.MIN));
        if (todayPlan == null || todayPlan.getPersonId() == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????????????????????????????????????????????????????");
        }
        dutyRuleRepository.updateStatus(ruleId, YesOrNoEnum.YES.getCode());
    }

    @Override
    public void categoryOnOff(Integer categoryId, DutyRuleEntity rule, boolean on) {
        if (on) {
            if (rule.getStatus() != YesOrNoEnum.YES.getCode()) {
                throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????????????????????????????????????????");
            }
            lightningIssueConfigManager.updateDutyByDayUser(categoryId, rule.getId());
        } else {
            lightningIssueConfigManager.removeDutyByDayUser(categoryId);
        }
    }

    @Override
    public DutyUserDetailDTO queryDutyUser(DutyRuleEntity rule) {

        DutyUserDetailDTO dutyUser = new DutyUserDetailDTO();

        List<DutyUserByDayDTO> dayList = new ArrayList<>(64);

        LocalDate today = LocalDate.now();

        List<DutyPlanEntity> dutyPlanList = dutyPlanRepository.findAllByRuleIdOrderByDutyDateDesc(rule.getId());
        if (CollectionUtils.isNotEmpty(dutyPlanList)) {
            // ??????id??????
            List<Integer> dutyUserIds = dutyPlanList.stream().map(DutyPlanEntity::getPersonId).collect(Collectors.toList());
            // ??????????????????
            Map<Integer, PassportUserInfoDTO> userInfoMap = passportFeignManager.getUserInfoMapFromRedis(dutyUserIds);
            dutyPlanList.forEach(p -> {
                DutyUserByDayDTO user = new DutyUserByDayDTO();
                user.setPlanId(p.getId());
                Integer personId = p.getPersonId();
                PassportUserInfoDTO userInfo = userInfoMap.get(personId);
                user.setUserId(p.getPersonId());
                if (userInfo != null) {
                    user.setName(userInfo.getNickname());
                }
                LocalDate dutyDate = p.getDutyDate().toLocalDate();
                user.setDutyDate(dutyDate);
                if (dutyDate.isBefore(today)) {
                    user.setModifiable(false);
                } else {
                    user.setModifiable(true);
                }
                dayList.add(user);
            });
        }

        dutyUser.setDayList(dayList);

        return dutyUser;
    }

    @Override
    public DutyUserListDTO queryDutyUser(DutyRuleEntity rule, Integer page, Integer size) {
        DutyUserListDTO listDTO = new DutyUserListDTO();
        listDTO.setDayList(lightningIssueConfigManager.queryPageDutyUserByDay(rule.getId(), page, size));
        return listDTO;
    }

    @Override
    public void updateCache(Integer categoryId, DutyRuleEntity rule) {
        lightningIssueConfigManager.updateDutyByDayUser(categoryId, rule.getId());
    }

    @Override
    public void removeCache(Integer categoryId) {
        lightningIssueConfigManager.removeDutyByDayUser(categoryId);
    }


    private Map<String, Set<Integer>> queryDutyDatePlanMap(Integer ruleId) {

        Map<String, Set<Integer>> datePlanMap = new HashMap<>(16);

        // ??????
        QDutyPlanEntity qPlan = QDutyPlanEntity.dutyPlanEntity;
        List<Tuple> dutyDatePlan = queryFactory.select(qPlan.dutyDate, qPlan.id)
                .from(qPlan).where(qPlan.ruleId.eq(ruleId).and(qPlan.dutyDate.after(LocalDateTime.of(LocalDate.now().plusDays(-1), LocalTime.MIN))))
                .groupBy(qPlan.dutyDate).fetch();

        // ????????????
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern(TimeUtil.FORMAT_DATE);
        if (CollectionUtils.isNotEmpty(dutyDatePlan)) {
            dutyDatePlan.forEach(d -> {
                Set<Integer> planSet = new HashSet<>();
                planSet.add(d.get(qPlan.id));
                datePlanMap.put(d.get(qPlan.dutyDate).format(dateFormat), planSet);
            });
        }

        return datePlanMap;
    }

    private List<DutyPlanEntity> convertPlanUniqueCheck(Integer ruleId, List<DutyUserByDayDTO> saveOrUpdateList) {

        if (CollectionUtils.isNotEmpty(saveOrUpdateList)) {
            // ??????????????????
            saveOrUpdateList.sort(Comparator.comparing(DutyUserByDayDTO::getDutyDate));
            // ???????????????
            if (saveOrUpdateList.get(0).getDutyDate().isBefore(LocalDate.now())) {
                throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????????????????????????????");
            }

            // ???????????????????????? ??????????????????
            Map<String, Set<Integer>> datePlanMap = queryDutyDatePlanMap(ruleId);

            List<DutyPlanEntity> planList = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            Integer operator = UserHelper.getUserId();
            DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern(TimeUtil.FORMAT_DATE);

            saveOrUpdateList.forEach(d -> {
                DutyPlanEntity plan = new DutyPlanEntity();
                Integer planId = d.getPlanId();
                // ??????jpa????????????????????????????????????java??????????????????
                String key = d.getDutyDate().format(dateFormat);
                if (datePlanMap.containsKey(key)) {
                    if (planId == null) {
                        // ??? plan id ???????????? ????????????????????????????????????????????????
                        throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????");
                    } else {
                        // ??????
                        Set<Integer> planIdSet = datePlanMap.get(key);
                        if (!planIdSet.contains(planId)) {
                            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????");
                        }
                    }
                }
                plan.setId(planId);
                plan.setRuleId(ruleId);
                plan.setPersonId(d.getUserId());
                plan.setPersonName(d.getName());
                plan.setDutyDate(LocalDateTime.of(d.getDutyDate(), LocalTime.MIN));
                plan.setCreatedBy(operator);
                plan.setCreatedOn(now);
                plan.setLastUpdatedBy(operator);
                plan.setLastUpdatedOn(now);
                planList.add(plan);
            });
            return planList;
        }
        return new ArrayList<>();
    }

    private List<DutyPlanEntity> convertPlan(Integer ruleId, List<DutyUserByDayDTO> dayList) {

        if (CollectionUtils.isEmpty(dayList)) {
            return new ArrayList<>();
        }

        dayList.sort(Comparator.comparing(DutyUserByDayDTO::getDutyDate));
        // ???????????????
        if (dayList.get(0).getDutyDate().isBefore(LocalDate.now())) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????????????????????????????");
        }

        List<DutyPlanEntity> planList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        Integer operator = UserHelper.getUserId();
        dayList.forEach(d -> {
            DutyPlanEntity plan = new DutyPlanEntity();
            plan.setId(d.getPlanId());
            plan.setRuleId(ruleId);
            plan.setPersonId(d.getUserId());
            plan.setPersonName(d.getName());
            plan.setDutyDate(LocalDateTime.of(d.getDutyDate(), LocalTime.MIN));
            plan.setCreatedBy(operator);
            plan.setCreatedOn(now);
            plan.setLastUpdatedBy(operator);
            plan.setLastUpdatedOn(now);
            planList.add(plan);
        });
        return planList;
    }
}
