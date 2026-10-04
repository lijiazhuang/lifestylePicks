package example.consumer;

import com.lifestylepicks.api.client.UserClient;
import com.lifestylepicks.api.http.HttpUserClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;

class ApiClientAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConsumerApplication.class);

    @Test
    void dependencyAutomaticallyRegistersClientOutsideComponentScan() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(UserClient.class);
            assertThat(context.getBean(UserClient.class)).isInstanceOf(HttpUserClient.class);
        });
    }

    @Test
    void supportsExistingServiceUriEnvironmentSetting() {
        runner.withPropertyValues("USER_SERVICE_URI=http://127.0.0.1:19000").run(context ->
                assertThat(ReflectionTestUtils.getField(context.getBean(UserClient.class), "baseUri"))
                        .isEqualTo("http://127.0.0.1:19000"));
    }

    @Test
    void newPropertyTakesPrecedenceOverLegacyAlias() {
        runner.withPropertyValues("lifestylepicks.api.user-service-uri=http://127.0.0.1:19001",
                "lifestylepicks.user-service-uri=http://127.0.0.1:19002", "USER_SERVICE_URI=http://127.0.0.1:19003")
                .run(context -> assertThat(ReflectionTestUtils.getField(context.getBean(UserClient.class), "baseUri"))
                        .isEqualTo("http://127.0.0.1:19001"));
    }

    @Test
    void respectsCallerProvidedClientForExplicitRollback() {
        runner.withUserConfiguration(CustomClientConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(UserClient.class);
            assertThat(context.getBean(UserClient.class)).isSameAs(context.getBean("localClient"));
        });
    }

    @Test
    void canDisableAutoConfiguration() {
        runner.withPropertyValues("lifestylepicks.api.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(UserClient.class));
    }

    @Test
    void rejectsZeroTimeoutDuringStartup() {
        runner.withPropertyValues("lifestylepicks.api.read-timeout=0ms")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConsumerApplication { }

    @Configuration(proxyBeanMethods = false)
    static class CustomClientConfiguration {
        @Bean
        UserClient localClient() { return ids -> Collections.emptyList(); }
    }
}
