package com.lifestylepicks.trade.controller;
import com.lifestylepicks.common.dto.Result;
import com.lifestylepicks.trade.entity.Voucher;
import com.lifestylepicks.trade.service.VoucherService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/voucher")
public class VoucherController {
    private final VoucherService service;
    public VoucherController(VoucherService service){this.service=service;}
    @GetMapping("/list/{shopId}") public Result list(@PathVariable Long shopId){return service.list(shopId);}
    @PostMapping public Result create(@RequestBody Voucher voucher){return service.create(voucher,false);}
    @PostMapping("/seckill") public Result flash(@RequestBody Voucher voucher){return service.create(voucher,true);}
}
