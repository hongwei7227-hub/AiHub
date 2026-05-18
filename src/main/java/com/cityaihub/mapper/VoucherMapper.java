package com.cityaihub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cityaihub.entity.Voucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author bany zhao
 * @since 2021-12-22
 */
public interface VoucherMapper extends BaseMapper<Voucher> {
    //添加针对优惠券表的查询
    @Select("SELECT id FROM tb_voucher LIMIT #{offset}, #{limit}")
    List<Long> selectAllIds(@Param("offset") int offset, @Param("limit") int limit);

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}
