package com.cityaihub.service.impl;

import com.cityaihub.entity.UserInfo;
import com.cityaihub.mapper.UserInfoMapper;
import com.cityaihub.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author bany zhao
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
