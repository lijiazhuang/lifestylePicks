package com.lifestylepicks.trade.mq;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 消费重试耗尽后的死信仅记录告警，订单与库存由人工核对处理。 */
@Component
@ConditionalOnProperty(prefix="lifestylepicks.trade", name="mq-enabled", havingValue="true", matchIfMissing=true)
@RocketMQMessageListener(
        topic="%DLQ%${rocketmq.consumer.group}",
        consumerGroup="${rocketmq.consumer.group}_dead_letter_alarm",
        consumeThreadNumber=1)
public class SeckillOrderDeadLetterListener implements RocketMQListener<MessageExt>, RocketMQPushConsumerLifecycleListener {

    private static final Logger LOG = LoggerFactory.getLogger(SeckillOrderDeadLetterListener.class);

    @Override
    public void onMessage(MessageExt message) {
        // 不反序列化为业务 DTO，格式错误的消息也必须能够记录并交给人工处理。
        String body = message.getBody() == null ? "<empty>"
                : new String(message.getBody(), StandardCharsets.UTF_8).replace("\r", "\\r").replace("\n", "\\n");
        LOG.error("SECKILL_ORDER_DEAD_LETTER 秒杀订单死信，请人工核对数据库与 Redis 后处理："
                        + "topic={}, originalTopic={}, msgId={}, originalMsgId={}, keys={}, reconsumeTimes={}, "
                        + "queueId={}, queueOffset={}, bornTimestamp={}, body={}",
                message.getTopic(), message.getProperty(MessageConst.PROPERTY_RETRY_TOPIC), message.getMsgId(),
                message.getProperty(MessageConst.PROPERTY_ORIGIN_MESSAGE_ID), message.getKeys(), message.getReconsumeTimes(),
                message.getQueueId(), message.getQueueOffset(), message.getBornTimestamp(), body);
        // 正常返回即确认这条告警已处理，不自动重放消息或修改订单、库存。
    }

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        // 新告警组从第一条死信开始；已有组重启后仍从 Broker 保存的消费进度继续。
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }
}
