package com.lifestylepicks.api.feign;

import com.lifestylepicks.api.config.ApiClientProperties;
import feign.Feign;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import java.util.concurrent.TimeUnit;

/** 仅由 Feign 子容器加载，不作为全局组件扫描配置。后续客户端可共用此配置。 */
public class ApiFeignConfiguration {
    @Bean
    public RequestInterceptor userContextRequestInterceptor() {
        return new UserContextRequestInterceptor();
    }

    @Bean
    public Request.Options apiFeignOptions(ApiClientProperties properties) {
        return new Request.Options(properties.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS,
                properties.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS, false);
    }

    @Bean
    public Retryer apiFeignRetryer() {
        // 保持原调用语义，不隐式重试未来可能新增的写请求。
        return Retryer.NEVER_RETRY;
    }

    @Bean
    @Scope("prototype")
    public Feign.Builder apiFeignBuilder() {
        // 同步请求线程执行拦截器，避免线程隔离丢失 UserContext。
        return Feign.builder();
    }
}
