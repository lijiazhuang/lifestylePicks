package com.lifestylepicks.common.context;

/**
 * Spring MVC 当前请求线程的用户上下文。
 * 不跨进程或异步线程自动传播；请求结束后由拦截器清理。
 */
public final class UserContext {

    private static final ThreadLocal<UserInfo> CURRENT_USER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUser(UserInfo user) {
        if (user == null) {
            clear();
        } else {
            CURRENT_USER.set(user);
        }
    }

    public static UserInfo getUser() {
        return CURRENT_USER.get();
    }

    /** 匿名请求返回 null，由调用方决定业务权限。 */
    public static Long getUserId() {
        UserInfo user = getUser();
        return user == null ? null : user.getUserId();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
