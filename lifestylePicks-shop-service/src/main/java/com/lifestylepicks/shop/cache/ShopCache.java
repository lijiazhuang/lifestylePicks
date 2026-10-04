package com.lifestylepicks.shop.cache;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.lifestylepicks.shop.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 迁移原服务实际启用的穿透缓存，保留 key、JSON 格式和过期时间。 */
@Component
public class ShopCache {
    public static final String KEY_PREFIX = "cache:shop:";
    private final StringRedisTemplate redis;

    public ShopCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Shop get(Long id, Supplier<Shop> fallback) {
        String key = KEY_PREFIX + id;
        String json = redis.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, Shop.class);
        }
        if (json != null) {
            return null;
        }
        Shop shop = fallback.get();
        if (shop == null) {
            redis.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
        } else {
            redis.opsForValue().set(key, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        }
        return shop;
    }

    public void evict(Long id) {
        redis.delete(KEY_PREFIX + id);
    }
}
