package com.lifestylepicks.user.controller;

import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.dto.Result;
import com.lifestylepicks.user.dto.LoginFormDTO;
import com.lifestylepicks.user.entity.UserInfo;
import com.lifestylepicks.user.mapper.UserInfoMapper;
import com.lifestylepicks.user.service.UserService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {
    private final UserService service;
    private final UserInfoMapper infoMapper;

    public UserController(UserService service, UserInfoMapper infoMapper) {
        this.service = service;
        this.infoMapper = infoMapper;
    }
    @PostMapping("/code")
    public Result code(@RequestParam String phone) { return service.sendCode(phone); }
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO form) { return service.login(form); }
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token) { return service.logout(token); }
    @GetMapping("/me")
    public Result me() { return Result.ok(service.summary(UserContext.getUserId())); }
    @GetMapping("/{id}")
    public Result user(@PathVariable Long id) { return Result.ok(service.summary(id)); }
    @GetMapping("/info/{id}")
    public Result info(@PathVariable Long id) {
        UserInfo info = infoMapper.selectById(id);
        if (info != null) { info.setCreateTime(null); info.setUpdateTime(null); }
        return Result.ok(info);
    }
    @PostMapping("/sign")
    public Result sign() { return service.sign(); }
    @GetMapping("/sign/count")
    public Result signCount() { return service.signCount(); }
}
