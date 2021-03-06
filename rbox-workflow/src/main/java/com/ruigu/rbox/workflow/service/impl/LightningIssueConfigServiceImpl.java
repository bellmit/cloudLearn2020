package com.ruigu.rbox.workflow.service.impl;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.ruigu.rbox.cloud.kanai.model.YesOrNoEnum;
import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.cloud.kanai.util.JsonUtil;
import com.ruigu.rbox.workflow.exceptions.GlobalRuntimeException;
import com.ruigu.rbox.workflow.exceptions.VerificationFailedException;
import com.ruigu.rbox.workflow.manager.LightningIssueConfigManager;
import com.ruigu.rbox.workflow.manager.PassportFeignManager;
import com.ruigu.rbox.workflow.model.dto.*;
import com.ruigu.rbox.workflow.model.request.DutyUserRequest;
import com.ruigu.rbox.workflow.model.entity.*;
import com.ruigu.rbox.workflow.model.enums.DutyRuleTypeEnum;
import com.ruigu.rbox.workflow.model.enums.ResponseCode;
import com.ruigu.rbox.workflow.model.request.*;
import com.ruigu.rbox.workflow.model.vo.*;
import com.ruigu.rbox.workflow.repository.DutyPlanRepository;
import com.ruigu.rbox.workflow.repository.DutyRuleRepository;
import com.ruigu.rbox.workflow.repository.DutyWeekPlanRepository;
import com.ruigu.rbox.workflow.repository.LightningIssueCategoryRepository;
import com.ruigu.rbox.workflow.service.DistributedLocker;
import com.ruigu.rbox.workflow.service.LightningIssueConfigService;
import com.ruigu.rbox.workflow.strategy.context.DutyConfigHandleContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author liqingtian
 * @date 2020/05/07 17:39
 */
@Slf4j
@Service
public class LightningIssueConfigServiceImpl implements LightningIssueConfigService {

    @Resource
    private LightningIssueConfigManager lightningIssueConfigManager;

    @Resource
    private PassportFeignManager passportFeignManager;

    @Resource
    private LightningIssueCategoryRepository lightningIssueCategoryRepository;

    @Resource
    private DutyRuleRepository dutyRuleRepository;

    @Resource
    private DutyPlanRepository dutyPlanRepository;

    @Resource
    private DutyWeekPlanRepository dutyWeekPlanRepository;

    @Resource
    private JPAQueryFactory queryFactory;

    @Resource
    private DistributedLocker distributedLocker;

    @Resource
    private DutyConfigHandleContext dutyConfigHandleContext;

    @Override
    public List<LightningCategoryConfigVO> selectIssueCategory(String categoryName) {

        // ????????????
        List<LightningCategoryConfigVO> resultList = new ArrayList<>();
        // ???????????????
        BooleanBuilder builder = new BooleanBuilder();
        QLightningIssueCategoryEntity qCategory = QLightningIssueCategoryEntity.lightningIssueCategoryEntity;
        if (!StringUtils.isBlank(categoryName)) {
            builder.and(qCategory.name.like("%" + categoryName + "%"));
        }
        QDutyRuleEntity qRule = QDutyRuleEntity.dutyRuleEntity;
        List<Tuple> data = queryFactory
                .select(qCategory.id, qCategory.name, qCategory.userId, qCategory.userName, qCategory.ruleId, qRule.name, qCategory.sort, qCategory.status)
                .from(qCategory).leftJoin(qRule).on(qCategory.ruleId.eq(qRule.id))
                .where(builder).fetch();
        if (CollectionUtils.isEmpty(data)) {
            return resultList;
        }
        // ????????????
        data.forEach(d -> {
            LightningCategoryConfigVO categoryConfig = new LightningCategoryConfigVO();
            categoryConfig.setCategoryId(d.get(qCategory.id));
            categoryConfig.setCategoryName(d.get(qCategory.name));
            categoryConfig.setUserId(d.get(qCategory.userId));
            categoryConfig.setUserName(d.get(qCategory.userName));
            categoryConfig.setRuleId(d.get(qCategory.ruleId));
            categoryConfig.setRuleName(d.get(qRule.name));
            categoryConfig.setSort(d.get(qCategory.sort));
            categoryConfig.setStatus(d.get(qCategory.status));
            resultList.add(categoryConfig);
        });
        return resultList;
    }

    @Override
    public Integer saveIssueCategory(LightningCategoryRequest request) {
        LightningIssueCategoryEntity categoryEntity = convertEntity(request);
        categoryEntity.setStatus(YesOrNoEnum.NO.getCode());
        // ????????????
        Integer operator = UserHelper.getUserId();
        categoryEntity.setCreatedBy(operator);
        categoryEntity.setLastUpdatedBy(operator);
        LocalDateTime now = LocalDateTime.now();
        categoryEntity.setCreatedOn(now);
        categoryEntity.setLastUpdatedOn(now);
        try {
            LightningIssueCategoryEntity save = lightningIssueCategoryRepository.save(categoryEntity);
            return save.getId();
        } catch (DataIntegrityViolationException e) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????????????????");
        }
    }

    @Override
    public void updateIssueCategory(LightningCategoryRequest request) {
        Integer categoryId = request.getCategoryId();
        if (categoryId == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????id????????????");
        }
        LightningIssueCategoryEntity oldCategoryEntity = lightningIssueCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????"));

        Integer oldRuleId = oldCategoryEntity.getRuleId();

        // ???????????????
        LightningIssueCategoryEntity categoryEntity = convertEntity(request);
        categoryEntity.setId(categoryId);
        categoryEntity.setStatus(oldCategoryEntity.getStatus());
        // ????????????
        categoryEntity.setCreatedBy(oldCategoryEntity.getCreatedBy());
        categoryEntity.setCreatedOn(oldCategoryEntity.getCreatedOn());
        categoryEntity.setLastUpdatedBy(UserHelper.getUserId());
        categoryEntity.setLastUpdatedOn(LocalDateTime.now());

        try {

            LightningIssueCategoryEntity save = lightningIssueCategoryRepository.save(categoryEntity);

            if (oldRuleId != null) {
                dutyRuleRepository.findById(oldRuleId).ifPresent(r -> {
                    dutyConfigHandleContext.removeCache(categoryId, r.getType());
                });
            }

            Integer newRuleId = save.getRuleId();
            if (newRuleId != null && !newRuleId.equals(oldRuleId)) {
                // ??????
                dutyRuleRepository.findById(newRuleId).ifPresent(r -> {
                    dutyConfigHandleContext.updateCache(categoryId, r);
                });
            }
        } catch (DuplicateKeyException e) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????");
        }
    }

    @Override
    public void categoryOnOff(OnOffRequest request) {
        Integer categoryId = request.getId();
        Integer status = request.getStatus();
        boolean on = YesOrNoEnum.YES.getCode() == status;
        LightningIssueCategoryEntity category = lightningIssueCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????????????????????????????"));
        Integer ruleId = category.getRuleId();
        if (ruleId != null) {
            DutyRuleEntity rule = dutyRuleRepository.findById(ruleId)
                    .orElseThrow(() -> new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????????????????"));
            dutyConfigHandleContext.categoryOnOff(categoryId, rule, on);
        } else {
            // ??????????????????
            if (category.getUserId() == null) {
                throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????????????????????????????");
            }
        }
        // ??????????????????
        lightningIssueCategoryRepository.updateStatus(categoryId, status);
    }

    @Override
    public List<LightningDutyRuleSelectVO> selectDutyRuleList(String ruleName) {
        List<LightningDutyRuleSelectVO> resultList = new ArrayList<>();
        // ????????????
        if (StringUtils.isBlank(ruleName)) {
            ruleName = null;
        } else {
            ruleName = "%" + ruleName + "%";
        }
        // ????????????????????????
        List<PassportGroupInfoDTO> allGroupInfo = passportFeignManager.getAllGroupInfo();
        Map<Integer, List<PassportGroupInfoDTO>> groupInfoMap = allGroupInfo.parallelStream()
                .collect(Collectors.groupingBy(PassportGroupInfoDTO::getId));
        // ??????????????????
        List<DutyRuleEntity> ruleList = dutyRuleRepository.findAllByRuleNameLike(ruleName);
        for (DutyRuleEntity e : ruleList) {
            LightningDutyRuleSelectVO rule = new LightningDutyRuleSelectVO();
            Integer ruleId = e.getId();
            rule.setRuleId(ruleId);
            rule.setRuleName(e.getName());
            // ????????????
            Integer departmentId = e.getDepartmentId();
            rule.setDepartmentId(departmentId);
            List<PassportGroupInfoDTO> groupInfo = groupInfoMap.get(departmentId);
            if (CollectionUtils.isNotEmpty(groupInfo)) {
                rule.setDepartmentName(groupInfo.get(0).getDescription());
            }
            rule.setScopeType(e.getScopeType());
            rule.setPreDefined(e.getIsPreDefined());
            rule.setStatus(e.getStatus());
            // ????????????
            Integer type = e.getType();
            rule.setType(type);
            // todo ??????
            rule.setDutyUser(dutyConfigHandleContext.queryDutyUser(e, 0, 10));

            resultList.add(rule);
        }
        return resultList;
    }

    @Override
    public Page<DutyUserByDayDTO> selectDutyUserByRuleId(Integer ruleId, Integer page, Integer size) {
        return lightningIssueConfigManager.queryPageDutyUserByDay(ruleId, page, size);
    }

    @Override
    public LightningDutyRuleDetailVO getDutyRuleDetail(Integer ruleId) {
        // ????????????
        DutyRuleEntity dutyRuleEntity = dutyRuleRepository.findById(ruleId)
                .orElseThrow(() -> new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????"));
        // ??????
        LightningDutyRuleDetailVO detailVO = new LightningDutyRuleDetailVO();
        detailVO.setRuleId(dutyRuleEntity.getId());
        detailVO.setRuleName(dutyRuleEntity.getName());
        // ????????????
        Integer departmentId = dutyRuleEntity.getDepartmentId();
        PassportGroupInfoDTO groupInfo = passportFeignManager.getGroupInfoById(departmentId);
        detailVO.setDepartmentId(departmentId);
        detailVO.setDepartmentName(groupInfo.getDescription());
        detailVO.setScopeType(dutyRuleEntity.getScopeType());
        detailVO.setPreDefined(dutyRuleEntity.getIsPreDefined());
        detailVO.setStatus(dutyRuleEntity.getStatus());
        // ????????????
        Integer type = dutyRuleEntity.getType();
        detailVO.setType(type);
        // todo ??????
        detailVO.setDutyUser(dutyConfigHandleContext.queryDutyUser(dutyRuleEntity));

        return detailVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer addDutyRule(AddDutyRuleRequest request) {
        DutyRuleEntity rule = new DutyRuleEntity();
        rule.setName(request.getRuleName());
        rule.setDepartmentId(request.getDepartmentId());
        rule.setIsPreDefined(YesOrNoEnum.NO.getCode());
        rule.setScopeType(YesOrNoEnum.NO.getCode());
        // ????????????
        Integer type = request.getType();
        rule.setType(type);
        // ?????????????????????
        rule.setStatus(YesOrNoEnum.NO.getCode());
        // ????????????
        Integer operator = UserHelper.getUserId();
        rule.setCreatedBy(operator);
        rule.setLastUpdatedBy(operator);
        LocalDateTime now = LocalDateTime.now();
        rule.setCreatedOn(now);
        rule.setLastUpdatedOn(now);
        try {
            dutyRuleRepository.save(rule);
        } catch (DataIntegrityViolationException e) {
            throw new GlobalRuntimeException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????");
        }
        // ??????????????????
        DutyUserRequest dutyUserRequest = request.getDutyUser();
        if (dutyUserRequest != null) {
            // todo ??????
            dutyConfigHandleContext.save(rule, dutyUserRequest);
        }

        return rule.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDutyRule(UpdateDutyRuleRequest request) {
        // ???????????????????????????
        Integer ruleId = request.getRuleId();
        DutyRuleEntity oldRule = dutyRuleRepository.findById(ruleId)
                .orElseThrow(() -> new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????"));
        // ?????????????????????
        Integer oldType = oldRule.getType();
        Integer newType = request.getType();
        // ???????????????????????? ??????????????????????????????
        if (!oldType.equals(newType)) {
            // ???????????? ?????????????????????
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????");
        } else {
            // todo ??????
            dutyConfigHandleContext.update(oldRule, request.getDutyUser());
        }

        // ????????????
        oldRule.setName(request.getRuleName());
        oldRule.setDepartmentId(request.getDepartmentId());
        oldRule.setType(newType);
        oldRule.setLastUpdatedBy(UserHelper.getUserId());
        oldRule.setLastUpdatedOn(LocalDateTime.now());
        dutyRuleRepository.save(oldRule);
    }

    @Override
    public List<DutyRuleEntity> selectDutyRuleDropBoxList() {
        return dutyRuleRepository.findAllByStatus(YesOrNoEnum.YES.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ruleOnOff(OnOffRequest request) {
        Integer ruleId = request.getId();
        Integer status = request.getStatus();
        // ?????????????????????????????????
        List<LightningIssueCategoryEntity> allCategory = lightningIssueCategoryRepository.findAllByRuleIdAndStatus(ruleId, YesOrNoEnum.YES.getCode());
        boolean on = status == YesOrNoEnum.YES.getCode();
        if (!on && CollectionUtils.isNotEmpty(allCategory)) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????????????????????????????????????????????????????????????????????????????");
        }
        // ????????????????????????
        if (on) {
            DutyRuleEntity rule = dutyRuleRepository.findByIdAndStatus(ruleId, YesOrNoEnum.NO.getCode());
            if (rule == null) {
                throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "????????????????????????????????????????????????????????????????????????");
            }
            // todo ??????
            dutyConfigHandleContext.onRule(rule);
        }
    }

    private LightningIssueCategoryEntity convertEntity(LightningCategoryRequest request) {
        // ???????????? ???????????????????????? or ???????????????
        Integer userId = request.getUserId();
        Integer ruleId = request.getRuleId();
        boolean userIdIsEmpty;
        boolean ruleIdIsEmpty;

        if ((userIdIsEmpty = userId == null) & (ruleIdIsEmpty = ruleId == null)) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "?????????????????????????????????????????????");
        }

        if (!userIdIsEmpty && !ruleIdIsEmpty) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "???????????????????????????????????????????????????");
        }

        // ????????????
        LightningIssueCategoryEntity categoryEntity = new LightningIssueCategoryEntity();
        // ?????????????????? ???????????????????????????
        categoryEntity.setName(request.getCategoryName());
        categoryEntity.setSort(request.getSort());
        // ?????????
        if (!userIdIsEmpty) {
            String userName;
            if (userId == 0) {
                userName = "??????";
            } else {
                userName = passportFeignManager.getUserInfoFromRedis(userId).getNickname();
            }
            categoryEntity.setUserId(userId);
            categoryEntity.setUserName(userName);
        }

        // ??????id
        if (!ruleIdIsEmpty) {
            DutyRuleEntity rule = dutyRuleRepository.findByIdAndStatus(ruleId, YesOrNoEnum.YES.getCode());
            if (rule == null) {
                throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????????????????");
            }
            categoryEntity.setRuleId(ruleId);
        }

        return categoryEntity;
    }

    @Override
    public LightningIssueCategoryVO distributionDutyUser(Integer categoryId) {
        Integer userId = lightningIssueConfigManager.distributionDuty(categoryId);
        if (null == userId) {
            // ???????????? ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????)
            // ????????????????????? ?????????????????????
            String lock = "temp:initDutyUser:lock:" + categoryId;
            try {
                // ??????
                distributedLocker.lock(lock, TimeUnit.SECONDS, 5);
                // ?????????????????? ????????????????????????
                userId = lightningIssueConfigManager.distributionDuty(categoryId);
                if (null == userId) {

                    DutyRuleEntity plan = dutyRuleRepository.findByCategoryId(categoryId);

                    List<Integer> dutyUserIds = new ArrayList<>();

                    if (plan == null) {
                        throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "????????????????????????????????????");
                    } else if (DutyRuleTypeEnum.DUTY_POLL.getCode().equals(plan.getType())) {
                        if (StringUtils.isBlank(plan.getUserIds())) {
                            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "????????????????????????????????????");
                        }
                        dutyUserIds = JsonUtil.parseArray(plan.getUserIds(), Integer.class);
                        if (CollectionUtils.isEmpty(dutyUserIds)) {
                            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "????????????????????????????????????");
                        }
                        lightningIssueConfigManager.initDutyPollUser(categoryId, dutyUserIds);
                    } else if (DutyRuleTypeEnum.DUTY_BY_WEEK.getCode().equals(plan.getType())) {
                        // ????????????
                        DutyWeekPlanEntity dutyWeekUser = dutyWeekPlanRepository.findByRuleIdAndDayOfWeek(plan.getId(),
                                LocalDateTime.now().getDayOfWeek().getValue());
                        dutyUserIds = JsonUtil.parseArray(dutyWeekUser.getUserIds(), Integer.class);
                        if (CollectionUtils.isEmpty(dutyUserIds)) {
                            throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), "????????????????????????????????????");
                        }
                        lightningIssueConfigManager.initDutyWeekUser(categoryId, dutyUserIds);
                    }
                    log.info(" =============================  ???????????????????????????????????????  ============================ ");
                    userId = lightningIssueConfigManager.distributionDuty(categoryId);
                }
            } finally {
                // ??????
                distributedLocker.unlock(lock);
            }
        }
        PassportUserInfoDTO userInfoFromRedis = passportFeignManager.getUserInfoFromRedis(userId);
        LightningIssueCategoryVO categoryVO = new LightningIssueCategoryVO();
        categoryVO.setId(categoryId);
        categoryVO.setUserId(userInfoFromRedis.getId());
        categoryVO.setUserName(userInfoFromRedis.getNickname());
        categoryVO.setAvatar(userInfoFromRedis.getAvatar());
        return categoryVO;
    }

    @Override
    public void updateRedisConfig(UpdateRedisDutyConfigRequest request) {
        Integer categoryId = request.getCategoryId();

        if (DutyRuleTypeEnum.DUTY_BY_DAY.getCode().equals(request.getType())) {
            lightningIssueConfigManager.replaceDutyByDayUser(categoryId, request.getUserId());
        } else if (DutyRuleTypeEnum.DUTY_POLL.getCode().equals(request.getType())) {
            List<Integer> userIds = request.getUserIds();
            if (CollectionUtils.isNotEmpty(userIds)) {
                lightningIssueConfigManager.removeAndUpdateDutyPollUser(categoryId, request.getUserIds());
            }
        } else if (DutyRuleTypeEnum.DUTY_BY_WEEK.getCode().equals(request.getType())) {
            List<Integer> userIds = request.getUserIds();
            if (CollectionUtils.isNotEmpty(userIds)) {
                lightningIssueConfigManager.removeAndUpdateDutyWeekUser(categoryId, request.getUserIds());
            }
        }
    }

    @Override
    public List<Integer> queryRedisDutyUser(Integer categoryId) {
        DutyRuleEntity rule = dutyRuleRepository.findByCategoryId(categoryId);
        if (rule == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "??????????????????");
        }
        // todo ??????
        return dutyConfigHandleContext.queryTodayDutyUser(categoryId, rule.getType());
    }
}
