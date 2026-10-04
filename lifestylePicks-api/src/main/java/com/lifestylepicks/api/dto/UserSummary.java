package com.lifestylepicks.api.dto;

/** 可对外展示的用户资料，保持原前端 id/nickName/icon 协议，不包含手机号和密码。 */
public class UserSummary {
    private Long id;
    private String nickName;
    private String icon;

    public UserSummary() {
    }

    public UserSummary(Long id, String nickName, String icon) {
        this.id = id;
        this.nickName = nickName;
        this.icon = icon;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNickName() { return nickName; }
    public void setNickName(String nickName) { this.nickName = nickName; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
}
