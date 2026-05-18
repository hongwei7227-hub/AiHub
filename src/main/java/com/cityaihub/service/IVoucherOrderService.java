package com.cityaihub.service;

import com.cityaihub.dto.Result;
import com.cityaihub.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author bany zhao
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result buyVoucher(Long voucherId);

    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    void closeTimeoutOrder(Long orderId);
}