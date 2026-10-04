package com.lifestylepicks.common.autoconfigure;

import com.lifestylepicks.common.interceptor.UserContextInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 引入 common 后自动生效，只在 Servlet/MVC 服务中注册，不影响 WebFlux 网关。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({DispatcherServlet.class, WebMvcConfigurer.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "lifestylepicks.common.user-context", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class UserContextAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public UserContextInterceptor userContextInterceptor() {
        return new UserContextInterceptor();
    }

    @Bean(name = "lifestylePicksUserContextWebMvcConfigurer")
    @ConditionalOnMissingBean(name = "lifestylePicksUserContextWebMvcConfigurer")
    public WebMvcConfigurer userContextWebMvcConfigurer(UserContextInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/**")
                        .order(Ordered.HIGHEST_PRECEDENCE);
            }
        };
    }
}
