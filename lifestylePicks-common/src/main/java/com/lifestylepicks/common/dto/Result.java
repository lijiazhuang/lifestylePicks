package com.lifestylepicks.common.dto;

import java.util.List;

/** 保持原前端的 success/errorMsg/data/total 响应协议。 */
public final class Result {

    private final Boolean success;
    private final String errorMsg;
    private final Object data;
    private final Long total;

    private Result(Boolean success, String errorMsg, Object data, Long total) {
        this.success = success;
        this.errorMsg = errorMsg;
        this.data = data;
        this.total = total;
    }

    public static Result ok() {
        return new Result(true, null, null, null);
    }

    public static Result ok(Object data) {
        return new Result(true, null, data, null);
    }

    public static Result ok(List<?> data, Long total) {
        return new Result(true, null, data, total);
    }

    public static Result fail(String message) {
        return new Result(false, message, null, null);
    }

    public Boolean getSuccess() { return success; }
    public String getErrorMsg() { return errorMsg; }
    public Object getData() { return data; }
    public Long getTotal() { return total; }
}
