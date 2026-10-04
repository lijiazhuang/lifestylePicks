package com.lifestylepicks.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifestylepicks.api.config.ApiClientProperties;
import com.lifestylepicks.api.dto.UserSummary;
import com.lifestylepicks.api.exception.RemoteCallException;
import com.lifestylepicks.common.constant.UserHeaders;
import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.context.UserInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpUserClientTest {
    private final RestTemplate rest = new ApiRestTemplateFactory(new RestTemplateBuilder(), new ApiClientProperties()).create();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(rest);
    private final HttpUserClient client = new HttpUserClient(rest, new ObjectMapper(), "http://127.0.0.1:8083");

    @AfterEach
    void clearContext() { UserContext.clear(); }

    @Test
    void preservesOrderDeduplicatesAndSkipsMissingUsers() {
        server.expect(request -> assertEquals("ids=2,1,999", request.getURI().getQuery()))
                .andRespond(withSuccess("{\"success\":true,\"data\":[{\"id\":1,\"nickName\":\"one\"},{\"id\":2,\"nickName\":\"two\"}]}", MediaType.APPLICATION_JSON));
        assertEquals(Arrays.asList(2L, 1L), client.findBatch(Arrays.asList(2L, 1L, 999L, 2L)).stream()
                .map(UserSummary::getId).collect(Collectors.toList()));
        server.verify();
    }

    @Test
    void splitsRequestsAtServerBatchLimit() {
        server.expect(request -> {
            assertEquals("/internal/users/batch", request.getURI().getPath());
            String[] ids = request.getURI().getQuery().substring(4).split(",");
            assertEquals(100, ids.length);
            assertEquals("100", ids[99]);
        }).andRespond(withSuccess("{\"success\":true,\"data\":[{\"id\":100},{\"id\":1}]}", MediaType.APPLICATION_JSON));
        server.expect(request -> assertEquals("ids=101", request.getURI().getQuery()))
                .andRespond(withSuccess("{\"success\":true,\"data\":[{\"id\":101}]}", MediaType.APPLICATION_JSON));
        assertEquals(Arrays.asList(1L, 100L, 101L), client.findBatch(LongStream.rangeClosed(1, 101)
                .boxed().collect(Collectors.toList())).stream().map(UserSummary::getId).collect(Collectors.toList()));
        server.verify();
    }

    @Test
    void invalidOrEmptyInputDoesNotSendHttpRequests() {
        assertTrue(client.findBatch(Collections.emptyList()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> client.findBatch(null));
        assertThrows(IllegalArgumentException.class, () -> client.findBatch(Arrays.asList(1L, null)));
        assertThrows(IllegalArgumentException.class, () -> client.findBatch(Collections.singletonList(-1L)));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"success\":false}", "{\"success\":true,\"data\":{}}",
            "{\"success\":true,\"data\":[{\"id\":999}]}", "{\"success\":true,\"data\":[{\"id\":\"1\"}]}"})
    void rejectsInvalidOrUnrequestedResponseData(String response) {
        server.expect(request -> { }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThrows(RemoteCallException.class, () -> client.findBatch(Collections.singletonList(1L)));
        server.verify();
    }

    @Test
    void exposesHttpFailureWithoutLocalDatabaseFallback() {
        server.expect(request -> { }).andRespond(withServerError());
        assertThrows(RemoteCallException.class, () -> client.findBatch(Collections.singletonList(1L)));
        server.verify();
    }

    @Test
    void sharedTransportForwardsCurrentIdentityAndClearsItForNextAnonymousCall() {
        RestTemplate template = new ApiRestTemplateFactory(new RestTemplateBuilder()
                .defaultHeader(UserHeaders.USER_ID, "999"), new ApiClientProperties()).create();
        MockRestServiceServer mock = MockRestServiceServer.createServer(template);
        HttpUserClient caller = new HttpUserClient(template, new ObjectMapper(), "http://127.0.0.1:8083");
        mock.expect(request -> assertEquals("42", request.getHeaders().getFirst(UserHeaders.USER_ID)))
                .andRespond(withSuccess("{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));
        mock.expect(request -> assertNull(request.getHeaders().getFirst(UserHeaders.USER_ID)))
                .andRespond(withSuccess("{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));
        UserContext.setUser(new UserInfo(42L));
        caller.findBatch(Collections.singletonList(1L));
        UserContext.clear();
        caller.findBatch(Collections.singletonList(1L));
        mock.verify();
    }
}
