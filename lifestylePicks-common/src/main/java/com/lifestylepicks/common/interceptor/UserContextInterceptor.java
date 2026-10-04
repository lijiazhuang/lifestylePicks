package com.lifestylepicks.common.interceptor;

import com.lifestylepicks.common.constant.UserHeaders;
import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.context.UserInfo;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;

/** 恢复可信入口传递的身份，不再查询 Redis，也不代替业务权限判断。 */
public class UserContextInterceptor implements AsyncHandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // 先清理复用线程上的旧值，匿名请求不能继承上一次请求的身份。
        UserContext.clear();
        Enumeration<String> values = request.getHeaders(UserHeaders.USER_ID);
        if (values == null || !values.hasMoreElements()) {
            return true;
        }
        String id = values.nextElement();
        if (values.hasMoreElements() || id == null || !id.matches("[1-9][0-9]*")) {
            return invalidIdentity(response);
        }
        try {
            UserContext.setUser(new UserInfo(Long.valueOf(id)));
            return true;
        } catch (NumberFormatException exception) {
            return invalidIdentity(response);
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        // 正常响应、业务异常，以及后续拦截器拒绝请求时都清理。
        UserContext.clear();
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        // MVC 异步请求释放原 Servlet 线程时不会立即调用 afterCompletion。
        UserContext.clear();
    }

    private boolean invalidIdentity(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"success\":false,\"errorMsg\":\"用户身份信息无效\"}");
        return false;
    }
}
