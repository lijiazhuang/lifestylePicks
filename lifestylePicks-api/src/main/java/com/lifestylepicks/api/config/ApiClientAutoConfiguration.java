package com.lifestylepicks.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifestylepicks.api.client.UserClient;
import com.lifestylepicks.api.http.ApiRestTemplateFactory;
import com.lifestylepicks.api.http.HttpUserClient;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({RestTemplate.class, RestTemplateBuilder.class, ObjectMapper.class})
@ConditionalOnBean({RestTemplateBuilder.class, ObjectMapper.class})
@ConditionalOnProperty(prefix = "lifestylepicks.api", name = "enabled", havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter({RestTemplateAutoConfiguration.class, JacksonAutoConfiguration.class})
@EnableConfigurationProperties(ApiClientProperties.class)
public class ApiClientAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ApiRestTemplateFactory apiRestTemplateFactory(RestTemplateBuilder builder, ApiClientProperties properties) {
        return new ApiRestTemplateFactory(builder, properties);
    }

    @Bean
    @ConditionalOnMissingBean(UserClient.class)
    public UserClient userClient(ApiRestTemplateFactory factory, ObjectMapper json,
                                 ApiClientProperties properties, Environment environment) {
        String uri = properties.getUserServiceUri();
        if (!environment.containsProperty("lifestylepicks.api.user-service-uri")) {
            // 兼容现有单体配置及网关共用的环境变量。
            uri = environment.getProperty("lifestylepicks.user-service-uri",
                    environment.getProperty("USER_SERVICE_URI", uri));
        }
        return new HttpUserClient(factory.create(), json, uri);
    }
}
