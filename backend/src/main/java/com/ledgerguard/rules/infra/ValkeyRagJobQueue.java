package com.ledgerguard.rules.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Valkey(Redis 호환) List 기반 색인 작업 큐. */
@Component
public class ValkeyRagJobQueue implements RagJobQueue {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String key;

    public ValkeyRagJobQueue(StringRedisTemplate redis, ObjectMapper objectMapper,
                             @Value("${app.queue.rag-key}") String key) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.key = key;
    }

    @Override
    public void enqueue(RagJob job) {
        try {
            redis.opsForList().leftPush(key, objectMapper.writeValueAsString(job));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            throw new QueueException("색인 작업 큐(Valkey)에 연결할 수 없습니다.", e);
        }
    }
}
