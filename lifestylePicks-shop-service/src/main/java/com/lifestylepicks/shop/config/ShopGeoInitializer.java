package com.lifestylepicks.shop.config;

import com.lifestylepicks.shop.cache.ShopGeoIndex;
import com.lifestylepicks.shop.mapper.ShopMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 显式开启时补充已有店铺的 GEO 索引，不清空任何 Redis key。 */
@Component
@ConditionalOnProperty(prefix = "lifestylepicks.shop.geo", name = "initialize", havingValue = "true")
public class ShopGeoInitializer implements ApplicationRunner {
    private final ShopMapper mapper;
    private final ShopGeoIndex geo;

    public ShopGeoInitializer(ShopMapper mapper, ShopGeoIndex geo) {
        this.mapper = mapper;
        this.geo = geo;
    }

    @Override
    public void run(ApplicationArguments args) {
        mapper.selectList(null).forEach(geo::add);
    }
}
