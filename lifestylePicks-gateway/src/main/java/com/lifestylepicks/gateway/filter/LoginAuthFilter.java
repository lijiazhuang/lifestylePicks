package com.lifestylepicks.gateway.filter;

import com.lifestylepicks.gateway.auth.AuthConstants;
import com.lifestylepicks.gateway.auth.AuthProperties;
import com.lifestylepicks.gateway.auth.LoginState;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 第二层：按方法和路径校验登录要求，并向下游写入可信身份。 */
@Component
public class LoginAuthFilter implements GlobalFilter, Ordered {

    private final List<PublicRule> publicRules = new ArrayList<>();

    public LoginAuthFilter(AuthProperties properties) {
        PathPatternParser parser = new PathPatternParser();
        for (AuthProperties.PublicEndpoint endpoint : properties.getPublicEndpoints()) {
            publicRules.add(new PublicRule(parser.parse(endpoint.getPath()),
                    new HashSet<>(endpoint.getMethods())));
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        LoginState state = exchange.getAttributeOrDefault(AuthConstants.LOGIN_STATE_ATTRIBUTE,
                LoginState.anonymous());
        if (!isPublic(exchange) && !state.isAuthenticated()) {
            if (state.isRedisUnavailable()) {
                return error(exchange, HttpStatus.SERVICE_UNAVAILABLE, "登录服务暂不可用，请稍后重试");
            }
            // 当前前端收到 HTTP 401 后会跳转 /login.html。
            return error(exchange, HttpStatus.UNAUTHORIZED, "请先登录");
        }

        // 公开接口也传递有效身份，支持点赞状态等个性化展示。
        if (state.isAuthenticated()) {
            ServerHttpRequest request = exchange.getRequest().mutate()
                    .headers(headers -> headers.set(AuthConstants.USER_ID_HEADER,
                            state.getUserId().toString()))
                    .build();
            return chain.filter(exchange.mutate().request(request).build());
        }
        return chain.filter(exchange);
    }

    private boolean isPublic(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        // StripPrefix 在后面执行；这里仅统一鉴权路径，不修改转发地址。
        if (path.equals("/api")) {
            path = "/";
        } else if (path.startsWith("/api/")) {
            path = path.substring(4);
        }
        PathContainer container = PathContainer.parsePath(path);
        HttpMethod method = exchange.getRequest().getMethod();
        return publicRules.stream().anyMatch(rule -> rule.methods.contains(method)
                && rule.path.matches(container));
    }

    private Mono<Void> error(ServerWebExchange exchange, HttpStatus status, String message) {
        byte[] body = ("{\"success\":false,\"errorMsg\":\"" + message + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
                .bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private static class PublicRule {

        private final PathPattern path;
        private final Set<HttpMethod> methods;

        private PublicRule(PathPattern path, Set<HttpMethod> methods) {
            this.path = path;
            this.methods = methods;
        }
    }
}
