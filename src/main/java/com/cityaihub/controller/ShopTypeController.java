package com.cityaihub.controller;


import com.cityaihub.dto.Result;
import com.cityaihub.entity.ShopType;
import com.cityaihub.service.IShopTypeService;
import com.cityaihub.utils.SlidingWindowLimit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author bany zhao
 */
@RestController
@Slf4j
@RequestMapping("/shop-type")
@SlidingWindowLimit(globalLimit = true)
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
        log.info("查询店铺清单");
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        return Result.ok(typeList);
    }
}
