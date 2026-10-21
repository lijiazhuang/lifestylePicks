package com.lifestylepicks.trade.mq;

import com.lifestylepicks.api.message.SeckillOrderMessage;
import com.lifestylepicks.trade.config.TradeProperties;
import com.lifestylepicks.trade.entity.VoucherOrder;
import com.lifestylepicks.trade.service.OrderDeliveryStore;
import com.lifestylepicks.trade.service.OrderMessageHandler;
import com.lifestylepicks.trade.service.OrderWriter;
import com.lifestylepicks.trade.service.PublishRetryResult;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RocketMqOrderLogicTest {
    private final SeckillOrderMessage command=new SeckillOrderMessage(100L,1L,2L);
    private final RocketMQTemplate rocket=mock(RocketMQTemplate.class);
    private final OrderDeliveryStore delivery=mock(OrderDeliveryStore.class);
    private final TradeProperties properties=new TradeProperties();
    private final OrderMessagePublisher publisher=new OrderMessagePublisher(rocket,delivery,properties);

    @BeforeEach void retrySchedule(){
        when(delivery.defer(anyLong())).thenReturn(new PublishRetryResult(1,5000,0,false,1));
    }

    private SendResult sent(SendStatus status){SendResult result=new SendResult();result.setSendStatus(status);return result;}

    @Test void acknowledgedSendRemovesOnlyDeliveryRecord(){
        when(rocket.syncSend(eq(properties.getOrderTopic()),any(Message.class),anyLong())).thenReturn(sent(SendStatus.SEND_OK));
        publisher.publish(command);
        verify(delivery).sent(100L);verify(delivery,never()).rejected(any());verify(delivery,never()).defer(anyLong());
    }
    @Test void sendTimeoutDefersWithoutReleasingStock(){
        when(rocket.syncSend(anyString(),any(Message.class),anyLong())).thenThrow(new IllegalStateException("timeout"));
        publisher.publish(command);
        verify(delivery).defer(100L);verify(delivery,never()).sent(anyLong());verify(delivery,never()).rejected(any());
    }
    @Test void uncertainBrokerStatusIsNotTreatedAsDelivered(){
        when(rocket.syncSend(anyString(),any(Message.class),anyLong())).thenReturn(sent(SendStatus.FLUSH_DISK_TIMEOUT));
        publisher.publish(command);verify(delivery).defer(100L);verify(delivery,never()).sent(anyLong());
    }
    @Test void dispatchReconstructsPendingCommandAndPublishes(){
        when(delivery.due()).thenReturn(Collections.singleton("100"));when(delivery.pending("100")).thenReturn(command);
        when(rocket.syncSend(anyString(),any(Message.class),anyLong())).thenReturn(sent(SendStatus.SEND_OK));
        publisher.dispatch();verify(delivery).sent(100L);
    }
    @Test void malformedPayloadDoesNotSend(){
        assertThrows(IllegalArgumentException.class,()->publisher.publish(new SeckillOrderMessage(null,1L,2L)));
        verifyNoInteractions(rocket);
    }
    @Test void listenerUsesMessageIdentityAndSettlesAfterWriterReturns() throws Exception {
        OrderWriter writer=mock(OrderWriter.class);when(writer.persist(any())).thenReturn(100L);
        new SeckillOrderListener(new OrderMessageHandler(writer,delivery)).onMessage(command);
        verify(writer).persist(argThat(order->order.getUserId().equals(1L)&&order.getVoucherId().equals(2L)&&order.getId().equals(100L)));
        org.mockito.InOrder order=inOrder(writer,delivery);order.verify(writer).persist(any());order.verify(delivery).success(command,100L);
    }
    @Test void temporaryDatabaseErrorPropagatesForMqRetry() throws Exception {
        OrderWriter writer=mock(OrderWriter.class);when(writer.persist(any())).thenThrow(new IllegalStateException("database offline"));
        assertThrows(IllegalStateException.class,()->new OrderMessageHandler(writer,delivery).handle(command));
        verify(delivery,never()).success(any(),anyLong());verify(delivery,never()).rejected(any());
    }
    @Test void postCommitRedisErrorAlsoPropagatesForIdempotentRetry() throws Exception {
        OrderWriter writer=mock(OrderWriter.class);when(writer.persist(any())).thenReturn(100L);
        doThrow(new IllegalStateException("redis offline")).when(delivery).success(command,100L);
        assertThrows(IllegalStateException.class,()->new OrderMessageHandler(writer,delivery).handle(command));
        verify(delivery,never()).rejected(any());
    }
    @Test void permanentInventoryRejectionMarksFailed() throws Exception {
        OrderWriter writer=mock(OrderWriter.class);when(writer.persist(any())).thenThrow(new OrderWriter.RejectedOrderException("stock"));
        new OrderMessageHandler(writer,delivery).handle(command);verify(delivery).rejected(command);
    }
    @Test void repeatedTerminalFailureDoesNotWriteAgain(){
        OrderWriter writer=mock(OrderWriter.class);when(delivery.state(100L)).thenReturn("FAILED");
        new OrderMessageHandler(writer,delivery).handle(command);verifyNoInteractions(writer);
    }

    @Test void confirmedSendWithRedisCleanupFailureDoesNotRegisterMqFailure(){
        when(rocket.syncSend(anyString(),any(Message.class),anyLong())).thenReturn(sent(SendStatus.SEND_OK));
        doThrow(new IllegalStateException("Redis cleanup unavailable")).when(delivery).sent(100L);
        assertDoesNotThrow(()->publisher.publish(command));
        verify(delivery,never()).defer(anyLong());verify(delivery,never()).rejected(any());
    }

    @Test void failedRetryRegistrationKeepsDeliveryForLaterInspection(){
        when(rocket.syncSend(anyString(),any(Message.class),anyLong())).thenThrow(new IllegalStateException("MQ offline"));
        when(delivery.defer(100L)).thenThrow(new IllegalStateException("Redis unavailable"));
        assertDoesNotThrow(()->publisher.publish(command));
        verify(delivery,never()).sent(anyLong());verify(delivery,never()).rejected(any());
    }

    @Test void globalAlarmLogsSnapshotWithoutStoppingOrReleasingTheOrder(){
        Logger logger=(Logger)LoggerFactory.getLogger(OrderMessagePublisher.class);
        ListAppender<ILoggingEvent> events=new ListAppender<>();events.start();logger.addAppender(events);
        try{
            when(rocket.syncSend(anyString(),any(Message.class),anyLong())).thenThrow(new IllegalStateException("MQ offline"));
            when(delivery.defer(100L)).thenReturn(new PublishRetryResult(8,123456789,100,true,42));
            publisher.publish(command);
            ILoggingEvent alert=events.list.stream().filter(event->event.getLevel()==Level.ERROR
                    && event.getFormattedMessage().contains("SECKILL_ORDER_PUBLISH_RETRY_ALERT")).findFirst().orElseThrow(AssertionError::new);
            String text=alert.getFormattedMessage();
            assertTrue(text.contains("windowFailures=100"));assertTrue(text.contains("threshold=100"));
            assertTrue(text.contains("pendingCount=42"));assertTrue(text.contains("orderId=100"));
            assertTrue(text.contains("publishFailCount=8"));assertTrue(text.contains("nextRetryAtMillis=123456789"));
            verify(delivery,never()).sent(anyLong());verify(delivery,never()).rejected(any());
        }finally{logger.detachAppender(events);events.stop();}
    }
}
