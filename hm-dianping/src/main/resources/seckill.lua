---@diagnostic disable: undefined-global
---
--- Created by Gbmm.
--- DateTime: 2025/9/22 11:07
---
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2用户id
local userId = ARGV[2]
-- 1.3.订单Id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1 判断库存是否充足
local stock = redis.call('get', stockKey)
if(not stock or tonumber(stock) <= 0) then
    -- 3.2 库存不足 返回1
    return 1
end

-- 3.3 判断用户是否下单
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.4 存在说明重复下单
    return 2
end

-- 3.5 扣减库存
redis.call('decr', stockKey)

-- 3.6 下单 保存用户
redis.call('sadd', orderKey, userId)

-- 3.6发送消息到队列中 xadd stream.orders * k1 v1 k2 v2 ....
redis.call("xadd","stream.orders","*",'userId',userId,'voucherId',voucherId,'id',orderId)
return 0
