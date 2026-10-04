package com.lifestylepicks.shop.cache;

import com.lifestylepicks.shop.entity.Shop;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ShopGeoIndex {
    public static final String KEY_PREFIX = "shop:geo:";
    private final StringRedisTemplate redis;

    public ShopGeoIndex(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void synchronize(Shop before, Shop after) {
        if (before != null && before.getTypeId() != null) {
            redis.opsForGeo().remove(KEY_PREFIX + before.getTypeId(), before.getId().toString());
        }
        add(after);
    }

    public void add(Shop shop) {
        if (shop != null && shop.getTypeId() != null && shop.getX() != null && shop.getY() != null) {
            redis.opsForGeo().add(KEY_PREFIX + shop.getTypeId(),
                    new Point(shop.getX(), shop.getY()), shop.getId().toString());
        }
    }
}
