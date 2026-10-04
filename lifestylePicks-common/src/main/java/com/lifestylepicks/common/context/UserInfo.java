package com.lifestylepicks.common.context;

/** 当前网关只传递用户 ID；昵称、头像等资料由业务服务按需查询。 */
public final class UserInfo {

    private final Long userId;

    public UserInfo(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户 ID 必须为正整数");
        }
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
