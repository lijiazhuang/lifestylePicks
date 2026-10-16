package com.lifestylepicks.trade.config;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class TradePropertiesTest {
    @Test void retryCapCannotBeBelowInitialDelay(){
        TradeProperties properties=new TradeProperties();
        properties.setDispatchRetryMaxDelay(Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class,properties::afterPropertiesSet);
    }

    @Test void windowThresholdAndCooldownMustBePositive(){
        TradeProperties properties=new TradeProperties();
        properties.setPublishRetryAlertWindow(Duration.ZERO);
        assertThrows(IllegalArgumentException.class,properties::afterPropertiesSet);
        properties.setPublishRetryAlertWindow(Duration.ofMinutes(5));
        properties.setPublishRetryAlertThreshold(0);
        assertThrows(IllegalArgumentException.class,properties::afterPropertiesSet);
        properties.setPublishRetryAlertThreshold(100);
        properties.setPublishRetryAlertCooldown(Duration.ZERO);
        assertThrows(IllegalArgumentException.class,properties::afterPropertiesSet);
    }
}
