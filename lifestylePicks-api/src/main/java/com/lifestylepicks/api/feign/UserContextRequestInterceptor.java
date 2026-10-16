package com.lifestylepicks.api.feign;

import com.lifestylepicks.common.constant.UserHeaders;
import com.lifestylepicks.common.context.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/** 每次调用重新读取当前身份，匿名请求不能沿用旧 Header。 */
public class UserContextRequestInterceptor implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate template) {
        template.removeHeader(UserHeaders.USER_ID);
        Long userId = UserContext.getUserId();
        if (userId != null) {
            template.header(UserHeaders.USER_ID, userId.toString());
        }
    }
}
