package com.lifestylepicks.trade.config;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import java.time.Duration;
import java.time.ZoneId;

@Component
@ConfigurationProperties(prefix="lifestylepicks.trade")
public class TradeProperties implements InitializingBean {
    private String orderTopic="seckill_order_topic";
    private String outboxKey="seckill:outbox";
    private Duration sendTimeout=Duration.ofSeconds(3);
    private Duration dispatchRetryDelay=Duration.ofSeconds(5);
    private String zone="Asia/Shanghai";
    public String getOrderTopic(){
        return orderTopic;}
    public void setOrderTopic(String value){orderTopic=value;}
    public String getOutboxKey(){return outboxKey;}
    public void setOutboxKey(String value){outboxKey=value;}
    public Duration getSendTimeout(){return sendTimeout;}
    public void setSendTimeout(Duration value){sendTimeout=value;}
    public Duration getDispatchRetryDelay(){return dispatchRetryDelay;}
    public void setDispatchRetryDelay(Duration value){dispatchRetryDelay=value;}
    public String getZone(){return zone;}
    public void setZone(String value){zone=value;}
    @Override
    public void afterPropertiesSet(){
        Assert.hasText(orderTopic,"RocketMQ Topic 不能为空"); Assert.hasText(outboxKey,"待投递 key 不能为空");
        Assert.isTrue(sendTimeout!=null && sendTimeout.toMillis()>0,"发送超时必须大于零");
        Assert.isTrue(dispatchRetryDelay!=null && dispatchRetryDelay.toMillis()>0,"重试间隔必须大于零"); ZoneId.of(zone);
    }
}
