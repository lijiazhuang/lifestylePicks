package com.lifestylepicks.trade.service;
import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.dto.Result;
import com.lifestylepicks.trade.config.TradeProperties;
import com.lifestylepicks.trade.entity.VoucherOrder;
import com.lifestylepicks.trade.mapper.VoucherOrderMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class OrderService {
    private final StringRedisTemplate redis;
    private final TradeProperties properties;
    private final VoucherOrderMapper orders;
    private final DefaultRedisScript<Long> admission=new DefaultRedisScript<>();
    public OrderService(StringRedisTemplate redis,TradeProperties properties,VoucherOrderMapper orders){
        this.redis=redis;this.properties=properties;this.orders=orders;
        admission.setLocation(new ClassPathResource("lua/seckill.lua"));admission.setResultType(Long.class);
    }
    public Result seckill(Long voucherId){
        Long uid=UserContext.getUserId();Instant now=Instant.now();
        String date=DateTimeFormatter.ofPattern("yyyy:MM:dd").withZone(ZoneOffset.UTC).format(now);
        Long sequence=redis.opsForValue().increment("icr:order:"+date);
        if(sequence==null || sequence>0xffffffffL){return Result.fail("订单序列暂不可用");}
        long id=((now.getEpochSecond()-1640995200L)<<32)|sequence;
        String vid=voucherId.toString();
        Long code=redis.execute(admission,Arrays.asList("seckill:stock:"+vid,"seckill:order:"+vid,
                properties.getOutboxKey(),"seckill:meta:"+vid,"seckill:status:"+id),
                uid.toString(),vid,Long.toString(id),Long.toString(now.toEpochMilli()));
        if(Long.valueOf(0).equals(code)){return Result.ok(Long.toString(id));}
        return Result.fail(Long.valueOf(1).equals(code)?"库存不足":Long.valueOf(2).equals(code)?"不能重复下单":
                Long.valueOf(3).equals(code)?"秒杀尚未开始":Long.valueOf(4).equals(code)?"秒杀已结束":"秒杀库存尚未就绪");
    }
    public Result status(Long id){
        VoucherOrder order=orders.selectById(id);
        Map<Object,Object> pending=redis.opsForHash().entries("seckill:status:"+id);
        if(order==null && "SUCCESS".equals(pending.get("state")) && pending.get("canonicalId")!=null){
            order=orders.selectById(Long.valueOf(pending.get("canonicalId").toString()));
        }
        Long uid=UserContext.getUserId();
        if(order!=null){if(!uid.equals(order.getUserId())){return Result.fail("订单不存在");}return Result.ok(order);}
        if(!uid.toString().equals(pending.get("userId"))){return Result.fail("订单不存在");}
        Map<String,Object> response=new LinkedHashMap<>();response.put("id",id.toString());response.put("state",pending.get("state"));
        if(pending.get("errorMsg")!=null){response.put("errorMsg",pending.get("errorMsg"));}
        return Result.ok(response);
    }
}
