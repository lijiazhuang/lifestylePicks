package com.lifestylepicks.api.contract;

/** 用户服务端和调用端共同使用的内部接口路径。 */
public final class UserApiPaths {
    public static final String BATCH_USERS = "/internal/users/batch";
    public static final int MAX_BATCH_SIZE = 100;
    private UserApiPaths() { }
}
