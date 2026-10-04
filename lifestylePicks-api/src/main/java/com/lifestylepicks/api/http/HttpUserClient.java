package com.lifestylepicks.api.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifestylepicks.api.client.UserClient;
import com.lifestylepicks.api.contract.UserApiPaths;
import com.lifestylepicks.api.dto.UserSummary;
import com.lifestylepicks.api.exception.RemoteCallException;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public class HttpUserClient implements UserClient {
    private final RestTemplate rest;
    private final ObjectMapper json;
    private final String baseUri;

    public HttpUserClient(RestTemplate rest, ObjectMapper json, String baseUri) {
        URI uri = URI.create(baseUri);
        Assert.isTrue(("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null && uri.getRawQuery() == null
                && uri.getFragment() == null, "用户服务地址必须是无凭据和查询参数的 HTTP/HTTPS 基础地址");
        this.baseUri = baseUri.replaceAll("/+$", "");
        this.rest = rest;
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
            URI uri = UriComponentsBuilder.fromHttpUrl(baseUri + UserApiPaths.BATCH_USERS)
                    .queryParam("ids", values).build().encode().toUri();
            JsonNode response;
            try {
                response = rest.getForObject(uri, JsonNode.class);
            } catch (RestClientException exception) {
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
