package com.ledgerguard.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 추가만 가능한 변경 이력. DB 트리거가 UPDATE/DELETE를 막는다. */
@Entity
@Table(name = "audit_logs")
@Immutable
@Getter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "target_type", nullable = false, length = 50)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_value", columnDefinition = "jsonb")
    private Map<String, Object> beforeValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_value", columnDefinition = "jsonb")
    private Map<String, Object> afterValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AuditLog(Long companyId, Long actorId, String action, String targetType, Long targetId,
                    Map<String, Object> beforeValue, Map<String, Object> afterValue) {
        this.companyId = companyId;
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
        this.createdAt = Instant.now();
    }
}
