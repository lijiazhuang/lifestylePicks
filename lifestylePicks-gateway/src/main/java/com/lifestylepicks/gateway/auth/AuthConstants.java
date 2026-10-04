package com.lifestylepicks.gateway.auth;

import com.lifestylepicks.common.constant.UserHeaders;

public final class AuthConstants {

    public static final String TOKEN_HEADER = "authorization";
    public static final String USER_ID_HEADER = UserHeaders.USER_ID;
    public static final String LOGIN_STATE_ATTRIBUTE = "lifestylepicks.loginState";

    private AuthConstants() {
    }
}
