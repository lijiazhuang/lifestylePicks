package com.lifestylepicks.content.config;
import com.lifestylepicks.common.context.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.file.Paths;

@Configuration
public class ContentWebConfig implements WebMvcConfigurer {
    @Value("${lifestylepicks.content.upload-dir:./uploads}")
    private String uploadDir;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
                if (UserContext.getUserId() != null) { return true; }
                res.setStatus(401); res.setContentType("application/json"); res.setCharacterEncoding("UTF-8");
                res.getWriter().write("{\"success\":false,\"errorMsg\":\"请先登录\"}"); return false;
            }
        }).addPathPatterns("/blog/**", "/follow/**", "/blog-comments/**", "/upload/**")
                .excludePathPatterns("/blog/hot").order(0);
    }
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uri = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/imgs/**").addResourceLocations(uri.endsWith("/") ? uri : uri + "/");
    }
}
