package com.ruigu.rbox.workflow.service.impl;

import com.ruigu.rbox.cloud.kanai.security.UserHelper;
import com.ruigu.rbox.workflow.constants.RedisKeyConstants;
import com.ruigu.rbox.workflow.exceptions.GlobalRuntimeException;
import com.ruigu.rbox.workflow.model.entity.ReliableMqLogEntity;
import com.ruigu.rbox.workflow.model.enums.ResponseCode;
import com.ruigu.rbox.workflow.model.enums.TxMessageStatusEnum;
import com.ruigu.rbox.workflow.repository.GuaranteeSuccessRabbitmqMessageRepository;
import com.ruigu.rbox.workflow.service.GuaranteeSuccessMqMessageService;
import com.ruigu.rbox.workflow.service.QuestNoticeService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author liqingtian
 * @date 2020/07/21 10:32
 */
@Slf4j
@Service
public class GuaranteeSuccessMqMessageServiceImpl implements GuaranteeSuccessMqMessageService {

    private static final LocalDateTime END = LocalDateTime.of(2999, 1, 1, 0, 0, 0);

    private static final long DEFAULT_INIT_BACKOFF = 10L;

    private static final int DEFAULT_BACKOFF_FACTOR = 2;

    private static final int DEFAULT_MAX_RETRY_TIMES = 5;

    @Resource
    private GuaranteeSuccessRabbitmqMessageRepository guaranteeSuccessRabbitmqMessageRepository;

    @Resource(name = "guaranteeSuccessRabbitTemplate")
    private RabbitTemplate rabbitTemplate;

    @Resource(name = "rboxGuaranteeSuccessAmqpAdmin")
    private AmqpAdmin amqpAdmin;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private QuestNoticeService questNoticeService;

    /**
     * exchange ??????
     */
    private final RabbitTemplate.ConfirmCallback confirmCallback = (correlationData, ack, cause) -> {

        // ??????????????????
        String uuid = correlationData == null ? "null" : correlationData.getId();

        // redis?????????
        String recordIdStr = stringRedisTemplate.opsForValue().get(String.format(RedisKeyConstants.MQ_ID_UUID_CORRELATION_KEY, uuid));
        Long recordId = Long.valueOf(recordIdStr);

        if (!ack) {

            log.error("???????????????????????????");
            log.error("correlationData : {}", correlationData);
            log.error("???????????? : {}", cause);

            // ??????????????????
            markFail(recordId);

            // ?????????
            questNoticeService.sendWarnNotice("?????????MQ??????????????????\n" +
                    "??????MQ?????? - ?????????????????????\n" +
                    "record ID???" + recordId + "\n" +
                    "?????????" + cause
            );
        } else {

            // ??????????????????
            markSuccess(recordId);
        }
    };

    /**
     * ????????????
     */
    private final RabbitTemplate.ReturnCallback returnCallback = (message, replyCode, replyText, exchange, routingKey) -> {

        log.error("????????????????????????");
        log.error("returnedMessage : {}", message);
        log.error("exchange : {}", exchange);
        log.error("routingKey : {}", routingKey);

        // ?????????
        questNoticeService.sendWarnNotice("?????????MQ??????????????????\n" +
                "??????MQ?????? - ??????????????????\n" +
                "exchange???" + exchange + "\n" +
                "routingKey???" + routingKey + "\n" +
                "returnedMessage" + message);
    };

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveRecord(ReliableMqLogEntity record) {
        record.setMessageStatus(TxMessageStatusEnum.PENDING.getStatus());
        LocalDateTime now = LocalDateTime.now();
        record.setNextScheduleTime(calculateNextScheduleTime(now, DEFAULT_INIT_BACKOFF, DEFAULT_BACKOFF_FACTOR, 0));
        record.setCurrentRetryTimes(0);
        record.setInitBackoff(DEFAULT_INIT_BACKOFF);
        record.setBackoffFactor(DEFAULT_BACKOFF_FACTOR);
        record.setMaxRetryTimes(DEFAULT_MAX_RETRY_TIMES);
        record.setCreatedAt(now);
        record.setLastUpdatedAt(now);
        Integer userId = UserHelper.getUserId() == null ? 0 : UserHelper.getUserId();
        record.setCreatedBy(userId);
        record.setLastUpdatedBy(userId);
        guaranteeSuccessRabbitmqMessageRepository.save(record);
    }

    @Override
    public void send(ReliableMqLogEntity record) {
        amqpAdmin.declareExchange(new TopicExchange(record.getExchangeName()));

        // ????????????
        rabbitTemplate.setConfirmCallback(confirmCallback);

        // ??????
        rabbitTemplate.setReturnCallback(returnCallback);

        // ?????????????????????????????? ?????????uuid??????????????????uuid???mqLogId???????????????
        String uuid = UUID.randomUUID().toString();

        // ???????????????redis
        stringRedisTemplate.opsForValue().set(String.format(RedisKeyConstants.MQ_ID_UUID_CORRELATION_KEY, uuid),
                record.getId().toString(), 10, TimeUnit.MINUTES);

        // ????????????
        rabbitTemplate.convertAndSend(record.getExchangeName(), record.getRoutingKey(), record.getContent(), new CorrelationData(uuid));
    }

    @Override
    public void scanRecordAndRetry() {

        // ?????????????????????????????????
        // ???????????????????????? 1.????????? ???????????? 2.?????????
        List<ReliableMqLogEntity> notSuccessRecords = guaranteeSuccessRabbitmqMessageRepository.findAllNeedRetryRecord(
                Arrays.asList(TxMessageStatusEnum.PENDING.getStatus(), TxMessageStatusEnum.FAIL.getStatus()));
        if (CollectionUtils.isEmpty(notSuccessRecords)) {
            return;
        }
        for (ReliableMqLogEntity record : notSuccessRecords) {
            if (TxMessageStatusEnum.PENDING.getStatus().equals(record.getMessageStatus())) {
                // ???????????????????????? ??????????????????????????? ???????????????????????????10?????? ??????
                if (record.getCreatedAt().plusMinutes(10).isAfter(LocalDateTime.now())) {
                    return;
                }
            }
            // ?????? - ??????
            send(record);
        }

    }

    @Override
    public void failRecordManualRetry(List<Long> recordIds, Boolean maxLimit) {

        List<ReliableMqLogEntity> retryRecord = new ArrayList<>();


        if (CollectionUtils.isEmpty(retryRecord)) {
            throw new GlobalRuntimeException(ResponseCode.CONDITION_EXECUTE_ERROR.getCode(), "??????????????????");
        }

    }


    private LocalDateTime calculateNextScheduleTime(LocalDateTime base,
                                                    long initBackoff,
                                                    long backoffFactor,
                                                    long round) {
        double delta = initBackoff * Math.pow(backoffFactor, round);
        return base.plusSeconds((long) delta);
    }

    private void markSuccess(Long recordId) {
        ReliableMqLogEntity record = guaranteeSuccessRabbitmqMessageRepository.findById(recordId).orElse(null);
        if (record == null) {
            log.error(" mark success ! but record id is lost , id:{}", recordId);
            return;
        }
        // ???????????????????????????????????????
        record.setNextScheduleTime(END);
        record.setCurrentRetryTimes(record.getCurrentRetryTimes().compareTo(record.getMaxRetryTimes()) >= 0 ?
                record.getMaxRetryTimes() : record.getCurrentRetryTimes() + 1);
        record.setMessageStatus(TxMessageStatusEnum.SUCCESS.getStatus());
        record.setLastUpdatedAt(LocalDateTime.now());
        guaranteeSuccessRabbitmqMessageRepository.save(record);
    }

    private void markFail(Long recordId) {
        ReliableMqLogEntity record = guaranteeSuccessRabbitmqMessageRepository.findById(recordId).orElse(null);
        if (record == null) {
            log.error(" mark success ! but record id is lost , id:{}", recordId);
            return;
        }
        record.setCurrentRetryTimes(record.getCurrentRetryTimes().compareTo(record.getMaxRetryTimes()) >= 0 ?
                record.getMaxRetryTimes() : record.getCurrentRetryTimes() + 1);
        // ??????????????????????????????
        LocalDateTime nextScheduleTime = calculateNextScheduleTime(
                record.getNextScheduleTime(),
                record.getInitBackoff(),
                record.getBackoffFactor(),
                record.getCurrentRetryTimes()
        );
        record.setNextScheduleTime(nextScheduleTime);
        record.setMessageStatus(TxMessageStatusEnum.FAIL.getStatus());
        record.setLastUpdatedAt(LocalDateTime.now());
        guaranteeSuccessRabbitmqMessageRepository.save(record);
    }
}
