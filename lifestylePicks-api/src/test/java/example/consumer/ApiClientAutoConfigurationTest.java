package example.consumer;

import com.lifestylepicks.api.client.UserClient;
import com.lifestylepicks.api.feign.FeignUserClient;
import com.lifestylepicks.api.feign.UserFeignClient;
import com.lifestylepicks.api.feign.UserContextRequestInterceptor;
import com.lifestylepicks.api.exception.RemoteCallException;
import com.lifestylepicks.common.constant.UserHeaders;
import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.context.UserInfo;
import feign.Request;
import feign.RequestInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.lang.reflect.Proxy;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiClientAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConsumerApplication.class);

    @AfterEach
    void clearContext() { UserContext.clear(); }

    @Test
    void dependencyAutomaticallyRegistersRealFeignProxyOutsideComponentScan() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(UserClient.class).hasSingleBean(UserFeignClient.class);
            assertThat(context.getBean(UserClient.class)).isInstanceOf(FeignUserClient.class);
            assertThat(Proxy.isProxyClass(context.getBean(UserFeignClient.class).getClass())).isTrue();
            assertThat(context.getBean(UserFeignClient.class).toString()).contains("http://127.0.0.1:8083");
            assertThat(context).doesNotHaveBean(RequestInterceptor.class);
            assertThat(context.getBean(FeignContext.class)
                    .getInstances("lifestylepicksUserClient", RequestInterceptor.class).values())
                    .hasSize(1).first().isInstanceOf(UserContextRequestInterceptor.class);
        });
    }

    @Test
    void supportsExistingServiceUriEnvironmentSetting() throws Exception {
        try (FakeUserServer server = new FakeUserServer()) {
            runner.withPropertyValues("USER_SERVICE_URI=" + server.url()).run(context -> {
                assertThat(context.getBean(UserClient.class).findBatch(Collections.singletonList(1L))).isEmpty();
                FakeUserServer.Call call = server.take();
                assertThat(call).isNotNull();
                assertThat(call.method).isEqualTo("GET");
                assertThat(call.uri.getPath()).isEqualTo("/internal/users/batch");
                assertThat(call.uri.getQuery()).isEqualTo("ids=1");
            });
        }
    }

    @Test
    void supportsLegacyPropertyAndBasePath() throws Exception {
        try (FakeUserServer server = new FakeUserServer()) {
            runner.withPropertyValues("lifestylepicks.user-service-uri=" + server.url() + "/prefix/",
                    "USER_SERVICE_URI=http://127.0.0.1:1").run(context -> {
                context.getBean(UserClient.class).findBatch(Collections.singletonList(1L));
                assertThat(server.take().uri.getPath()).isEqualTo("/prefix/internal/users/batch");
            });
        }
    }

    @Test
    void newPropertyTakesPrecedenceOverLegacyAlias() throws Exception {
        try (FakeUserServer server = new FakeUserServer()) {
            runner.withPropertyValues("lifestylepicks.api.user-service-uri=" + server.url(),
                    "lifestylepicks.user-service-uri=http://127.0.0.1:1", "USER_SERVICE_URI=http://127.0.0.1:2")
                    .run(context -> {
                        context.getBean(UserClient.class).findBatch(Collections.singletonList(1L));
                        assertThat(server.take()).isNotNull();
                    });
        }
    }

    @Test
    void forwardsCurrentIdentityAndClearsAnonymousHeaderOverHttp() throws Exception {
        try (FakeUserServer server = new FakeUserServer()) {
            runner.withPropertyValues("USER_SERVICE_URI=" + server.url()).run(context -> {
                UserClient client = context.getBean(UserClient.class);
                UserContext.setUser(new UserInfo(42L));
                client.findBatch(Collections.singletonList(1L));
                assertThat(server.take().headers.get(UserHeaders.USER_ID)).containsExactly("42");
                UserContext.clear();
                client.findBatch(Collections.singletonList(1L));
                assertThat(server.take().headers.getFirst(UserHeaders.USER_ID)).isNull();
                UserContext.setUser(new UserInfo(43L));
                client.findBatch(Collections.singletonList(1L));
                assertThat(server.take().headers.get(UserHeaders.USER_ID)).containsExactly("43");
            });
        }
    }

    @Test
    void configuredReadTimeoutFailsInsteadOfWaitingForSlowServer() throws Exception {
        try (FakeUserServer server = new FakeUserServer()) {
            server.enqueue(200, "{\"success\":true,\"data\":[]}", 300, null);
            runner.withPropertyValues("USER_SERVICE_URI=" + server.url(), "lifestylepicks.api.read-timeout=50ms",
                    "lifestylepicks.api.connect-timeout=123ms").run(context -> {
                Request.Options options = context.getBean(FeignContext.class)
                        .getInstance("lifestylepicksUserClient", Request.Options.class);
                assertThat(options.connectTimeoutMillis()).isEqualTo(123);
                assertThat(options.readTimeoutMillis()).isEqualTo(50);
                assertThrows(RemoteCallException.class,
                        () -> context.getBean(UserClient.class).findBatch(Collections.singletonList(1L)));
                assertThat(server.take()).isNotNull();
                assertThat(server.remainingCalls()).isZero();
            });
        }
    }

    @Test
    void exposesHttpFailureWithoutImplicitRetry() throws Exception {
        try (FakeUserServer server = new FakeUserServer()) {
            server.enqueue(503, "{}", 0, null);
            runner.withPropertyValues("USER_SERVICE_URI=" + server.url()).run(context -> {
                assertThrows(RemoteCallException.class,
                        () -> context.getBean(UserClient.class).findBatch(Collections.singletonList(1L)));
                assertThat(server.take()).isNotNull();
                assertThat(server.remainingCalls()).isZero();
            });
        }
    }

    @Test
    void doesNotForwardIdentityToRedirectTarget() throws Exception {
        try (FakeUserServer server = new FakeUserServer(); FakeUserServer target = new FakeUserServer()) {
            server.enqueue(302, "{}", 0, target.url() + "/internal/users/batch?ids=1");
            runner.withPropertyValues("USER_SERVICE_URI=" + server.url()).run(context -> {
                UserContext.setUser(new UserInfo(42L));
                assertThrows(RemoteCallException.class,
                        () -> context.getBean(UserClient.class).findBatch(Collections.singletonList(1L)));
                assertThat(server.take().headers.getFirst(UserHeaders.USER_ID)).isEqualTo("42");
                assertThat(target.remainingCalls()).isZero();
            });
        }
    }

    @Test
    void respectsCallerProvidedClientForExplicitRollback() {
        runner.withUserConfiguration(CustomClientConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(UserClient.class).doesNotHaveBean(UserFeignClient.class);
            assertThat(context.getBean(UserClient.class)).isSameAs(context.getBean("localClient"));
        });
    }

    @Test
    void canDisableAutoConfiguration() {
        runner.withPropertyValues("lifestylepicks.api.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(UserClient.class).doesNotHaveBean(UserFeignClient.class));
    }

    @Test
    void rejectsZeroTimeoutDuringStartup() {
        runner.withPropertyValues("lifestylepicks.api.read-timeout=0ms").run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://127.0.0.1", "http://user:pass@127.0.0.1", "http://127.0.0.1?secret=1", "http://127.0.0.1#part"})
    void rejectsUnsafeOrInvalidServiceAddress(String url) {
        runner.withPropertyValues("lifestylepicks.api.user-service-uri=" + url).run(context -> assertThat(context).hasFailed());
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
