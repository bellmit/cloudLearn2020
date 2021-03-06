package com.ruigu.rbox.workflow.service.impl;

import com.ruigu.rbox.cloud.kanai.util.JsonUtil;
import com.ruigu.rbox.workflow.exceptions.GlobalRuntimeException;
import com.ruigu.rbox.workflow.exceptions.VerificationFailedException;
import com.ruigu.rbox.workflow.feign.PassportFeignClient;
import com.ruigu.rbox.workflow.model.ActionConstants;
import com.ruigu.rbox.workflow.model.LightningReturnMessageInfoMap;
import com.ruigu.rbox.workflow.model.ServerResponse;
import com.ruigu.rbox.workflow.model.client.AbstractReconnectWebSocketClient;
import com.ruigu.rbox.workflow.model.dto.*;
import com.ruigu.rbox.workflow.model.entity.ChatWebSocketUser;
import com.ruigu.rbox.workflow.model.entity.LightningIssueGroupEntity;
import com.ruigu.rbox.workflow.model.entity.WsApiLogEntity;
import com.ruigu.rbox.workflow.model.enums.LightningApplyStatus;
import com.ruigu.rbox.workflow.model.enums.ResponseCode;
import com.ruigu.rbox.workflow.model.enums.TransferTypeEnum;
import com.ruigu.rbox.workflow.repository.LightningIssueGroupRepository;
import com.ruigu.rbox.workflow.service.ChatWebSocketService;
import com.ruigu.rbox.workflow.service.WsApiLogService;
import com.ruigu.rbox.workflow.supports.WebSocketClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author liqingtian
 * @date 2020/01/08 15:47
 */
@Slf4j
@Service
public class ChatWebSocketServiceImpl implements ChatWebSocketService {

    @Value("${rbox.chat.websocket.robot}")
    private String robotName;

    @Resource
    private WsApiLogService wsApiLogService;

    @Resource
    private AbstractReconnectWebSocketClient reconnectWebSocketClient;

    @Resource
    private PassportFeignClient passportFeignClient;

    @Resource
    private LightningIssueGroupRepository lightningIssueGroupRepository;

    @Resource
    private LightningReturnMessageInfoMap lightningReturnMessageInfoMap;

    @Value("${rbox.workflow.lightning.close.group.enable}")
    private boolean closeGroupEnable;

    @Override
    public void login(String userId) {
        if (userId == null) {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(),
                    "??????????????????ID??????");
        }
        SendWebSocketMessageDTO message = new SendWebSocketMessageDTO();
        SendWebSocketMessageDTO.MessageContent content = new SendWebSocketMessageDTO.MessageContent();
        content.setFromConnName("0-" + userId);
        message.setContent(content);
        message.setAction(ActionConstants.LOGIN);
        reconnectWebSocketClient.send(JsonUtil.toJsonString(message));
    }

    @Override
    public void loginRobot() {
        RobotAndWebSocketMessageDTO info = WebSocketClientUtil.getRobotLoginMessage(robotName);
        SendWebSocketMessageDTO message = info.getSendWebSocketMessageDTO();
        String loginRobotName = info.getLoginRobotName();
        try {
            reconnectWebSocketClient.send(JsonUtil.toJsonString(message));
            log.info("| - > [ WebSocket ] ??????????????? {}", loginRobotName);
        } catch (Exception e) {
            log.info("| - > [ WebSocket ] ????????????????????? ??????????????? {}", e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void buildGroup(List<BuildGroupDTO> buildGroupList) {
        String errorHead = "| - > [ WebSocket ] - ?????????????????? - ";
        if (CollectionUtils.isEmpty(buildGroupList)) {
            logAndThrowException(errorHead, "???????????????????????????");
        }
        // ??????
        SendWebSocketMessageDTO sendMessage;
        SendWebSocketMessageDTO.MessageContent content;
        // ?????????????????????????????????????????????????????????????????????
        Set<Integer> userIds = buildGroupList.stream()
                .map(BuildGroupDTO::getMasterId)
                .distinct()
                .collect(Collectors.toSet());
        buildGroupList.parallelStream()
                .map(BuildGroupDTO::getMemberIds)
                .forEach(members -> {
                    if (CollectionUtils.isNotEmpty(members)) {
                        userIds.addAll(members);
                    }
                });
        if (CollectionUtils.isEmpty(userIds)) {
            logAndThrowException(errorHead, "??????????????????????????????");
        }
        for (BuildGroupDTO group : buildGroupList) {
            Integer issueId = group.getIssueId();
            Integer masterId = group.getMasterId();
            Long messageId = System.currentTimeMillis();
            content = new SendWebSocketMessageDTO.MessageContent();
            content.setRandomCode(messageId);
            content.setAppId(0);
            content.setFromConnName("0-" + masterId);
            content.setGroupTitle("?????????????????? [ No." + issueId + " ]");
            List<ChatWebSocketUser> userList = new ArrayList<>();
            List<Integer> members = group.getMemberIds();
            if (CollectionUtils.isNotEmpty(members)) {
                members.forEach(memberId -> {
                    ChatWebSocketUser user = new ChatWebSocketUser();
                    user.setConnName("0-" + memberId);
                    userList.add(user);
                });
            }
            content.setUserList(userList);
            if (StringUtils.isBlank(robotName)) {
                logAndThrowException(errorHead, "???????????????????????????????????????Name is Empty");
            }
            content.setRobotConnName(robotName);
            // ?????????????????????
            sendMessage = new SendWebSocketMessageDTO();
            sendMessage.setContent(content);
            sendMessage.setAction(ActionConstants.EXT_ROBOT_GROUP);
            // ????????????
            log(messageId, issueId, sendMessage.getParam(), masterId);
            // ??????????????????
            try {
                reconnectWebSocketClient.send(JsonUtil.toJsonString(sendMessage));
            } catch (Exception e) {
                logAndThrowException(errorHead, "??????????????????????????????????????????????????? ");
            }
            // ??????????????????websocket??????
            ReturnWebSocketMessageDTO returnMessage = lightningReturnMessageInfoMap.getMessage(messageId);
            if (returnMessage == null) {
                logAndThrowException(errorHead, "???????????????????????????????????? ");
            } else {
                Integer result = returnMessage.getResult();
                if (result != ResponseCode.SUCCESS.getCode()) {
                    logAndThrowException(errorHead, "??????????????????????????????");
                }
                Long groupId = returnMessage.getGroupId();
                if (groupId == null) {
                    logAndThrowException(errorHead, "??????????????????????????????????????????id ");
                }
                LightningIssueGroupEntity groupEntity = new LightningIssueGroupEntity();
                groupEntity.setIssueId(issueId);
                groupEntity.setGroupId(groupId.toString());
                groupEntity.setGroupName(returnMessage.getGroupTitle());
                groupEntity.setCreatedOn(new Date());
                lightningIssueGroupRepository.save(groupEntity);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addUserToGroup(Integer issueId, Long groupId, Integer userId, Integer addUserId) {
        String errorHead = "| - > [ WebSocket ] - ?????????????????? - ";
        if (groupId == null || addUserId == null) {
            logAndThrowException(errorHead, "???????????????????????????");
        }
        // ?????????????????????
        SendWebSocketMessageDTO sendMessage = new SendWebSocketMessageDTO();
        sendMessage.setAction(ActionConstants.ROBOT_ADD_USER);
        // ??????????????????
        Long messageId = System.currentTimeMillis();
        SendWebSocketMessageDTO.MessageContent content = WebSocketClientUtil.buildRobotMessage(robotName);
        content.setRandomCode(messageId);
        content.setGroupId(groupId);
        // add user??????
        ChatWebSocketUser addUser = new ChatWebSocketUser();
        addUser.setConnName("0-" + addUserId);
        content.setAddUserList(Collections.singletonList(addUser));
        sendMessage.setContent(content);
        // ??????
        log(messageId, issueId, sendMessage.getParam(), 0);
        // ????????????????????????
        try {
            reconnectWebSocketClient.send(JsonUtil.toJsonString(sendMessage));
            // ??????????????????????????????
            ReturnWebSocketMessageDTO returnMessage = lightningReturnMessageInfoMap.getMessage(messageId);
            if (returnMessage == null) {
                logAndThrowException(errorHead, "???????????????????????????????????? ");
            } else {
                Integer result = returnMessage.getResult();
                if (result != ResponseCode.SUCCESS.getCode()) {
                    logAndThrowException(errorHead, "?????????????????????????????? ");
                }
            }
            sendActionMessage(issueId, groupId, addUserId, LightningApplyStatus.ADD_MEMBER);
        } catch (Exception e) {
            logAndThrowException(errorHead, "???????????????????????????????????????????????????");
        }
    }

    @Override
    public void sendMessage(Integer issueId, Long groupId, String content, String action) {
        String errorHead = "| - > [ WebSocket ] - " + action + " - ";
        if (issueId == null || groupId == null || StringUtils.isBlank(content)) {
            log.error(errorHead + "??????????????????");
            return;
        }
        // ?????????????????????
        SendWebSocketMessageDTO sendMessage = new SendWebSocketMessageDTO();
        sendMessage.setAction(ActionConstants.ROBOT_SEND_GROUP_MESSAGE);
        // ????????????
        SendWebSocketMessageDTO.MessageContent messageContent = WebSocketClientUtil.buildRobotMessage(robotName);
        messageContent.setGroupId(groupId);
        messageContent.setContent(content);
        sendMessage.setContent(messageContent);
        // ??????
        log(null, issueId, sendMessage.getParam(), 0);
        // ????????????
        try {
            reconnectWebSocketClient.send(JsonUtil.toJsonString(sendMessage));
        } catch (Exception e) {
            log.error(errorHead + "??????????????????");
        }
    }

    @Override
    public void sendActionMessage(Integer issueId, Long groupId, Integer userId, LightningApplyStatus status) {
        String action = null;
        switch (status) {
            case TO_BE_CONFIRMED:
                action = "?????????????????????????????????";
                break;
            case RESOLVED:
                action = "????????????????????????";
                break;
            case UNRESOLVED:
                action = "????????????????????????";
                break;
            case REVOKED:
                action = "??????????????????";
                break;
            case URGE:
                action = "??????????????????";
                break;
            case ACCEPTING:
                action = "?????????????????????";
                break;
            case RESUBMIT:
                action = "????????????????????????";
                break;
            case ADD_MEMBER:
                action = "????????????";
                break;
            default:
                break;
        }
        if (StringUtils.isBlank(action)) {
            return;
        }
        try {
            Map<String, PassportUserInfoDTO> userInfoMap = getUserInfoMap(Collections.singletonList(userId));
            String userName = userInfoMap.get(userId.toString()).getNickname();
            String content = userName + action;
            sendMessage(issueId, groupId, content, status.getDesc());
        } catch (Exception e) {
            log.error("?????????????????????????????????{}", e);
        }
    }

    @Override
    public void sendAssociateMessage(Integer issueId, Long groupId, Integer userId, Integer assigneeId, Integer transferType) {
        try {
            Map<String, PassportUserInfoDTO> userInfoMap = getUserInfoMap(Arrays.asList(userId, assigneeId));
            String assigneeName = userInfoMap.get(assigneeId.toString()).getNickname();
            String userName = null;
            if (TransferTypeEnum.LEAVE_TRANSFER.getCode().equals(transferType)) {
                userName = "???????????????????????????";
            } else if (TransferTypeEnum.INVITE_TRANSFER.getCode().equals(transferType)){
                userName = userInfoMap.get(userId.toString()).getNickname() + "???????????????????????????";
            } else if (TransferTypeEnum.SOLVER_TRANSFER.getCode().equals(transferType)){
                userName = userInfoMap.get(userId.toString()).getNickname() + "?????????????????????";
            } else {
                throw new RuntimeException("transferType??????");
            }
            String content = userName + assigneeName;
            sendMessage(issueId, groupId, content, "????????????");
        } catch (Exception e) {
            log.error("?????????????????????????????????{}", e);
        }
    }

    @Override
    public void closeGroup(Long groupId, Integer issueId) {
        if (!closeGroupEnable) {
            return;
        }
        String errorHead = "| - > [ WebSocket ] - ???????????? - ";
        if (groupId == null) {
            log.error(errorHead + "??????id??????");
            return;
        }
        // ?????????????????????
        SendWebSocketMessageDTO sendMessage = new SendWebSocketMessageDTO();
        sendMessage.setAction(ActionConstants.ROBOT_CLOSE_GROUP);
        // ??????????????????
        Long messageId = System.currentTimeMillis();
        SendWebSocketMessageDTO.MessageContent messageContent = WebSocketClientUtil.buildRobotMessage(robotName);
        messageContent.setGroupId(groupId);
        messageContent.setRandomCode(messageId);
        sendMessage.setContent(messageContent);
        // ??????
        log(messageId, issueId, sendMessage.getParam(), 0);
        try {
            reconnectWebSocketClient.send(JsonUtil.toJsonString(sendMessage));
        } catch (Exception e) {
            log.error(errorHead + "??????????????????");
        }
    }

    private Map<String, PassportUserInfoDTO> getUserInfoMap(Collection<Integer> userList) {
        ServerResponse<List<PassportUserInfoDTO>> userInfoResponse = passportFeignClient.getUserMsgByIds(userList);
        if (userInfoResponse.getCode() != ResponseCode.SUCCESS.getCode()) {
            logAndThrowException("", "???????????????????????????????????????????????????");
        }
        Map<String, PassportUserInfoDTO> userInfoMap = new HashMap<>(16);
        if (CollectionUtils.isNotEmpty(userInfoResponse.getData())) {
            List<PassportUserInfoDTO> userInfoList = userInfoResponse.getData();
            userInfoList.forEach(user -> userInfoMap.put(String.valueOf(user.getId()), user));
        }
        return userInfoMap;
    }

    private void log(Long messageId, Integer issueId, String content, Integer createdBy) {
        WsApiLogEntity wsLog = new WsApiLogEntity();
        if (messageId != null) {
            wsLog.setMessageId(messageId.toString());
        }
        wsLog.setSendMessage(content);
        wsLog.setIssueId(issueId);
        wsLog.setCreatedBy(createdBy);
        wsLog.setCreatedOn(LocalDateTime.now());
        wsApiLogService.insertLog(wsLog);
    }

    private void logAndThrowException(String errorHead, String errMsg) {
        log.error(errorHead + errMsg);
        throw new GlobalRuntimeException(ResponseCode.INTERNAL_ERROR.getCode(), errMsg);
    }
}
