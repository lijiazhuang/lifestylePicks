package com.lifestylepicks.trade.mq;

import com.lifestylepicks.api.message.SeckillOrderMessage;
import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.context.UserInfo;
import com.lifestylepicks.common.dto.Result;
import com.lifestylepicks.trade.TradeApplication;
import com.lifestylepicks.trade.config.TradeProperties;
import com.lifestylepicks.trade.entity.SeckillVoucher;
import com.lifestylepicks.trade.service.*;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 MySQL/Redis，模拟 MQ 客户端；不启动或连接 RocketMQ。 */
@EnabledIfEnvironmentVariable(named="TRADE_TEST_DB_URL",matches=".+")
@SpringBootTest(classes=TradeApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
        "lifestylepicks.trade.mq-enabled=false",
        "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
        "spring.datasource.url=${TRADE_TEST_DB_URL}","spring.datasource.username=root","spring.datasource.password=",
        "spring.redis.host=127.0.0.1","spring.redis.port=${TRADE_TEST_REDIS_PORT}","spring.redis.password="})
class RocketMqFlowIntegrationTest {
    private static final AtomicLong IDS=new AtomicLong(5000);
    @Autowired OrderService orders;
    @Autowired OrderMessageHandler handler;
    @Autowired OrderDeliveryStore delivery;
    @Autowired SeckillCache cache;
    @Autowired TradeProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @MockBean RocketMQTemplate rocket;
    private long voucher;

    @BeforeEach void seed(){
        voucher=IDS.incrementAndGet();
        LocalDateTime begin=LocalDateTime.now().minusDays(1),end=LocalDateTime.now().plusDays(1);
        jdbc.update("INSERT INTO tb_voucher(id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status) VALUES(?,1,'test','test','test',100,200,1,1)",voucher);
        jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES(?,2,?,?)",voucher,begin,end);
        cache.initialize(new SeckillVoucher().setVoucherId(voucher).setStock(2).setBeginTime(begin).setEndTime(end));
        UserContext.setUser(new UserInfo(1L));reset(rocket);
    }
    @AfterEach void clear(){UserContext.clear();}
    private SeckillOrderMessage reserve(){
        Result accepted=orders.seckill(voucher);assertTrue(accepted.getSuccess());
        return delivery.pending(accepted.getData().toString());
    }
    private SendResult ok(){SendResult result=new SendResult();result.setSendStatus(SendStatus.SEND_OK);return result;}
    private OrderMessagePublisher publisher(){return new OrderMessagePublisher(rocket,delivery,properties);}
    private int stock(){return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?",Integer.class,voucher);}

    @Test void realReservationMqDeliveryAndDuplicateConsumption(){
        SeckillOrderMessage message=reserve();
        assertEquals(1,Integer.parseInt(redis.opsForValue().get("seckill:stock:"+voucher)));
        assertEquals(2,stock());assertEquals("PENDING",delivery.state(message.getOrderId()));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("stream.orders")));
        when(rocket.syncSend(anyString(),any(Message.class),anyLong())).thenReturn(ok());
        publisher().publish(message);assertNull(redis.opsForZSet().score(properties.getOutboxKey(),message.getOrderId().toString()));
        UserContext.setUser(new UserInfo(99L)); // 消费使用消息里的用户，不使用线程上下文。
        handler.handle(message);handler.handle(message);
        assertEquals(1,stock());assertEquals("SUCCESS",delivery.state(message.getOrderId()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE user_id=1 AND voucher_id=?",Integer.class,voucher));
    }
    @Test void outageKeepsReservationAndCanPublishAgain(){
        SeckillOrderMessage message=reserve();
        when(rocket.syncSend(anyString(),any(Message.class),anyLong())).thenThrow(new IllegalStateException("offline"));
        publisher().publish(message);
        assertNotNull(redis.opsForZSet().score(properties.getOutboxKey(),message.getOrderId().toString()));
        assertEquals("PENDING",delivery.state(message.getOrderId()));assertEquals(1,Integer.parseInt(redis.opsForValue().get("seckill:stock:"+voucher)));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember("seckill:order:"+voucher,"1")));
        when(rocket.syncSend(anyString(),any(Message.class),anyLong())).thenReturn(ok());
        publisher().publish(message);handler.handle(message);assertEquals(1,stock());
    }
    @Test void acknowledgementLossAfterConsumptionDoesNotReopenReservation(){
        SeckillOrderMessage message=reserve();
        when(rocket.syncSend(anyString(),any(Message.class),anyLong())).thenAnswer(invocation->{
            handler.handle(message);throw new IllegalStateException("ack lost");
        });
        publisher().publish(message);
        assertEquals("SUCCESS",delivery.state(message.getOrderId()));assertEquals(1,stock());
        assertNull(redis.opsForZSet().score(properties.getOutboxKey(),message.getOrderId().toString()));
    }
    @Test void databaseFailureRollsBackStockAndCanRetry(){
        SeckillOrderMessage message=reserve();
        jdbc.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id,pay_type,status) VALUES(?,99,?,1,1)",message.getOrderId(),voucher);
        assertThrows(RuntimeException.class,()->handler.handle(message));assertEquals(2,stock());
        assertEquals("PENDING",delivery.state(message.getOrderId()));
        jdbc.update("DELETE FROM tb_voucher_order WHERE id=?",message.getOrderId());
        handler.handle(message);assertEquals(1,stock());assertEquals("SUCCESS",delivery.state(message.getOrderId()));
    }
    @Test void permanentStockRejectionSettlesFailedAndStopsRepublishing(){
        SeckillOrderMessage message=reserve();jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=?",voucher);
        handler.handle(message);handler.handle(message);
        assertEquals("FAILED",delivery.state(message.getOrderId()));assertEquals("0",redis.opsForValue().get("seckill:stock:"+voucher));
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember("seckill:order:"+voucher,"1")));
        assertNull(redis.opsForZSet().score(properties.getOutboxKey(),message.getOrderId().toString()));
    }
    @Test void missingDeliveryMetadataIsNotSilentlyDropped(){
        String id="900000000000000000";
        redis.opsForZSet().add(properties.getOutboxKey(),id,0);
        assertThrows(IllegalStateException.class,()->delivery.pending(id));
        assertNotNull(redis.opsForZSet().score(properties.getOutboxKey(),id));
        redis.opsForZSet().remove(properties.getOutboxKey(),id);
    }
}
