package com.ruigu.rbox.workflow.service.impl;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.ruigu.rbox.cloud.kanai.model.YesOrNoEnum;
import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.workflow.exceptions.GlobalRuntimeException;
import com.ruigu.rbox.workflow.manager.PassportFeignManager;
import com.ruigu.rbox.workflow.manager.UserManager;
import com.ruigu.rbox.workflow.model.dto.PassportUserInfoDTO;
import com.ruigu.rbox.workflow.model.dto.SpecialAfterSaleApprovalRulesDTO;
import com.ruigu.rbox.workflow.model.dto.UserGroupSimpleDTO;
import com.ruigu.rbox.workflow.model.entity.*;
import com.ruigu.rbox.workflow.model.enums.ResponseCode;
import com.ruigu.rbox.workflow.model.enums.SpecialAfterSaleApproverTypeEnum;
import com.ruigu.rbox.workflow.model.enums.Symbol;
import com.ruigu.rbox.workflow.model.request.SpecialAfterSaleApplyConfigRequest;
import com.ruigu.rbox.workflow.repository.SpecialAfterSaleReviewNodeRepository;
import com.ruigu.rbox.workflow.repository.SpecialAfterSaleReviewPositionRepository;
import com.ruigu.rbox.workflow.repository.SpecialAfterSaleReviewRepository;
import com.ruigu.rbox.workflow.repository.SpecialAfterSaleReviewReposity;
import com.ruigu.rbox.workflow.service.SpecialAfterSaleApplyConfigService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * @author PanJianWei
 * @version 1.0
 * @date 2020/8/11 17:07
 */

@Service
public class SpecialAfterSaleApplyConfigServiceImpl implements SpecialAfterSaleApplyConfigService {

    @Autowired
    private JPAQueryFactory queryFactory;

    @Autowired
    private SpecialAfterSaleReviewNodeRepository reviewNodeRepository;

    @Autowired
    private SpecialAfterSaleReviewPositionRepository reviewPositionRepository;

    @Autowired
    private SpecialAfterSaleReviewRepository reviewRepository;

    @Autowired
    private SpecialAfterSaleReviewNodeRepository nodeReposity;

    @Autowired
    private SpecialAfterSaleReviewReposity reviewReposity;


    @Autowired
    private SpecialAfterSaleReviewNodeRepository nodeRepository;

    @Autowired
    private SpecialAfterSaleReviewPositionRepository positionRepository;

    @Autowired
    private PassportFeignManager passportFeignManager;

    @Autowired
    private UserManager userManager;



    /**
     * ??????/??????????????????
     *
     * @param req
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveOrUpdateApproverConfig(SpecialAfterSaleApplyConfigRequest req) {
        Integer configId = req.getConfigId();
        if (configId == null) {
            // ??????????????????
            this.addApproverConfig(req);
        } else {
            // ??????????????????
            this.updateApproverConfig(req);
        }

    }

    /**
     * ??????????????????
     *
     * @param req
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateApproverConfig(SpecialAfterSaleApplyConfigRequest req) {
        // ??????????????????????????????
        this.deleteConfigById(req.getConfigId());
        // ??????????????????????????????
        this.addApproverConfig(req);
    }

    /**
     * ??????????????????
     *
     * @param configId ??????id
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConfigById(Integer configId) {
        QSpecialAfterSaleReviewEntity reviewEntity = QSpecialAfterSaleReviewEntity.specialAfterSaleReviewEntity;
        QSpecialAfterSaleReviewNodeEntity nodeEntity = QSpecialAfterSaleReviewNodeEntity.specialAfterSaleReviewNodeEntity;
        QSpecialAfterSaleReviewPositionEntity positionEntity = QSpecialAfterSaleReviewPositionEntity.specialAfterSaleReviewPositionEntity;
        queryFactory.delete(reviewEntity)
                .where(reviewEntity.id.eq(configId))
                .execute();
        queryFactory.delete(nodeEntity)
                .where(nodeEntity.configId.eq(configId))
                .execute();
        queryFactory.delete(positionEntity)
                .where(positionEntity.configId.eq(configId))
                .execute();
    }

    /**
     * ??????????????????
     *
     * @param req
     */
    @Transactional(rollbackFor = Exception.class)
    public void addApproverConfig(SpecialAfterSaleApplyConfigRequest req) {
        Integer userId = UserHelper.getUserId();
        LocalDateTime localDateTime = LocalDateTime.now();
        SpecialAfterSaleReviewEntity reviewEntity = new SpecialAfterSaleReviewEntity();
        // ????????????????????????
        reviewEntity.setName(req.getConfigName());
        reviewEntity.setCreatedBy(userId);
        reviewEntity.setCreatedAt(localDateTime);
        reviewEntity.setLastUpdateAt(localDateTime);
        reviewEntity.setLastUpdateBy(userId);
        reviewEntity.setStatus(YesOrNoEnum.YES.getCode());
        reviewEntity.setCcIds(StringUtils.join(req.getCcList(), Symbol.COMMA.getValue()));
        reviewEntity.setDescription(req.getDescription());
        reviewEntity.setGroupId(req.getGroupId());
        Integer reviewId = reviewRepository.save(reviewEntity).getId();
        // ?????????????????????????????????
        List<SpecialAfterSaleReviewNodeEntity> nodeEntityList = new ArrayList<>(8);
        req.getApproverList().forEach(approver -> {
            SpecialAfterSaleReviewNodeEntity reviewNodeEntity = new SpecialAfterSaleReviewNodeEntity();
            reviewNodeEntity.setConfigId(reviewId);
            reviewNodeEntity.setCreatedAt(localDateTime);
            reviewNodeEntity.setCreatedBy(userId);
            reviewNodeEntity.setLastUpdateAt(localDateTime);
            reviewNodeEntity.setLastUpdateBy(userId);
            reviewNodeEntity.setFlag(approver.getFlag());
            reviewNodeEntity.setSort(approver.getSort());
            reviewNodeEntity.setUseQuota(approver.getUseQuota());
            reviewNodeEntity.setName(approver.getName());
            reviewNodeEntity.setStatus(YesOrNoEnum.YES.getCode());
            Integer flag = approver.getFlag();
            reviewNodeEntity.setFlag(flag);
            // ???????????????(1???????????????,2??????)
            if (flag == SpecialAfterSaleApproverTypeEnum.POSITION.getState()) {
                List<String> positions = approver.getPositions();
                if (positions == null) {
                    throw new GlobalRuntimeException(ResponseCode.ERROR.getCode(), "?????????????????????????????????????????????");
                }
                reviewNodeEntity.setPositions(StringUtils.join(positions, Symbol.COMMA.getValue()));
            } else if (flag == SpecialAfterSaleApproverTypeEnum.SINGLE.getState()) {
                Integer approverUserId = approver.getId();
                if (approverUserId == null) {
                    throw new GlobalRuntimeException(ResponseCode.ERROR.getCode(), "?????????????????????????????????id????????????");
                }
                reviewNodeEntity.setUserId(approver.getId());
            } else {
                throw new GlobalRuntimeException(ResponseCode.ERROR.getCode(), "???????????????????????????");
            }
            nodeEntityList.add(reviewNodeEntity);

        });
        reviewNodeRepository.saveAll(nodeEntityList);

        // ??????position???
        List<String> positions = req.getPositions();
        if (positions == null) {
            throw new GlobalRuntimeException(ResponseCode.ERROR.getCode(), "?????????????????????????????????????????????");
        }
        List<SpecialAfterSaleReviewPositionEntity> positionEntityList = new ArrayList<>(4);
        positions.forEach(position -> {
            SpecialAfterSaleReviewPositionEntity reviewPositionEntity = new SpecialAfterSaleReviewPositionEntity();
            reviewPositionEntity.setConfigId(reviewId);
            reviewPositionEntity.setCreatedAt(localDateTime);
            reviewPositionEntity.setPosition(position);
            positionEntityList.add(reviewPositionEntity);
        });
        reviewPositionRepository.saveAll(positionEntityList);
    }


    /**
     * ???????????????????????????????????????
     * @param status
     * @param id
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void enableOrDisable(Integer status, Integer id) {
        nodeReposity.updateReviewNodeStatus(status, id);
        reviewRepository.updateReviewStatus(status, id);
    }


    /**
     * ????????????????????????
     * @param
     * @return
     */
    @Override
    public List<SpecialAfterSaleApprovalRulesDTO> getSpecialAfterSaleApprovalRulesDetails(Integer status, Pageable pageable) {
        //????????????????????????
        Page<SpecialAfterSaleReviewEntity> listPage = reviewReposity.findAllByStatus(status, pageable);
        List<SpecialAfterSaleReviewEntity> entityList = listPage.getContent();
        List<SpecialAfterSaleApprovalRulesDTO> results = new ArrayList<>();
        // ??????????????????
        for (SpecialAfterSaleReviewEntity entity : entityList) {
            SpecialAfterSaleApprovalRulesDTO rulesDTO = new SpecialAfterSaleApprovalRulesDTO();
            //??????????????????
            rulesDTO.setLastUpdateAt(entity.getLastUpdateAt());
            //??????group-id???????????????????????????
            Integer groupId = entity.getGroupId();
            Map<Integer, UserGroupSimpleDTO> map = userManager.searchUserGroupFromCache(Collections.singleton(groupId));
            for (UserGroupSimpleDTO values : map.values()) {
                for (UserGroupSimpleDTO.GroupInfoVO group : values.getGroups()) {
                    if (groupId.equals(group.getGroupId())) {
                        // ?????????
                        rulesDTO.setGroupName(group.getGroupName());
                        break;
                    }
                }
            }
            //??????configId?????????????????????
            Integer configId = entity.getId();
            SpecialAfterSaleReviewPositionEntity position = positionRepository.findById(configId.intValue())
                    .orElseThrow(() -> new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "???????????????????????????"));
            rulesDTO.setPosition(position.getPosition());

            //??????configId???????????????
            Optional<SpecialAfterSaleReviewNodeEntity> reviewNodeEntity = nodeRepository.findById(configId.intValue());
            Integer flag = reviewNodeEntity.get().getFlag();
            //??????falg=2,???????????????????????????????????????
            if (flag == 2) {
                //???????????????
                Integer userId = reviewNodeEntity.get().getUserId();
                PassportUserInfoDTO userInfoFromRedis = passportFeignManager.getUserInfoFromRedis(userId);
                rulesDTO.setNickname(userInfoFromRedis.getNickname());
            } else {
                //??????
                rulesDTO.setPositions(reviewNodeEntity.get().getPositions());
            }
            //??????ccIds
            String[] splitCcids = entity.getCcIds().split(",");
            List<Integer> idsList = Stream.of(splitCcids).map(Integer::valueOf).collect(Collectors.toList());
            //??????ccIds??????????????????
            List<PassportUserInfoDTO> userInfoListFromRedis = passportFeignManager.getUserInfoListFromRedis(idsList);
            List<String> nicknameList = userInfoListFromRedis.stream().map(PassportUserInfoDTO::getNickname).collect(Collectors.toList());
            rulesDTO.setCcNickname(nicknameList);
            results.add(rulesDTO);
        }
        return results;

    }



}
