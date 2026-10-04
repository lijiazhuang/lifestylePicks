package com.lifestylepicks.shop.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lifestylepicks.common.dto.Result;
import com.lifestylepicks.shop.cache.ShopCache;
import com.lifestylepicks.shop.cache.ShopGeoIndex;
import com.lifestylepicks.shop.entity.Shop;
import com.lifestylepicks.shop.mapper.ShopMapper;
import com.lifestylepicks.shop.service.IShopService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    private static final Logger LOG = LoggerFactory.getLogger(ShopServiceImpl.class);
    private static final int PAGE_SIZE = 5;
    private final ShopCache cache;
    private final ShopGeoIndex geo;
    private final StringRedisTemplate redis;

    public ShopServiceImpl(ShopCache cache, ShopGeoIndex geo, StringRedisTemplate redis) {
        this.cache = cache;
        this.geo = geo;
        this.redis = redis;
    }

    @Override
    public Result queryById(Long id) {
        Shop shop = cache.get(id, () -> getById(id));
        return shop == null ? Result.fail("店铺不存在！") : Result.ok(shop);
    }

    @Override
    @Transactional
    public Result create(Shop shop) {
        // 创建接口统一由数据库生成 ID。
        shop.setId(null);
        if (!save(shop)) {
            return Result.fail("新增店铺失败");
        }
        synchronizeAfterCommit(null, getById(shop.getId()));
        return Result.ok(shop.getId());
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        Shop before = getById(shop.getId());
        if (before == null) {
            return Result.fail("店铺不存在！");
        }
        if (!updateById(shop)) {
            return Result.fail("更新店铺失败");
        }
        synchronizeAfterCommit(before, getById(shop.getId()));
        return Result.ok();
    }

    private void synchronizeAfterCommit(Shop before, Shop after) {
        Runnable synchronize = () -> {
            // 先提交数据库，再清理缓存，避免其他请求在提交前重新缓存旧数据。
            try {
                cache.evict(after.getId());
            } catch (RuntimeException exception) {
                LOG.error("店铺 {} 已提交，但缓存清理失败", after.getId(), exception);
            }
            try {
                geo.synchronize(before, after);
            } catch (RuntimeException exception) {
                LOG.error("店铺 {} 已提交，但 GEO 更新失败", after.getId(), exception);
            }
        };
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                synchronize.run();
            }
        });
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (typeId <= 0 || current <= 0 || current > Integer.MAX_VALUE / PAGE_SIZE) {
            return Result.fail("分类和页码必须为有效正整数");
        }
        if (x == null || y == null) {
            Page<Shop> page = query().eq("type_id", typeId)
                    .orderByAsc("id").page(new Page<>(current, PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < -180 || x > 180
                || y < -85.05112878 || y > 85.05112878) {
            return Result.fail("经纬度超出有效范围");
        }
        int from = (current - 1) * PAGE_SIZE;
        int end = current * PAGE_SIZE;
        // GEORADIUS 支持 Redis 3.2+，兼容本机 Redis 5；返回 5km 内按距离排序的数据。
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().radius(
                ShopGeoIndex.KEY_PREFIX + typeId,
                new Circle(new Point(x, y), new Distance(5000, RedisGeoCommands.DistanceUnit.METERS)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance().sortAscending().limit(end));
        if (results == null || results.getContent().size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>();
        Map<Long, Double> distances = new HashMap<>();
        results.getContent().stream().skip(from).forEach(result -> {
            Long id = Long.valueOf(result.getContent().getName());
            ids.add(id);
            distances.put(id, result.getDistance().getValue());
        });
        // 数据库返回顺序不固定，按 GEO 顺序组装，避免依赖跨库 FIELD 函数。
        Map<Long, Shop> records = new HashMap<>();
        query().in("id", ids).eq("type_id", typeId).list().forEach(shop -> records.put(shop.getId(), shop));
        List<Shop> shops = new ArrayList<>();
        for (Long id : ids) {
            Shop shop = records.get(id);
            if (shop != null) {
                shop.setDistance(distances.get(id));
                shops.add(shop);
            }
        }
        return Result.ok(shops);
    }
}
