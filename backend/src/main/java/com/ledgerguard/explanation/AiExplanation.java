package com.ledgerguard.explanation;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** LLM이 만든 설명. LLM에 준 입력(facts)과 생성 정보(meta)를 함께 저장한다. */
@Entity
@Table(name = "ai_explanations")
@Getter
@Setter
@NoArgsConstructor
public class AiExplanation {

    public enum TargetType {
        /** 이상거래 결과 한 건의 탐지 이유 */
        ANOMALY_RESULT,
        /** 이상거래 분석 한 번의 요약 */
        ANOMALY_SCAN,
        /** 월마감 보고서 (Phase 6) */
        CLOSING_MONTH
    }

    public enum Status {
        /** LLM 작업 대기·처리 중 */
        PENDING,
        /** LLM 설명이 검증을 통과 */
        GENERATED,
        /** LLM 실패 또는 검증 실패 — 규칙 근거로 만든 템플릿 설명 */
        FALLBACK,
        /** 큐 장애·시간 초과. 다시 생성할 수 있다 */
        FAILED,
        /** 사람이 수정한 설명 */
        EDITED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "facts_hash", nullable = false, length = 64)
    private String factsHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> facts;

    @Column(columnDefinition = "text")
    private String text;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> checkpoints = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> meta = new LinkedHashMap<>();

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "edited_by")
    private Long editedBy;

    @Column(name = "edited_at")
    private Instant editedAt;

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
