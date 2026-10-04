package com.lifestylepicks.api.client;

import com.lifestylepicks.api.dto.UserSummary;
import java.util.List;

/** 用户资料远程调用契约：去重、按输入顺序返回，跳过不存在的用户。 */
public interface UserClient {
    List<UserSummary> findBatch(List<Long> ids);
}
