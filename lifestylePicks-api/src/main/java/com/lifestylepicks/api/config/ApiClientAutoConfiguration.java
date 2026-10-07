package com.lifestylepicks.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifestylepicks.api.client.UserClient;
import com.lifestylepicks.api.feign.FeignUserClient;
import com.lifestylepicks.api.feign.UserFeignClient;
import feign.Feign;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({Feign.class, FeignClient.class, ObjectMapper.class})
@ConditionalOnBean(ObjectMapper.class)
@ConditionalOnProperty(prefix = "lifestylepicks.api", name = "enabled", havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter({FeignAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
@EnableConfigurationProperties(ApiClientProperties.class)
public class ApiClientAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(UserClient.class)
    @EnableFeignClients(clients = UserFeignClient.class)
    static class UserFeignClientConfiguration {
        @Bean
        public UserClient userClient(UserFeignClient remote, ObjectMapper json) {
            return new FeignUserClient(remote, json);
        }
    }
}
