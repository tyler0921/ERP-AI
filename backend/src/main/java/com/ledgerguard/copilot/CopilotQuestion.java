package com.ledgerguard.copilot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** AI Copilot 질문 한 건: 질문 → 검증된 계획 → Query Tool 결과. 답변은 ai_explanations(COPILOT_ANSWER)에 있다. */
@Entity
@Table(name = "copilot_questions")
@Getter
@Setter
@NoArgsConstructor
public class CopilotQuestion {

    public enum Status {
        /** LLM Planner 응답 대기 */
        PLANNING,
        /** 조회 완료 (답변은 별도로 생성) */
        ANSWERED,
        /** 지원하지 않는 질문 (조회하지 않음) */
        UNSUPPORTED,
        /** 해석·조회 실패. 다시 시도하거나 해석을 고쳐 조회할 수 있다 */
        FAILED
    }

    public enum PlanSource {
        LLM,
        RULE,
        USER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "asked_by", nullable = false, updatable = false)
    private Long askedBy;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PLANNING;

    @Column(nullable = false)
    private int attempt = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_source", length = 10)
    private PlanSource planSource;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_meta", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> planMeta = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "planned_at")
    private Instant plannedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
