package com.lifestylepicks.api.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "lifestylepicks.api")
public class ApiClientProperties implements InitializingBean, EnvironmentAware {
    private String userServiceUri = "http://127.0.0.1:8083";
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration readTimeout = Duration.ofSeconds(1);
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) { this.environment = environment; }

    @Override
    public void afterPropertiesSet() {
        Assert.isTrue(connectTimeout != null && connectTimeout.toMillis() > 0, "连接超时必须至少为 1ms");
        Assert.isTrue(readTimeout != null && readTimeout.toMillis() > 0, "读取超时必须至少为 1ms");
        Assert.isTrue(connectTimeout.toMillis() <= Integer.MAX_VALUE
                && readTimeout.toMillis() <= Integer.MAX_VALUE, "超时超过 Feign 支持的范围");
        if (environment != null && !environment.containsProperty("lifestylepicks.api.user-service-uri")) {
            userServiceUri = environment.getProperty("lifestylepicks.user-service-uri",
                    environment.getProperty("USER_SERVICE_URI", userServiceUri));
        }
        Assert.hasText(userServiceUri, "用户服务地址不能为空");
        URI uri = URI.create(userServiceUri);
        Assert.isTrue(("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null && uri.getRawQuery() == null
                && uri.getFragment() == null, "用户服务地址必须是无凭据和查询参数的 HTTP/HTTPS 基础地址");
    }
    public String getUserServiceUri() { return userServiceUri; }
    public void setUserServiceUri(String value) { userServiceUri = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = value; }
}
