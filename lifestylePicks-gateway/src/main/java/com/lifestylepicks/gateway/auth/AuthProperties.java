package com.lifestylepicks.gateway.auth;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "lifestylepicks.auth")
public class AuthProperties implements InitializingBean {

    private String tokenKeyPrefix = "login:token:";
    private Duration tokenTtl = Duration.ofMinutes(36000);
    private Duration redisTimeout = Duration.ofSeconds(1);
    private List<PublicEndpoint> publicEndpoints = new ArrayList<>();

    @Override
    public void afterPropertiesSet() {
        Assert.hasText(tokenKeyPrefix, "Token key 前缀不能为空");
        Assert.isTrue(tokenTtl != null && tokenTtl.getSeconds() > 0, "Token TTL 至少为 1 秒");
        Assert.isTrue(redisTimeout != null && !redisTimeout.isNegative() && !redisTimeout.isZero(),
                "Redis 超时必须大于 0");
        Assert.notNull(publicEndpoints, "公开接口列表不能为空引用");
        PathPatternParser parser = new PathPatternParser();
        for (PublicEndpoint endpoint : publicEndpoints) {
            Assert.notNull(endpoint, "公开接口规则不能为空引用");
            Assert.hasText(endpoint.getPath(), "公开接口路径不能为空");
            Assert.isTrue(endpoint.getPath().startsWith("/"), "公开接口路径必须以 / 开头");
            parser.parse(endpoint.getPath());
            Assert.notEmpty(endpoint.getMethods(), "公开接口必须指定 HTTP 方法");
            Assert.noNullElements(endpoint.getMethods().toArray(), "HTTP 方法不能为空引用");
        }
    }

    public String getTokenKeyPrefix() {
        return tokenKeyPrefix;
    }

    public void setTokenKeyPrefix(String tokenKeyPrefix) {
        this.tokenKeyPrefix = tokenKeyPrefix;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public Duration getRedisTimeout() {
        return redisTimeout;
    }

    public void setRedisTimeout(Duration redisTimeout) {
        this.redisTimeout = redisTimeout;
    }

    public List<PublicEndpoint> getPublicEndpoints() {
        return publicEndpoints;
    }

    public void setPublicEndpoints(List<PublicEndpoint> publicEndpoints) {
        this.publicEndpoints = publicEndpoints;
    }

    public static class PublicEndpoint {

        private String path;
        private List<HttpMethod> methods = new ArrayList<>();

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public List<HttpMethod> getMethods() {
            return methods;
        }

        public void setMethods(List<HttpMethod> methods) {
            this.methods = methods;
        }
    }
}
