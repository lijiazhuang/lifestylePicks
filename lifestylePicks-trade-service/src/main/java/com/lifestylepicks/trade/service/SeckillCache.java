package com.lifestylepicks.trade.service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lifestylepicks.trade.config.TradeProperties;
import com.lifestylepicks.trade.entity.SeckillVoucher;
import com.lifestylepicks.trade.entity.VoucherOrder;
import com.lifestylepicks.trade.mapper.SeckillVoucherMapper;
import com.lifestylepicks.trade.mapper.VoucherOrderMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.ZoneId;
import java.util.Collections;

@Component
public class SeckillCache implements ApplicationRunner {
    private final StringRedisTemplate redis;
    private final TradeProperties properties;
    private final SeckillVoucherMapper vouchers;
    private final VoucherOrderMapper orders;
    private final DefaultRedisScript<Long> initialize=new DefaultRedisScript<>();
    public SeckillCache(StringRedisTemplate redis,TradeProperties properties,SeckillVoucherMapper vouchers,VoucherOrderMapper orders){
        this.redis=redis;this.properties=properties;this.vouchers=vouchers;this.orders=orders;
        initialize.setLocation(new ClassPathResource("lua/initialize-seckill.lua"));initialize.setResultType(Long.class);
    }
    public void initialize(SeckillVoucher voucher){
        String id=voucher.getVoucherId().toString();
        // 先恢复已有买家，避免启动时 Redis 买家集合缺失导致重复报名。
        orders.selectList(new QueryWrapper<VoucherOrder>().eq("voucher_id",voucher.getVoucherId())).forEach(order ->
                redis.opsForSet().add("seckill:order:"+id,order.getUserId().toString()));
        ZoneId zone=ZoneId.of(properties.getZone());
        redis.execute(initialize, java.util.Arrays.asList("seckill:stock:"+id,"seckill:meta:"+id),
                String.valueOf(voucher.getStock()),String.valueOf(voucher.getBeginTime().atZone(zone).toInstant().toEpochMilli()),
                String.valueOf(voucher.getEndTime().atZone(zone).toInstant().toEpochMilli()));
    }
    @Override public void run(ApplicationArguments arguments){vouchers.selectList(null).forEach(this::initialize);}
}
