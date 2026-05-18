package com.cityaihub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cityaihub.dto.LoginFormDTO;
import com.cityaihub.dto.Result;
import com.cityaihub.entity.User;

import jakarta.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author bany zhao
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();

}
