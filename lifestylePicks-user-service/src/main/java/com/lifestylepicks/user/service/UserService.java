package com.lifestylepicks.user.service;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.dto.Result;
import com.lifestylepicks.api.dto.UserSummary;
import com.lifestylepicks.user.config.UserProperties;
import com.lifestylepicks.user.dto.LoginFormDTO;
import com.lifestylepicks.user.entity.User;
import com.lifestylepicks.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class UserService {
    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> CONSUME_CODE = script("consume-code.lua");
    private static final DefaultRedisScript<Long> SAVE_TOKEN = script("save-token.lua");
    private static final DefaultRedisScript<Long> LOGOUT = script("logout.lua");
    private final UserMapper mapper;
    private final StringRedisTemplate redis;
    private final UserProperties properties;
    private final Clock clock;

    public UserService(UserMapper mapper, StringRedisTemplate redis, UserProperties properties, Clock clock) {
        this.mapper = mapper;
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/" + name));
        script.setResultType(Long.class);
        return script;
    }

    private boolean validPhone(String phone) { return phone != null && phone.matches("1[3-9][0-9]{9}"); }

    public Result sendCode(String phone) {
        if (!validPhone(phone)) { return Result.fail("手机号格式错误！"); }
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1000000));
        redis.opsForValue().set("login:code:" + phone, code, properties.getCodeTtl());
        if (properties.isLogCode()) {
            LOG.info("开发环境模拟验证码，手机号 {}，验证码 {}", phone, code);
        }
        return Result.ok();
    }

    public Result login(LoginFormDTO form) {
        if (!validPhone(form.getPhone())) { return Result.fail("手机号格式错误！"); }
        if (form.getCode() == null || !form.getCode().matches("[0-9]{6}")) {
            return Result.fail("验证码错误");
        }
        Long accepted = redis.execute(CONSUME_CODE, Collections.singletonList("login:code:" + form.getPhone()), form.getCode());
        if (!Long.valueOf(1).equals(accepted)) { return Result.fail("验证码错误或已过期"); }
        User user = findByPhone(form.getPhone());
        if (user == null) {
            user = new User().setPhone(form.getPhone()).setNickName("user_" + RandomUtil.randomString(10));
            try {
                mapper.insert(user);
            } catch (DuplicateKeyException duplicate) {
                // 原表已有手机号唯一索引；并发注册时复用获胜事务的用户。
                user = findByPhone(form.getPhone());
                if (user == null) { throw duplicate; }
            }
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.execute(SAVE_TOKEN, Collections.singletonList("login:token:" + token),
                user.getId().toString(), value(user.getNickName()), value(user.getIcon()),
                Long.toString(properties.getTokenTtl().getSeconds()));
        return Result.ok(token);
    }

    private User findByPhone(String phone) {
        return mapper.selectOne(new QueryWrapper<User>().eq("phone", phone));
    }

    private String value(String text) { return text == null ? "" : text; }

    public Result logout(String token) {
        if (token != null && !token.isEmpty()) {
            // 只删除当前用户的当前 Token，支持重复退出，不影响其他设备。
            redis.execute(LOGOUT, Collections.singletonList("login:token:" + token), UserContext.getUserId().toString());
        }
        return Result.ok();
    }

    public UserSummary summary(Long id) {
        User user = mapper.selectById(id);
        return user == null ? null : new UserSummary(user.getId(), user.getNickName(), user.getIcon());
    }

    public List<UserSummary> summaries(List<Long> ids) {
        if (ids.isEmpty()) { return Collections.emptyList(); }
        List<User> users = mapper.selectList(new QueryWrapper<User>().select("id", "nick_name", "icon").in("id", ids));
        Map<Long, UserSummary> byId = new HashMap<>();
        users.forEach(user -> byId.put(user.getId(), new UserSummary(user.getId(), user.getNickName(), user.getIcon())));
        List<UserSummary> result = new ArrayList<>();
        ids.forEach(id -> { if (byId.containsKey(id)) { result.add(byId.get(id)); } });
        return result;
    }

    public Result sign() {
        LocalDate now = LocalDate.now(clock);
        redis.opsForValue().setBit(signKey(now), now.getDayOfMonth() - 1, true);
        return Result.ok();
    }

    public Result signCount() {
        LocalDate now = LocalDate.now(clock);
        List<Long> bits = redis.opsForValue().bitField(signKey(now), BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(now.getDayOfMonth())).valueAt(0));
        long value = bits == null || bits.isEmpty() || bits.get(0) == null ? 0 : bits.get(0);
        int count = 0;
        while ((value & 1) != 0) { count++; value >>>= 1; }
        return Result.ok(count);
    }

    private String signKey(LocalDate date) {
        return "sign:" + UserContext.getUserId() + date.format(DateTimeFormatter.ofPattern(":yyyyMM"));
    }
}
