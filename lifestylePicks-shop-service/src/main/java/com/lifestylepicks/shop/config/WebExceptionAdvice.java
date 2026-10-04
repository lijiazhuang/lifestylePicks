package com.lifestylepicks.shop.config;

import com.lifestylepicks.common.dto.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class WebExceptionAdvice {
    private static final Logger LOG = LoggerFactory.getLogger(WebExceptionAdvice.class);

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException exception) {
        LOG.error("店铺请求处理失败", exception);
        return Result.fail("服务器异常");
    }
}
