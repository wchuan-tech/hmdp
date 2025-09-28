package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            // 2.如果不符合 返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4.保存验证码->redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}",code);
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号和验证码
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式错误！");
        }

        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();

        if (cacheCode == null || !cacheCode.equals(code)){
            // 2.不一致 报错
            return Result.fail("验证码错误！");
        }

        // TODO 3.从redis中获取用户信息
        User user = query().eq("phone", phone).one();

        // 4.判断用户是否存在
        if(user == null){
            // 5.不存在，创建用户并保存
            user = createUserWithPhone(phone);
        }

        // TODO 6.保存用户信息到redis中
        // TODO 6.1 生成token
        String token = UUID.randomUUID().toString(true);
        // 6.2 将User对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, String> userMap = new HashMap<>();
        // 注意这里，要用 String.valueOf() 避免 Long 类型在某些工具中转换问题
        userMap.put("id", String.valueOf(userDTO.getId()));
        userMap.put("nickName", userDTO.getNickName());
        userMap.put("icon", userDTO.getIcon());


        // 6.3存储
        String tokenKey = LOGIN_USER_KEY + token;

        // --- 添加关键日志 ---
        log.info("准备向Redis写入用户信息...");
        log.info("生成的Token: {}", token);
        log.info("生成的Redis Key: {}", tokenKey);
        log.info("准备写入的User Map: {}", userMap);

        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        // 6.4 设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        // 7.返回token
        log.info("用户信息写入Redis成功！");
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis setbit key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 5.获取本月截止今天为止的所有签到记录 返回的是一个十进制数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()){
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0){
            return Result.ok(0);
        }
        int count = 0;
        // 6.循环遍历
        while(true){
            // 6.1.让这个数字跟1做与运算 得到数字的最后一个bit位
            if ((num & 1) == 0){
                // 如果是0， 说明未签到 结束
                break;
            }else {
                // 如果不是0，说明已签到，计时器+1
                count++;
            }
            // 把数字右移一位 抛弃最后一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }

}
