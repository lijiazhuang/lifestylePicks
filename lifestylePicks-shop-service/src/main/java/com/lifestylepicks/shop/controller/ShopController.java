package com.lifestylepicks.shop.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.dto.Result;
import com.lifestylepicks.shop.entity.Shop;
import com.lifestylepicks.shop.service.IShopService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shop")
public class ShopController {
    private final IShopService service;

    public ShopController(IShopService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public Result queryById(@PathVariable Long id) {
        return service.queryById(id);
    }

    @PostMapping
    public ResponseEntity<Result> create(@RequestBody Shop shop) {
        if (UserContext.getUserId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail("请先登录"));
        }
        return ResponseEntity.ok(service.create(shop));
    }

    @PutMapping
    public ResponseEntity<Result> update(@RequestBody Shop shop) {
        if (UserContext.getUserId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail("请先登录"));
        }
        return ResponseEntity.ok(service.update(shop));
    }

    @GetMapping("/of/type")
    public Result queryByType(@RequestParam Integer typeId,
                              @RequestParam(defaultValue = "1") Integer current,
                              @RequestParam(required = false) Double x,
                              @RequestParam(required = false) Double y) {
        return service.queryShopByType(typeId, current, x, y);
    }

    @GetMapping("/of/name")
    public Result queryByName(@RequestParam(required = false) String name,
                              @RequestParam(defaultValue = "1") Integer current) {
        if (current <= 0) {
            return Result.fail("页码必须为正整数");
        }
        Page<Shop> page = service.query().like(StrUtil.isNotBlank(name), "name", name)
                .orderByAsc("id").page(new Page<>(current, 10));
        return Result.ok(page.getRecords());
    }
}
