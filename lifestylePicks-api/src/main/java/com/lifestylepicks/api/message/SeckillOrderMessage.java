package com.lifestylepicks.api.message;

/** RocketMQ 秒杀命令，异步消费显式携带身份。 */
public class SeckillOrderMessage {
    private Long orderId;
    private Long userId;
    private Long voucherId;
    public SeckillOrderMessage() { }
    public SeckillOrderMessage(Long orderId, Long userId, Long voucherId) {
        this.orderId=orderId; this.userId=userId; this.voucherId=voucherId;
    }
    public void validate() {
        if(orderId==null || orderId<=0 || userId==null || userId<=0 || voucherId==null || voucherId<=0) {
            throw new IllegalArgumentException("订单消息必须包含有效的订单、用户和优惠券 ID");
        }
    }
    public Long getOrderId(){return orderId;} public void setOrderId(Long value){orderId=value;}
    public Long getUserId(){return userId;} public void setUserId(Long value){userId=value;}
    public Long getVoucherId(){return voucherId;} public void setVoucherId(Long value){voucherId=value;}
}
