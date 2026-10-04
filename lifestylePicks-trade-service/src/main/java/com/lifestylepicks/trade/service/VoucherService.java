package com.lifestylepicks.trade.service;
import com.lifestylepicks.common.dto.Result;
import com.lifestylepicks.trade.entity.Voucher;
import com.lifestylepicks.trade.entity.SeckillVoucher;
import com.lifestylepicks.trade.mapper.VoucherMapper;
import com.lifestylepicks.trade.mapper.SeckillVoucherMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class VoucherService {
    private static final Logger LOG=LoggerFactory.getLogger(VoucherService.class);
    private final VoucherMapper vouchers;
    private final SeckillVoucherMapper seckill;
    private final SeckillCache cache;
    public VoucherService(VoucherMapper vouchers,SeckillVoucherMapper seckill,SeckillCache cache){this.vouchers=vouchers;this.seckill=seckill;this.cache=cache;}
    public Result list(Long shopId){return Result.ok(vouchers.queryVoucherOfShop(shopId));}
    @Transactional public Result create(Voucher voucher,boolean flash){
        if(voucher.getShopId()==null || voucher.getTitle()==null || voucher.getTitle().trim().isEmpty()
                || voucher.getPayValue()==null || voucher.getActualValue()==null){return Result.fail("优惠券信息不完整");}
        if(flash && (voucher.getStock()==null || voucher.getStock()<=0 || voucher.getBeginTime()==null
                || voucher.getEndTime()==null || !voucher.getEndTime().isAfter(voucher.getBeginTime()))){return Result.fail("秒杀库存和时间范围无效");}
        voucher.setId(null);voucher.setType(flash?1:0);voucher.setStatus(1);vouchers.insert(voucher);
        if(flash){
            SeckillVoucher item=new SeckillVoucher().setVoucherId(voucher.getId()).setStock(voucher.getStock())
                    .setBeginTime(voucher.getBeginTime()).setEndTime(voucher.getEndTime());
            seckill.insert(item);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){
                    try{cache.initialize(item);}catch(RuntimeException unavailable){
                        LOG.error("秒杀券 {} 已提交，缓存未就绪，可在 Redis 恢复后重启交易服务初始化",item.getVoucherId(),unavailable);
                    }
                }
            });
        }
        return Result.ok(voucher.getId());
    }
}
