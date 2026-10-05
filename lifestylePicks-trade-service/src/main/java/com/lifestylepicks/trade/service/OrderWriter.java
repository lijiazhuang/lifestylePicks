package com.lifestylepicks.trade.service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.lifestylepicks.trade.entity.VoucherOrder;
import com.lifestylepicks.trade.entity.SeckillVoucher;
import com.lifestylepicks.trade.mapper.VoucherOrderMapper;
import com.lifestylepicks.trade.mapper.SeckillVoucherMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.concurrent.TimeUnit;

@Service
public class OrderWriter {
    private final VoucherOrderMapper orders;
    private final SeckillVoucherMapper stock;
    private final RedissonClient redisson;
    private final TransactionTemplate transaction;
    public OrderWriter(VoucherOrderMapper orders,SeckillVoucherMapper stock,RedissonClient redisson,PlatformTransactionManager manager){
        this.orders=orders;this.stock=stock;this.redisson=redisson;transaction=new TransactionTemplate(manager);
    }
    public long persist(VoucherOrder order) throws InterruptedException {
        RLock lock=redisson.getLock("lock:order:"+order.getUserId()+":"+order.getVoucherId());
        if(!lock.tryLock(2,TimeUnit.SECONDS)){throw new IllegalStateException("订单锁暂不可用");}
        try{
            return transaction.execute(status -> {
                VoucherOrder existing=orders.selectOne(new QueryWrapper<VoucherOrder>().eq("user_id",order.getUserId())
                        .eq("voucher_id",order.getVoucherId()).last("LIMIT 1"));
                if(existing!=null){
                    return existing.getId();
                }
                int updated=stock.update(null,new UpdateWrapper<SeckillVoucher>().setSql("stock=stock-1")
                        .eq("voucher_id",order.getVoucherId()).gt("stock",0));
                if(updated!=1){throw new RejectedOrderException("数据库库存不足");
                }
                order.setPayType(1);order.setStatus(1);orders.insert(order);return order.getId();
            });
        }finally{lock.unlock();}
    }
    public static class RejectedOrderException extends RuntimeException {public RejectedOrderException(String message){super(message);}}
}
