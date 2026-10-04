package com.lifestylepicks.api.http;

import com.lifestylepicks.api.config.ApiClientProperties;
import com.lifestylepicks.common.constant.UserHeaders;
import com.lifestylepicks.common.context.UserContext;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

/** 所有阻塞 HTTP 客户端共用的超时和身份传递策略。 */
public class ApiRestTemplateFactory {
    private final RestTemplateBuilder builder;
    private final ApiClientProperties properties;

    public ApiRestTemplateFactory(RestTemplateBuilder builder, ApiClientProperties properties) {
        this.builder = builder;
        this.properties = properties;
    }

    public RestTemplate create() {
        return builder.setConnectTimeout(properties.getConnectTimeout())
                .setReadTimeout(properties.getReadTimeout())
                .additionalInterceptors((request, body, execution) -> {
                    // 只传递 common 中的当前身份，不携带上一次请求遗留的 Header。
                    request.getHeaders().remove(UserHeaders.USER_ID);
                    Long userId = UserContext.getUserId();
                    if (userId != null) { request.getHeaders().set(UserHeaders.USER_ID, userId.toString()); }
                    return execution.execute(request, body);
                }).build();
    }
}
