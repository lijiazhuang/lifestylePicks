package com.lifestylepicks.api.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;
import java.time.Duration;

@ConfigurationProperties(prefix = "lifestylepicks.api")
public class ApiClientProperties implements InitializingBean {
    private String userServiceUri = "http://127.0.0.1:8083";
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration readTimeout = Duration.ofSeconds(1);

    @Override
    public void afterPropertiesSet() {
        Assert.isTrue(connectTimeout != null && connectTimeout.toMillis() > 0, "连接超时必须至少为 1ms");
        Assert.isTrue(readTimeout != null && readTimeout.toMillis() > 0, "读取超时必须至少为 1ms");
    }
    public String getUserServiceUri() { return userServiceUri; }
    public void setUserServiceUri(String value) { userServiceUri = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = value; }
}
