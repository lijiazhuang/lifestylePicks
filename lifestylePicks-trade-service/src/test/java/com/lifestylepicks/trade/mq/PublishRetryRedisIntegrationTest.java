package com.lifestylepicks.trade.mq;

import com.lifestylepicks.trade.config.TradeProperties;
import com.lifestylepicks.trade.service.OrderDeliveryStore;
import com.lifestylepicks.trade.service.PublishRetryResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 仅由 verify_publish_retry.py 启动的独立 Redis 执行，不连接 MySQL 或 Broker。 */
@EnabledIfEnvironmentVariable(named="TRADE_RETRY_TEST_REDIS_PORT",matches="[0-9]+")
class PublishRetryRedisIntegrationTest {
    private static final AtomicLong IDS=new AtomicLong(8_000_000_000_000_000_000L);
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private TradeProperties properties;
    private OrderDeliveryStore delivery;
    private final List<String> keys=new ArrayList<>();

    @BeforeAll static void connect(){
        assertEquals("true",System.getenv("TRADE_RETRY_TEST_REDIS_ISOLATED"),"Use the isolated Redis runner");
        int port=Integer.parseInt(System.getenv("TRADE_RETRY_TEST_REDIS_PORT"));
        assertNotEquals(6379,port,"Never connect the default Redis port");
        connection=new LettuceConnectionFactory("127.0.0.1",port);connection.afterPropertiesSet();
        redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();
    }
    @AfterAll static void disconnect(){if(connection!=null){connection.destroy();}}
    @BeforeEach void setup(){
        properties=new TradeProperties();properties.setOutboxKey("test:publish-retry:"+UUID.randomUUID());
        track(properties);delivery=new OrderDeliveryStore(redis,properties);
    }
    @AfterEach void cleanup(){redis.delete(keys);}
    private void track(TradeProperties value){
        keys.add(value.getOutboxKey());keys.add(events(value));keys.add(cooldown(value));
    }
    private String events(TradeProperties value){return value.getOutboxKey()+":publish-retry-failures";}
    private String cooldown(TradeProperties value){return value.getOutboxKey()+":publish-retry-alert";}
    private String status(long id){return "seckill:status:"+id;}
    private long pending(){return pending(properties);}
    private long pending(TradeProperties value){
        long id=IDS.incrementAndGet();keys.add(status(id));
        redis.opsForHash().put(status(id),"state","PENDING");
        redis.opsForZSet().add(value.getOutboxKey(),Long.toString(id),0);return id;
    }

    @Test void legacyPendingOrdersUseDoublingJitterAndCapWithoutTouchingReservation(){
        long id=pending();String stock="test:retry-stock:"+id, buyers="test:retry-buyers:"+id;
        keys.add(stock);keys.add(buyers);redis.opsForValue().set(stock,"9");redis.opsForSet().add(buyers,"1");
        long base=5000;
        for(int attempt=1;attempt<=12;attempt++){
            long before=System.currentTimeMillis();PublishRetryResult result=delivery.defer(id);long after=System.currentTimeMillis();
            assertEquals(attempt,result.getFailureCount());assertTrue(result.isScheduled());
            assertTrue(result.getNextRetryAtMillis()>=before+(long)(base*.8));
            assertTrue(result.getNextRetryAtMillis()<=after+base);
            assertEquals(result.getNextRetryAtMillis(),redis.opsForZSet().score(properties.getOutboxKey(),Long.toString(id)).longValue());
            assertEquals(attempt-1,result.getWindowFailureCount());
            base=Math.min(base*2,300000);
        }
        assertEquals("12",redis.opsForHash().get(status(id),"publishFailCount"));
        assertEquals("PENDING",redis.opsForHash().get(status(id),"state"));
        assertEquals("9",redis.opsForValue().get(stock));assertTrue(redis.opsForSet().isMember(buyers,"1"));
    }

    @Test void firstFailuresDoNotCountButRetriesOfDifferentOrdersShareOneWindow(){
        properties.setPublishRetryAlertThreshold(2);
        long first=pending(),second=pending();
        assertEquals(0,delivery.defer(first).getWindowFailureCount());
        assertEquals(0,delivery.defer(second).getWindowFailureCount());
        PublishRetryResult retry=delivery.defer(first);assertEquals(1,retry.getWindowFailureCount());assertFalse(retry.isAlert());
        retry=delivery.defer(second);assertEquals(2,retry.getWindowFailureCount());assertTrue(retry.isAlert());
        retry=delivery.defer(first);assertEquals(3,retry.getWindowFailureCount());assertFalse(retry.isAlert());assertTrue(retry.isScheduled());
        assertEquals(3,redis.opsForZSet().zCard(events(properties)).longValue());
        assertEquals("PENDING",redis.opsForHash().get(status(first),"state"));
    }

    @Test void multiplePublishersAtomicallyCountFailuresAndOnlyOneClaimsAlarm() throws Exception {
        properties.setPublishRetryAlertThreshold(8);
        long id=pending();delivery.defer(id);
        OrderDeliveryStore secondPublisher=new OrderDeliveryStore(redis,properties);
        ExecutorService executor=Executors.newFixedThreadPool(8);
        try{
            List<Callable<PublishRetryResult>> calls=new ArrayList<>();
            for(int index=0;index<32;index++){
                final OrderDeliveryStore store=index%2==0?delivery:secondPublisher;calls.add(()->store.defer(id));
            }
            int alerts=0;Set<Long> counts=new HashSet<>();
            for(Future<PublishRetryResult> future:executor.invokeAll(calls,10,TimeUnit.SECONDS)){
                PublishRetryResult result=future.get();if(result.isAlert()){alerts++;}counts.add(result.getFailureCount());
            }
            assertEquals(1,alerts);assertEquals(32,counts.size());
            assertEquals("33",redis.opsForHash().get(status(id),"publishFailCount"));
            assertEquals(32,redis.opsForZSet().zCard(events(properties)).longValue());
            assertNotNull(redis.opsForZSet().score(properties.getOutboxKey(),Long.toString(id)));
        }finally{executor.shutdownNow();}
    }

    @Test void cooldownAllowsAnotherAlarmWhileMessagesKeepRetrying(){
        properties.setPublishRetryAlertThreshold(1);properties.setPublishRetryAlertCooldown(Duration.ofMillis(300));
        long id=pending();delivery.defer(id);assertTrue(delivery.defer(id).isAlert());
        assertFalse(delivery.defer(id).isAlert());
        await().atMost(Duration.ofSeconds(5)).until(()->!redis.hasKey(cooldown(properties)));
        PublishRetryResult result=delivery.defer(id);assertTrue(result.isAlert());assertTrue(result.isScheduled());
        assertEquals(3,result.getWindowFailureCount());
    }

    @Test void expiredEventsArePrunedAndInactiveWindowKeyExpires(){
        properties.setPublishRetryAlertWindow(Duration.ofSeconds(1));
        long id=pending();redis.opsForZSet().add(events(properties),"old",System.currentTimeMillis()-5000);
        assertEquals(0,delivery.defer(id).getWindowFailureCount());
        assertEquals(1,delivery.defer(id).getWindowFailureCount());
        assertTrue(redis.getExpire(events(properties),TimeUnit.MILLISECONDS)>0);
        await().atMost(Duration.ofSeconds(5)).until(()->!redis.hasKey(events(properties)));
        assertEquals(1,delivery.defer(id).getWindowFailureCount());
    }

    @Test void completedOrdersAreNotRescheduledOrCounted(){
        for(String terminal:Arrays.asList("SUCCESS","FAILED")){
            long id=pending();delivery.defer(id);delivery.defer(id);
            long eventsBefore=redis.opsForZSet().zCard(events(properties));
            redis.opsForHash().put(status(id),"state",terminal);
            PublishRetryResult result=delivery.defer(id);assertFalse(result.isScheduled());assertFalse(result.isAlert());
            assertNull(redis.opsForZSet().score(properties.getOutboxKey(),Long.toString(id)));
            assertEquals("2",redis.opsForHash().get(status(id),"publishFailCount"));
            assertEquals(eventsBefore,redis.opsForZSet().zCard(events(properties)).longValue());
        }
    }

    @Test void missingMetadataAndCorruptedStatisticsDoNotDiscardOrPartiallyUpdateOrder(){
        long missing=IDS.incrementAndGet();redis.opsForZSet().add(properties.getOutboxKey(),Long.toString(missing),0);
        assertThrows(RuntimeException.class,()->delivery.defer(missing));
        assertNotNull(redis.opsForZSet().score(properties.getOutboxKey(),Long.toString(missing)));
        long id=pending();redis.opsForValue().set(events(properties),"wrong-type");
        assertThrows(RuntimeException.class,()->delivery.defer(id));
        assertNull(redis.opsForHash().get(status(id),"publishFailCount"));
        assertEquals(0,redis.opsForZSet().score(properties.getOutboxKey(),Long.toString(id)).longValue());
    }

    @Test void independentOutboxesDoNotShareCountersOrAlarmCooldown(){
        properties.setPublishRetryAlertThreshold(1);long first=pending();delivery.defer(first);assertTrue(delivery.defer(first).isAlert());
        TradeProperties isolated=new TradeProperties();isolated.setOutboxKey("test:publish-retry:"+UUID.randomUUID());
        isolated.setPublishRetryAlertThreshold(1);track(isolated);
        OrderDeliveryStore another=new OrderDeliveryStore(redis,isolated);long second=pending(isolated);
        assertEquals(0,another.defer(second).getWindowFailureCount());assertTrue(another.defer(second).isAlert());
        assertEquals(1,redis.opsForZSet().zCard(events(properties)).longValue());
        assertEquals(1,redis.opsForZSet().zCard(events(isolated)).longValue());
    }
}
