package com.lifestylepicks.api.feign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifestylepicks.api.client.UserClient;
import com.lifestylepicks.api.contract.UserApiPaths;
import com.lifestylepicks.api.dto.UserSummary;
import com.lifestylepicks.api.exception.RemoteCallException;
import org.springframework.util.Assert;
import feign.FeignException;
import java.util.*;
import java.util.stream.Collectors;

/** 保留业务批量契约，HTTP 请求交给声明式 Feign 接口。 */
public class FeignUserClient implements UserClient {
    private final UserFeignClient remote;
    private final ObjectMapper json;

    public FeignUserClient(UserFeignClient remote, ObjectMapper json) {
        this.remote = remote;
        this.json = json;
    }

    @Override
    public List<UserSummary> findBatch(List<Long> input) {
        Assert.notNull(input, "用户 ID 列表不能为空引用");
        Assert.isTrue(input.stream().allMatch(id -> id != null && id > 0), "用户 ID 必须为正整数");
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(input));
        if (ids.isEmpty()) { return Collections.emptyList(); }
        Map<Long, UserSummary> users = new HashMap<>();
        for (int start = 0; start < ids.size(); start += UserApiPaths.MAX_BATCH_SIZE) {
            List<Long> batch = ids.subList(start, Math.min(start + UserApiPaths.MAX_BATCH_SIZE, ids.size()));
            String values = batch.stream().map(String::valueOf).collect(Collectors.joining(","));
            JsonNode response;
            try {
                response = remote.findBatch(values);
            } catch (FeignException exception) {
                throw new RemoteCallException("用户服务不可用", exception);
            }
            if (response == null || !response.path("success").asBoolean()
                    || !response.path("data").isArray()) {
                throw new RemoteCallException("用户服务资料响应无效");
            }
            for (JsonNode node : response.path("data")) {
                if (!node.path("id").isIntegralNumber() || !node.path("id").canConvertToLong()
                        || !batch.contains(node.path("id").longValue())) {
                    throw new RemoteCallException("用户服务返回了不属于查询范围的资料");
                }
                try {
                    UserSummary user = json.convertValue(node, UserSummary.class);
                    users.put(user.getId(), user);
                } catch (IllegalArgumentException exception) {
                    throw new RemoteCallException("用户服务资料格式无效", exception);
                }
            }
        }
        return ids.stream().map(users::get).filter(Objects::nonNull).collect(Collectors.toList());
    }
}
