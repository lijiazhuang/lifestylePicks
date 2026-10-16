package com.lifestylepicks.api.feign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifestylepicks.api.dto.UserSummary;
import com.lifestylepicks.api.exception.RemoteCallException;
import com.lifestylepicks.common.constant.UserHeaders;
import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.context.UserInfo;
import feign.FeignException;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class FeignUserClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final UserFeignClient remote = mock(UserFeignClient.class);
    private final FeignUserClient client = new FeignUserClient(remote, json);

    @AfterEach
    void clearContext() { UserContext.clear(); }

    @Test
    void preservesOrderDeduplicatesAndSkipsMissingUsers() throws Exception {
        when(remote.findBatch("2,1,999")).thenReturn(json.readTree(
                "{\"success\":true,\"data\":[{\"id\":1,\"nickName\":\"one\"},{\"id\":2,\"nickName\":\"two\"}]}"));
        assertEquals(Arrays.asList(2L, 1L), client.findBatch(Arrays.asList(2L, 1L, 999L, 2L)).stream()
                .map(UserSummary::getId).collect(Collectors.toList()));
        verify(remote).findBatch("2,1,999");
    }

    @Test
    void splitsRequestsAtServerBatchLimit() throws Exception {
        String first = LongStream.rangeClosed(1, 100).mapToObj(String::valueOf).collect(Collectors.joining(","));
        when(remote.findBatch(first)).thenReturn(json.readTree("{\"success\":true,\"data\":[{\"id\":100},{\"id\":1}]}"));
        when(remote.findBatch("101")).thenReturn(json.readTree("{\"success\":true,\"data\":[{\"id\":101}]}"));
        assertEquals(Arrays.asList(1L, 100L, 101L), client.findBatch(LongStream.rangeClosed(1, 101)
                .boxed().collect(Collectors.toList())).stream().map(UserSummary::getId).collect(Collectors.toList()));
        verify(remote).findBatch(first);
        verify(remote).findBatch("101");
        verifyNoMoreInteractions(remote);
    }

    @Test
    void invalidOrEmptyInputDoesNotCallRemoteService() {
        assertTrue(client.findBatch(Collections.emptyList()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> client.findBatch(null));
        assertThrows(IllegalArgumentException.class, () -> client.findBatch(Arrays.asList(1L, null)));
        assertThrows(IllegalArgumentException.class, () -> client.findBatch(Collections.singletonList(-1L)));
        verifyNoInteractions(remote);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"success\":false}", "{\"success\":true,\"data\":{}}",
            "{\"success\":true,\"data\":[{\"id\":999}]}", "{\"success\":true,\"data\":[{\"id\":\"1\"}]}"})
    void rejectsInvalidOrUnrequestedResponseData(String response) throws Exception {
        when(remote.findBatch("1")).thenReturn(json.readTree(response));
        assertThrows(RemoteCallException.class, () -> client.findBatch(Collections.singletonList(1L)));
    }

    @Test
    void rejectsNullResponse() {
        assertThrows(RemoteCallException.class, () -> client.findBatch(Collections.singletonList(1L)));
    }

    @Test
    void exposesFeignFailureWithoutLocalDatabaseFallback() {
        FeignException failure = mock(FeignException.class);
        when(remote.findBatch(anyString())).thenThrow(failure);
        RemoteCallException actual = assertThrows(RemoteCallException.class,
                () -> client.findBatch(Collections.singletonList(1L)));
        assertSame(failure, actual.getCause());
    }

    @Test
    void interceptorOverwritesSpoofedIdentityAndClearsReusedTemplate() {
        UserContextRequestInterceptor interceptor = new UserContextRequestInterceptor();
        RequestTemplate template = new RequestTemplate();
        template.header(UserHeaders.USER_ID, "999", "888");
        UserContext.setUser(new UserInfo(42L));
        interceptor.apply(template);
        assertEquals(Collections.singletonList("42"), new ArrayList<>(template.headers().get(UserHeaders.USER_ID)));
        UserContext.clear();
        interceptor.apply(template);
        assertFalse(template.headers().containsKey(UserHeaders.USER_ID));
        UserContext.setUser(new UserInfo(43L));
        interceptor.apply(template);
        assertEquals(Collections.singletonList("43"), new ArrayList<>(template.headers().get(UserHeaders.USER_ID)));
    }
}
