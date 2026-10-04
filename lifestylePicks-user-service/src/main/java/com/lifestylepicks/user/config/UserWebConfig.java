package com.lifestylepicks.user.config;

import com.lifestylepicks.common.context.UserContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class UserWebConfig implements WebMvcConfigurer {
    @Bean
    public Clock userClock(UserProperties properties) {
        return Clock.system(ZoneId.of(properties.getSignZone()));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // common 已先恢复用户身份。直接调用服务也不能匿名访问 /user 私有接口。
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                if (UserContext.getUserId() != null) { return true; }
                response.setStatus(401);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"success\":false,\"errorMsg\":\"请先登录\"}");
                return false;
            }
        }).addPathPatterns("/user/**").excludePathPatterns("/user/code", "/user/login").order(0);
    }
}
