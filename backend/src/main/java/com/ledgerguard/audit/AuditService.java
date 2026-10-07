package com.ledgerguard.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 변경 이력을 남긴다. 호출한 쓰기 작업의 트랜잭션에 참여하므로 변경과 이력이 함께 커밋/롤백된다.
     * 트랜잭션이 없는 호출(로그인 등)은 자체 트랜잭션으로 저장된다.
     *
     * @param before 변경 전 값(DTO/Map). 없으면 null
     * @param after  변경 후 값(DTO/Map). 없으면 null
     */
    @Transactional
    public void record(Long companyId, Long actorId, String action, String targetType, Long targetId,
                       Object before, Object after) {
        repository.save(new AuditLog(companyId, actorId, action, targetType, targetId,
                snapshot(before), snapshot(after)));
    }

    private Map<String, Object> snapshot(Object value) {
        if (value == null) {
            return null;
        }
        return objectMapper.convertValue(value, MAP_TYPE);
    }
}
