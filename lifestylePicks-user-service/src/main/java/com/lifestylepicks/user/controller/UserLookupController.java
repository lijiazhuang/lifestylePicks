package com.lifestylepicks.user.controller;

import com.lifestylepicks.common.dto.Result;
import com.lifestylepicks.api.contract.UserApiPaths;
import com.lifestylepicks.user.service.UserService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 仅供可信内部服务读取公开展示资料，网关不转发此路径到用户服务。 */
@RestController
public class UserLookupController {
    private final UserService service;
    public UserLookupController(UserService service) { this.service = service; }
    @GetMapping(UserApiPaths.BATCH_USERS)
    public Result batch(@RequestParam List<Long> ids) {
        if (ids.size() > UserApiPaths.MAX_BATCH_SIZE || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            return Result.fail("每次最多查询 100 个有效用户 ID");
        }
        return Result.ok(service.summaries(ids));
    }
}
