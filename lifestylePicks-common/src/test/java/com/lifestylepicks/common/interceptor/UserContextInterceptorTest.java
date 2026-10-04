package com.lifestylepicks.common.interceptor;

import com.lifestylepicks.common.constant.UserHeaders;
import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.context.UserInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UserContextInterceptorTest {

    private final UserContextInterceptor interceptor = new UserContextInterceptor();

    @AfterEach
    void cleanContext() {
        UserContext.clear();
    }

    @Test
    void restoresIdentityAndClearsAfterCompletion() throws Exception {
        MockHttpServletRequest request = request("42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(Long.valueOf(42), UserContext.getUserId());
        assertEquals(Long.valueOf(42), UserContext.getUser().getUserId());
        interceptor.afterCompletion(request, response, new Object(), new RuntimeException("business failure"));
        assertNull(UserContext.getUser());
    }

    @Test
    void anonymousRequestDoesNotReuseOldIdentity() throws Exception {
        UserContext.setUser(new UserInfo(42L));
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()));
        assertNull(UserContext.getUserId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "abc", "42,84", "9223372036854775808", "+42", " 42", "42 "})
    void rejectsInvalidIdentityWithoutLeavingContext(String id) throws Exception {
        UserContext.setUser(new UserInfo(42L));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request(id), response, new Object()));
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("用户身份信息无效"));
        assertNull(UserContext.getUser());
    }

    @Test
    void rejectsDuplicateHeaders() throws Exception {
        MockHttpServletRequest request = request("42");
        request.addHeader(UserHeaders.USER_ID, "84");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(400, response.getStatus());
        assertNull(UserContext.getUser());
    }

    @Test
    void asyncReleaseClearsOriginalThreadAndRedispatchRestoresIdentity() throws Exception {
        MockHttpServletRequest request = request("42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());
        interceptor.afterConcurrentHandlingStarted(request, response, new Object());
        assertNull(UserContext.getUserId());
        interceptor.preHandle(request, response, new Object());
        assertEquals(Long.valueOf(42), UserContext.getUserId());
        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(UserContext.getUserId());
    }

    @Test
    void workerThreadsDoNotInheritRequestIdentity() throws Exception {
        UserContext.setUser(new UserInfo(42L));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertNull(executor.submit(() -> {
                return UserContext.getUserId();
            }).get(2, TimeUnit.SECONDS));
            assertEquals(Long.valueOf(42), UserContext.getUserId());
        } finally {
            executor.shutdownNow();
        }
    }

    private MockHttpServletRequest request(String id) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UserHeaders.USER_ID, id);
        return request;
    }
}
