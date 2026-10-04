package com.lifestylepicks.trade.service;

import com.lifestylepicks.api.message.SeckillOrderMessage;
import com.lifestylepicks.trade.config.TradeProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.*;

/** Redis 保存预留状态及 MQ 补发记录，订单落库只在 MQ 消费者中执行。 */
@Service
public class OrderDeliveryStore {
    private final StringRedisTemplate redis;
    private final TradeProperties properties;
    private final DefaultRedisScript<Long> settle=script("settle-order.lua");
    private final DefaultRedisScript<Long> fail=script("fail-order.lua");
    private final DefaultRedisScript<Long> defer=script("defer-publish.lua");
    public OrderDeliveryStore(StringRedisTemplate redis,TradeProperties properties){this.redis=redis;this.properties=properties;}
    private static DefaultRedisScript<Long> script(String file){
        DefaultRedisScript<Long> value=new DefaultRedisScript<>();value.setLocation(new ClassPathResource("lua/"+file));value.setResultType(Long.class);return value;
    }
    public Set<String> due(){
        Set<String> ids=redis.opsForZSet().rangeByScore(properties.getOutboxKey(),0,System.currentTimeMillis(),0,100);
        return ids==null?Collections.emptySet():ids;
    }
    public String state(Long id){
        Object value=redis.opsForHash().get("seckill:status:"+id,"state");return value==null?null:value.toString();
    }
    public SeckillOrderMessage pending(String id){
        Map<Object,Object> values=redis.opsForHash().entries("seckill:status:"+id);
        Object state=values.get("state");
        if("SUCCESS".equals(state) || "FAILED".equals(state)){sent(Long.valueOf(id));return null;}
        if(!"PENDING".equals(state)){throw new IllegalStateException("待投递订单状态缺失，保留记录待检查");}
        SeckillOrderMessage message=new SeckillOrderMessage(Long.valueOf(id),Long.valueOf(values.get("userId").toString()),Long.valueOf(values.get("voucherId").toString()));
        message.validate();return message;
    }
    public void sent(Long id){redis.opsForZSet().remove(properties.getOutboxKey(),id.toString());}
    public void defer(Long id){
        redis.execute(defer,Arrays.asList("seckill:status:"+id,properties.getOutboxKey()),id.toString(),
                Long.toString(System.currentTimeMillis()+properties.getDispatchRetryDelay().toMillis()));
    }
    public void success(SeckillOrderMessage message,long canonicalId){
        redis.execute(settle,Arrays.asList("seckill:status:"+message.getOrderId(),properties.getOutboxKey()),
                message.getOrderId().toString(),message.getUserId().toString(),message.getVoucherId().toString(),Long.toString(canonicalId));
    }
    public void rejected(SeckillOrderMessage message){
        redis.execute(fail,Arrays.asList("seckill:stock:"+message.getVoucherId(),"seckill:order:"+message.getVoucherId(),
                        "seckill:status:"+message.getOrderId(),properties.getOutboxKey()),message.getOrderId().toString(),
                message.getUserId().toString(),message.getVoucherId().toString(),"订单创建失败，请稍后重试");
    }
}
