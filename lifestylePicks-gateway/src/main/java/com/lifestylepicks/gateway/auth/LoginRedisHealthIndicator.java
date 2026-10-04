package com.lifestylepicks.gateway.auth;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 用 PING 检查登录 Redis，避免 Windows Redis 的 INFO 路径转义兼容问题。 */
@Component
public class LoginRedisHealthIndicator implements ReactiveHealthIndicator {

    private final ReactiveStringRedisTemplate redis;
    private final AuthProperties properties;

    public LoginRedisHealthIndicator(ReactiveStringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Mono<Health> health() {
        return redis.execute(connection -> connection.ping())
                .next()
                .map(reply -> "PONG".equals(reply) ? Health.up().build() : Health.down().build())
                .defaultIfEmpty(Health.down().build())
                .timeout(properties.getRedisTimeout())
                .onErrorResume(exception -> Mono.just(Health.down()
                        .withDetail("error", exception.getClass().getSimpleName()).build()));
    }
}
