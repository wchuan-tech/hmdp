package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.判断是否需要拦截 Thread Local中是否存在用户
        if(UserHolder.getUser() == null){
            response.setStatus(401);
            return false;
        }

        // 2.如果存在，保存用户信息，放行
        return true;
    }
}
