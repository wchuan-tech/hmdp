package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static cn.hutool.json.JSONUtil.toJsonStr;
import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit){
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    // 解决缓存穿透问题
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从Redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(json)){
            // 3.存在，返回
            return JSONUtil.toBean(json,type);
        }

        // 如果key存在且为null 此时缓存在redis中的json == "" 不需要再去数据库中寻找 避免了缓存穿透问题
        if(json != null){
            // 返回一个错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.数据库不存在，返回错误
        if(r == null){
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入Redis
        this.set(key,r,time,unit);
        // 7.返回
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,
                                           Long time,TimeUnit unit){
        String key = CACHE_SHOP_KEY + id;
        // 1.从Redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isBlank(json)){
            // 3.不存在，返回
            return null;
        }
        // 4.命中, 需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            // 5.1 未过期 直接返回店铺信息
            return r;
        }

        // 5.2 已过期 需要缓存重建
        // 6. 缓存重建
        // 6.1获取互斥锁
        String localKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(localKey);
        // 6.2 判断是否获取锁成功
        if(isLock){
            // 6.3 成功 开启独立线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    // 重建缓存
                    // 1.查询数据库
                    R r1 = dbFallback.apply(id);
                    // 2.写入Redis
                    this.setWithLogicalExpire(key, r1,time,unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(localKey);
                }
            });
        }


        // 6.4 返回过期商铺信息
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }


    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
