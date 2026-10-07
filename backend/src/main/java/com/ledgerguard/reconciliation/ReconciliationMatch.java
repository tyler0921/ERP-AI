package com.ledgerguard.reconciliation;

import com.ledgerguard.reconciliation.MatchEnums.MatchSource;
import com.ledgerguard.reconciliation.MatchEnums.MatchStatus;
import com.ledgerguard.reconciliation.MatchEnums.MatchType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 은행 거래와 전표의 매칭(헤더). 참여자는 items로 표현해 1:N, N:1로 확장할 수 있다. */
@Entity
@Table(name = "reconciliation_matches")
@Getter
@Setter
@NoArgsConstructor
public class ReconciliationMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 20, updatable = false)
    private MatchType matchType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MatchStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private MatchSource source;

    @Column(nullable = false, updatable = false)
    private int score;

    /** 점수 근거 (항목별 점수, 날짜 차이, 유사도, 사람이 읽을 수 있는 사유) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "score_detail", columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> scoreDetail;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    @BatchSize(size = 50)
    private List<ReconciliationMatchItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public List<Long> journalEntryIds() {
        return items.stream().map(ReconciliationMatchItem::getJournalEntryId).filter(id -> id != null).toList();
    }

    public List<Long> bankTransactionIds() {
        return items.stream().map(ReconciliationMatchItem::getBankTransactionId).filter(id -> id != null).toList();
    }

    /** 확정 여부를 참여자 플래그에 반영한다. (부분 유니크 인덱스·균형 트리거의 기준) */
    public void markItemsConfirmed(boolean confirmed) {
        items.forEach(i -> i.setConfirmed(confirmed));
    }
}
