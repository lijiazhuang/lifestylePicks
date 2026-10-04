package com.lifestylepicks.trade.mq;

import com.lifestylepicks.api.message.SeckillOrderMessage;
import com.lifestylepicks.trade.service.OrderMessageHandler;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="lifestylepicks.trade",name="mq-enabled",havingValue="true",matchIfMissing=true)
@RocketMQMessageListener(topic="${lifestylepicks.trade.order-topic}",consumerGroup="${rocketmq.consumer.group}",
        consumeThreadNumber=4,maxReconsumeTimes=16)
public class SeckillOrderListener implements RocketMQListener<SeckillOrderMessage>,RocketMQPushConsumerLifecycleListener {
    private final OrderMessageHandler handler;
    public SeckillOrderListener(OrderMessageHandler handler){this.handler=handler;}
    @Override public void onMessage(SeckillOrderMessage message){handler.handle(message);}
    @Override public void prepareStart(DefaultMQPushConsumer consumer){
        // 新组读取已有消息；已有组仍按 Broker 保存的消费进度继续。
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }
}
