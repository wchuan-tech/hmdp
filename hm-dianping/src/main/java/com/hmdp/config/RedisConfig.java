package com.hmdp.config;

import org.redisson.Redisson;

import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient(){
        // 配置类
        Config config = new Config();
        // 添加redis地址
        config.useSingleServer().setAddress("redis://192.168.88.128:6379").setPassword("123321");
        // 创建客户端
        return Redisson.create( config);
    }
//    @Bean
//    public RedissonClient redissonClient2(){
//        // 配置类
//        Config config = new Config();
//        // 添加redis地址
//        config.useSingleServer().setAddress("redis://192.168.88.128:6380");
//        // 创建客户端
//        return Redisson.create( config);
//    }
//    @Bean
//    public RedissonClient redissonClient3(){
//        // 配置类
//        Config config = new Config();
//        // 添加redis地址
//        config.useSingleServer().setAddress("redis://192.168.88.128:63781");
//        // 创建客户端
//        return Redisson.create( config);
//    }
}
