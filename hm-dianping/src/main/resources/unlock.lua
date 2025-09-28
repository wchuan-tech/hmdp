---@diagnostic disable: undefined-global
---
--- Created by Gbmm.
--- DateTime: 2025/9/20 15:14
---
if(redis.call('get', KEYS[1]) == ARGV[1]) then
    return redis.call('del',KEYS[1])
end
return 0