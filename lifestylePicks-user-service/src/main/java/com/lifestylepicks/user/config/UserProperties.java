package com.lifestylepicks.user.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import java.time.Duration;
import java.time.ZoneId;

@Component
@ConfigurationProperties(prefix = "lifestylepicks.user")
public class UserProperties implements InitializingBean {
    private Duration tokenTtl = Duration.ofMinutes(36000);
    private Duration codeTtl = Duration.ofMinutes(2);
    private String signZone = "Asia/Shanghai";
    private boolean logCode;

    @Override
    public void afterPropertiesSet() {
        Assert.isTrue(tokenTtl != null && tokenTtl.getSeconds() > 0, "Token TTL 至少为 1 秒");
        Assert.isTrue(codeTtl != null && codeTtl.getSeconds() > 0, "验证码 TTL 至少为 1 秒");
        ZoneId.of(signZone);
    }
    public Duration getTokenTtl() { return tokenTtl; }
    public void setTokenTtl(Duration value) { tokenTtl = value; }
    public Duration getCodeTtl() { return codeTtl; }
    public void setCodeTtl(Duration value) { codeTtl = value; }
    public String getSignZone() { return signZone; }
    public void setSignZone(String value) { signZone = value; }
    public boolean isLogCode() { return logCode; }
    public void setLogCode(boolean value) { logCode = value; }
}
