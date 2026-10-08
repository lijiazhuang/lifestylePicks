package com.lifestylepicks.trade.mq;

import com.lifestylepicks.api.message.SeckillOrderMessage;
import com.lifestylepicks.trade.config.TradeProperties;
import com.lifestylepicks.trade.service.OrderDeliveryStore;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="lifestylepicks.trade",name="mq-enabled",havingValue="true",matchIfMissing=true)
public class OrderMessagePublisher {
    private static final Logger LOG=LoggerFactory.getLogger(OrderMessagePublisher.class);
    private final RocketMQTemplate rocketMQ;
    private final OrderDeliveryStore delivery;
    private final TradeProperties properties;
    public OrderMessagePublisher(RocketMQTemplate rocketMQ,OrderDeliveryStore delivery,TradeProperties properties){
        this.rocketMQ=rocketMQ;this.delivery=delivery;this.properties=properties;
    }
    @Scheduled(fixedDelayString="${lifestylepicks.trade.dispatch-interval:1000}")
    public void dispatch(){
        for(String id:delivery.due()){
            try{SeckillOrderMessage message=delivery.pending(id);if(message!=null){publish(message);}}
            catch(RuntimeException unavailable){LOG.warn("订单 {} 待投递记录暂不可用，保留补发：{}",id,unavailable.getClass().getSimpleName());}
        }
    }
    public void publish(SeckillOrderMessage message){
        message.validate();
        try{
            SendResult result=rocketMQ.syncSend(properties.getOrderTopic(),MessageBuilder.withPayload(message)
                    .setHeader(RocketMQHeaders.KEYS,message.getOrderId().toString()).build(),properties.getSendTimeout().toMillis());
            if(result==null || result.getSendStatus()!=SendStatus.SEND_OK){
                throw new IllegalStateException("RocketMQ 未确认发送成功");
            }
            delivery.sent(message.getOrderId());
        }catch(RuntimeException uncertain){
            // 超时可能已经送达，不能退库存；补发同一订单由消费者幂等处理。
            // 保留完整异常堆栈，区分 Topic 路由、Broker 地址、连接及权限问题。
            LOG.warn("订单 {} 投递到 {} 未确认，保留预留等待补发",message.getOrderId(),properties.getOrderTopic(),uncertain);
            delivery.defer(message.getOrderId());
        }
    }
}
