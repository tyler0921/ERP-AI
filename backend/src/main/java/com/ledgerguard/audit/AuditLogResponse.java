package com.ledgerguard.audit;

import java.time.Instant;
import java.util.Map;

public record AuditLogResponse(
        Long id,
        Long companyId,
        Long actorId,
        String action,
        String targetType,
        Long targetId,
        Map<String, Object> before,
        Map<String, Object> after,
        Instant createdAt) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getCompanyId(), log.getActorId(), log.getAction(),
                log.getTargetType(), log.getTargetId(), log.getBeforeValue(), log.getAfterValue(), log.getCreatedAt());
    }
}
