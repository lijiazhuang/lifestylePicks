package com.lifestylepicks.trade.config;
import com.lifestylepicks.common.context.UserContext;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Configuration
public class TradeWebConfig implements WebMvcConfigurer {
    @Bean(destroyMethod="shutdown")
    public RedissonClient redissonClient(RedisProperties properties){
        Config config=new Config();
        SingleServerConfig server=config.useSingleServer().setAddress("redis://"+properties.getHost()+":"+properties.getPort()).setDatabase(properties.getDatabase());
        if(properties.getPassword()!=null && !properties.getPassword().isEmpty()){server.setPassword(properties.getPassword());}
        return Redisson.create(config);
    }
    @Override public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(new HandlerInterceptor(){
            @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) throws Exception{
                if("GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod())){
                    if(req.getRequestURI().startsWith("/voucher/list/")){return true;}
                }
                if(UserContext.getUserId()!=null){return true;}
                res.setStatus(401);res.setContentType("application/json");res.setCharacterEncoding("UTF-8");
                res.getWriter().write("{\"success\":false,\"errorMsg\":\"请先登录\"}");return false;
            }
        }).addPathPatterns("/voucher/**","/voucher-order/**").order(0);
    }
}
