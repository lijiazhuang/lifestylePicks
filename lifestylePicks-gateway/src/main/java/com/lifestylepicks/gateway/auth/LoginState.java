package com.lifestylepicks.gateway.auth;

/** 单次请求的登录状态，保存在 exchange 中，不使用 ThreadLocal。 */
public final class LoginState {

    private final Long userId;
    private final boolean redisUnavailable;

    private LoginState(Long userId, boolean redisUnavailable) {
        this.userId = userId;
        this.redisUnavailable = redisUnavailable;
    }

    public static LoginState anonymous() {
        return new LoginState(null, false);
    }

    public static LoginState unavailable() {
        return new LoginState(null, true);
    }

    public static LoginState fromRedis(String id) {
        try {
            long userId = Long.parseLong(id);
            return userId > 0 ? new LoginState(userId, false) : anonymous();
        } catch (NumberFormatException exception) {
            return anonymous();
        }
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isAuthenticated() {
        return userId != null;
    }

    public boolean isRedisUnavailable() {
        return redisUnavailable;
    }
}
