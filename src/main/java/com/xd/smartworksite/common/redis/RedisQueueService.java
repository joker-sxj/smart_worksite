package com.xd.smartworksite.common.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RedisQueueService {

    private final StringRedisTemplate redisTemplate;

    public RedisQueueService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void push(String queueKey, String payload) {
        redisTemplate.opsForList().rightPush(queueKey, payload);
    }

    public String pop(String queueKey, Duration timeout) {
        return redisTemplate.opsForList().leftPop(queueKey, timeout);
    }
}
