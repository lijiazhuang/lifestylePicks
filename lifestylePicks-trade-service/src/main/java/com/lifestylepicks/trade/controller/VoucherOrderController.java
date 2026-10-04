package com.lifestylepicks.trade.controller;
import com.lifestylepicks.common.dto.Result;
import com.lifestylepicks.trade.service.OrderService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    private final OrderService service;
    public VoucherOrderController(OrderService service){this.service=service;}
    @PostMapping("/seckill/{id}") public Result seckill(@PathVariable Long id){return service.seckill(id);}
    @GetMapping("/{id}") public Result status(@PathVariable Long id){return service.status(id);}
}
