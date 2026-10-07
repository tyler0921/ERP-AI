package com.ledgerguard.document.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Valkey(Redis 호환) List 기반 작업 큐. */
@Component
public class ValkeyOcrJobQueue implements OcrJobQueue {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String key;

    public ValkeyOcrJobQueue(StringRedisTemplate redis, ObjectMapper objectMapper,
                             @Value("${app.queue.ocr-key}") String key) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.key = key;
    }

    @Override
    public void enqueue(OcrJob job) {
        try {
            redis.opsForList().leftPush(key, objectMapper.writeValueAsString(job));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            throw new QueueException("작업 큐(Valkey)에 연결할 수 없습니다.", e);
        }
    }
}
