package com.lifestylepicks.api.feign;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifestylepicks.api.contract.UserApiPaths;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 固定 URL 的用户服务调用，不通过服务名查找注册中心。 */
@FeignClient(name = "lifestylepicks-user", contextId = "lifestylepicksUserClient",
        url = "${lifestylepicks.api.user-service-uri:${lifestylepicks.user-service-uri:${USER_SERVICE_URI:http://127.0.0.1:8083}}}",
        configuration = ApiFeignConfiguration.class)
public interface UserFeignClient {
    @GetMapping(UserApiPaths.BATCH_USERS)
    JsonNode findBatch(@RequestParam("ids") String ids);
}
