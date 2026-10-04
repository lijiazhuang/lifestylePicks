package com.lifestylepicks.gateway.filter;

import com.lifestylepicks.gateway.auth.AuthConstants;
import com.lifestylepicks.gateway.auth.AuthProperties;
import com.lifestylepicks.gateway.auth.LoginState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Collections;

/** 第一层：清除伪造身份、恢复登录态、续期；不因未登录拒绝请求。 */
@Component
public class RefreshTokenFilter implements GlobalFilter, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenFilter.class);
    private static final DefaultRedisScript<String> REFRESH_SCRIPT;

    static {
        REFRESH_SCRIPT = new DefaultRedisScript<>();
        REFRESH_SCRIPT.setLocation(new ClassPathResource("lua/refresh-token.lua"));
        REFRESH_SCRIPT.setResultType(String.class);
    }

    private final ReactiveStringRedisTemplate redis;
    private final AuthProperties properties;

    public RefreshTokenFilter(ReactiveStringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 所有业务请求都清除客户端身份头，公开接口也不能携带伪造 userId。
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.remove(AuthConstants.USER_ID_HEADER))
                .build();
        ServerWebExchange cleanExchange = exchange.mutate().request(request).build();
        String token = request.getHeaders().getFirst(AuthConstants.TOKEN_HEADER);

        Mono<LoginState> state;
        if (!StringUtils.hasText(token)) {
            state = Mono.just(LoginState.anonymous());
        } else {
            state = redis.execute(REFRESH_SCRIPT,
                            Collections.singletonList(properties.getTokenKeyPrefix() + token),
                            Collections.singletonList(Long.toString(properties.getTokenTtl().getSeconds())))
                    .next()
                    .map(LoginState::fromRedis)
                    .defaultIfEmpty(LoginState.anonymous())
                    .timeout(properties.getRedisTimeout())
                    .onErrorResume(exception -> {
                        // 不记录 Token；由第二层决定 Redis 故障时是否能放行。
                        LOG.warn("Redis 登录态校验不可用，异常类型：{}", exception.getClass().getSimpleName());
                        return Mono.just(LoginState.unavailable());
                    });
        }

        // 异常处理仅包裹 Redis 查询，不能吞掉下游业务或转发异常。
        return state.flatMap(loginState -> {
            cleanExchange.getAttributes().put(AuthConstants.LOGIN_STATE_ATTRIBUTE, loginState);
            return chain.filter(cleanExchange);
        });
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
