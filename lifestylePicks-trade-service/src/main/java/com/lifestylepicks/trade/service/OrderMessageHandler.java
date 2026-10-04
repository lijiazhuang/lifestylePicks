package com.lifestylepicks.trade.service;

import com.lifestylepicks.api.message.SeckillOrderMessage;
import com.lifestylepicks.trade.entity.VoucherOrder;
import org.springframework.stereotype.Service;

@Service
public class OrderMessageHandler {
    private final OrderWriter writer;
    private final OrderDeliveryStore delivery;
    public OrderMessageHandler(OrderWriter writer,OrderDeliveryStore delivery){this.writer=writer;this.delivery=delivery;}
    public void handle(SeckillOrderMessage message){
        if(message==null){throw new IllegalArgumentException("订单消息不能为空");}
        message.validate();
        if("FAILED".equals(delivery.state(message.getOrderId()))){return;}
        try{
            long canonical=writer.persist(new VoucherOrder().setId(message.getOrderId()).setUserId(message.getUserId()).setVoucherId(message.getVoucherId()));
            // persist 返回时已经提交；Redis 更新失败仍抛出异常，MQ 重试时数据库幂等。
            delivery.success(message,canonical);
        }catch(OrderWriter.RejectedOrderException rejected){
            delivery.rejected(message);
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();throw new IllegalStateException("订单处理被中断，请重试",interrupted);
        }
        // 不吞掉数据库/Redis 暂时不可用等运行时异常，由 RocketMQ 重试。
    }
}
