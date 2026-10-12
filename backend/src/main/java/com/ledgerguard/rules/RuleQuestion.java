package com.ledgerguard.rules;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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

/** 규정 질문 한 건. 검색 결과(sources)는 질문 시점의 스냅샷이다. 답변은 ai_explanations(RULE_ANSWER). */
@Entity
@Table(name = "rule_questions")
@Getter
@Setter
@NoArgsConstructor
public class RuleQuestion {

    public enum Status {
        /** 관련 발췌를 찾아 답변을 요청함 */
        ANSWERED,
        /** 관련도 기준을 넘는 발췌가 없음 (LLM을 부르지 않음) */
        NO_MATCH
    }

    public enum RetrievalMode {
        VECTOR,
        /** 임베딩 서비스 장애 시 키워드 검색 */
        KEYWORD
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
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "retrieval_mode", nullable = false, length = 10)
    private RetrievalMode retrievalMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieval_meta", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> retrievalMeta = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> sources = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
