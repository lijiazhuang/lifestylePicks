package example.consumer;

import com.lifestylepicks.common.constant.UserHeaders;
import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.interceptor.UserContextInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 消费端位于 common 包之外，验证只引入依赖即可通过 spring.factories 自动生效。 */
class UserContextAutoConfigurationTest {

    private final WebApplicationContextRunner mvcRunner = new WebApplicationContextRunner()
            .withUserConfiguration(ConsumerApplication.class);

    @AfterEach
    void cleanContext() {
        UserContext.clear();
    }

    @Test
    void autoRegistersOutsideConsumerPackageAndServesAuthenticatedThenAnonymousRequests() {
        mvcRunner.run(context -> {
            assertThat(context).hasSingleBean(UserContextInterceptor.class);
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/probe").header(UserHeaders.USER_ID, "42"))
                    .andExpect(status().isOk()).andExpect(content().json("{\"userId\":42}"));
            assertThat(UserContext.getUserId()).isNull();
            mvc.perform(get("/probe")).andExpect(status().isOk())
                    .andExpect(content().json("{\"userId\":null}"));
            assertThat(UserContext.getUserId()).isNull();
        });
    }

    @Test
    void mvcExceptionStillClearsContext() {
        mvcRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            assertThrows(Exception.class, () -> mvc.perform(get("/failure").header(UserHeaders.USER_ID, "42")));
            assertThat(UserContext.getUser()).isNull();
        });
    }

    @Test
    void mvcRejectsAmbiguousIdentityBeforeController() {
        mvcRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/probe").header(UserHeaders.USER_ID, "42", "84"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"success\":false,\"errorMsg\":\"用户身份信息无效\"}"));
            assertThat(UserContext.getUser()).isNull();
        });
    }

    @Test
    void canDisableAutomaticRegistration() {
        mvcRunner.withPropertyValues("lifestylepicks.common.user-context.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(UserContextInterceptor.class));
    }

    @Test
    void usesServiceProvidedInterceptor() {
        mvcRunner.withUserConfiguration(CustomInterceptor.class).run(context -> {
            assertThat(context).hasSingleBean(UserContextInterceptor.class);
            assertThat(context.getBean(UserContextInterceptor.class)).isSameAs(context.getBean("customInterceptor"));
        });
    }

    @Test
    void doesNotRegisterInNonWebApplications() {
        new ApplicationContextRunner().withUserConfiguration(ConsumerApplication.class)
                .run(context -> assertThat(context).doesNotHaveBean(UserContextInterceptor.class));
    }

    @Test
    void doesNotRegisterInReactiveApplicationsEvenWhenMvcClassesAreAvailable() {
        new ReactiveWebApplicationContextRunner().withUserConfiguration(ConsumerApplication.class)
                .run(context -> assertThat(context).doesNotHaveBean(UserContextInterceptor.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConsumerApplication {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomInterceptor {
        @Bean
        UserContextInterceptor customInterceptor() {
            return new UserContextInterceptor();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        Map<String, Long> probe() {
            return Collections.singletonMap("userId", UserContext.getUserId());
        }

        @GetMapping("/failure")
        void failure() {
            throw new IllegalStateException("business failure");
        }
    }
}
