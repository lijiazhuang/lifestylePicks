package com.lifestylepicks.trade.mq;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeckillOrderDeadLetterListenerTest {

    @Test
    void malformedBusinessPayloadStillProducesAnErrorWithReplayCoordinates() {
        Logger logger = (Logger) LoggerFactory.getLogger(SeckillOrderDeadLetterListener.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            MessageExt message = new MessageExt();
            message.setTopic("%DLQ%seckill_order_consumer_group");
            message.setMsgId("dead-message-id");
            message.setKeys("100");
            message.setReconsumeTimes(16);
            message.setQueueId(0);
            message.setQueueOffset(42);
            MessageAccessor.putProperty(message, MessageConst.PROPERTY_RETRY_TOPIC, "seckill_order_topic");
            message.setBody("invalid JSON\n人工核对".getBytes(StandardCharsets.UTF_8));

            assertDoesNotThrow(() -> new SeckillOrderDeadLetterListener().onMessage(message));

            assertEquals(1, events.list.size());
            ILoggingEvent event = events.list.get(0);
            assertEquals(Level.ERROR, event.getLevel());
            String text = event.getFormattedMessage();
            assertTrue(text.contains("originalTopic=seckill_order_topic"));
            assertTrue(text.contains("msgId=dead-message-id"));
            assertTrue(text.contains("keys=100"));
            assertTrue(text.contains("reconsumeTimes=16"));
            assertTrue(text.contains("queueOffset=42"));
            assertTrue(text.contains("invalid JSON\\n人工核对"));
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void firstStartDoesNotSkipAlreadyAccumulatedDeadLetters() {
        DefaultMQPushConsumer consumer = mock(DefaultMQPushConsumer.class);
        new SeckillOrderDeadLetterListener().prepareStart(consumer);
        verify(consumer).setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }
}
