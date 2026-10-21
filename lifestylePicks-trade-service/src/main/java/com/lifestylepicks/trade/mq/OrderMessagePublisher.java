package com.lifestylepicks.trade.mq;

import com.lifestylepicks.api.message.SeckillOrderMessage;
import com.lifestylepicks.trade.config.TradeProperties;
import com.lifestylepicks.trade.service.OrderDeliveryStore;
import com.lifestylepicks.trade.service.PublishRetryResult;
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
        }catch(RuntimeException uncertain){
            // 超时可能已经送达，不能退库存；补发同一订单由消费者幂等处理。
            // 保留完整异常堆栈，区分 Topic 路由、Broker 地址、连接及权限问题。
            LOG.warn("订单 {} 投递到 {} 未确认，保留预留等待补发",message.getOrderId(),properties.getOrderTopic(),uncertain);
            try{
                PublishRetryResult retry=delivery.defer(message.getOrderId());
                if(retry.isAlert()){
                    LOG.error("SECKILL_ORDER_PUBLISH_RETRY_ALERT 重发失败达到阈值，保留消息并继续退避重试："
                                    +"topic={}, outbox={}, windowMs={}, windowFailures={}, threshold={}, pendingCount={}, "
                                    +"orderId={}, publishFailCount={}, nextRetryAtMillis={}",
                            properties.getOrderTopic(),properties.getOutboxKey(),properties.getPublishRetryAlertWindow().toMillis(),
                            retry.getWindowFailureCount(),properties.getPublishRetryAlertThreshold(),retry.getPendingCount(),
                            message.getOrderId(),retry.getFailureCount(),retry.getNextRetryAtMillis());
                }
            }catch(RuntimeException schedulingFailure){
                LOG.error("SECKILL_ORDER_PUBLISH_DEFER_FAILED 订单 {} 重试计数或调度失败，保留原待投递记录，需检查 Redis",
                        message.getOrderId(),schedulingFailure);
            }
            return;
        }
        // Broker 已确认成功。清理失败不属于 MQ 发送失败，不能增加重试次数或触发重发告警。
        try{
            delivery.sent(message.getOrderId());
        }catch(RuntimeException cleanupFailure){
            LOG.warn("SECKILL_ORDER_PUBLISH_CLEANUP_FAILED 订单 {} 已获 Broker 确认，但待投递记录清理失败，保留记录待检查",
                    message.getOrderId(),cleanupFailure);
        }
    }
}
