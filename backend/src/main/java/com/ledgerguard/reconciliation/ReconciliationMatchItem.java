package com.ledgerguard.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 매칭 참여자: 은행 거래 또는 전표 중 하나와 그 금액. */
@Entity
@Table(name = "reconciliation_match_items")
@Getter
@Setter
@NoArgsConstructor
public class ReconciliationMatchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_id", nullable = false, updatable = false)
    private ReconciliationMatch match;

    @Column(name = "bank_transaction_id", updatable = false)
    private Long bankTransactionId;

    @Column(name = "journal_entry_id", updatable = false)
    private Long journalEntryId;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private boolean confirmed;

    public static ReconciliationMatchItem bank(ReconciliationMatch match, Long bankTransactionId, BigDecimal amount) {
        ReconciliationMatchItem item = new ReconciliationMatchItem();
        item.match = match;
        item.bankTransactionId = bankTransactionId;
        item.amount = amount;
        return item;
    }

    public static ReconciliationMatchItem journal(ReconciliationMatch match, Long journalEntryId, BigDecimal amount) {
        ReconciliationMatchItem item = new ReconciliationMatchItem();
        item.match = match;
        item.journalEntryId = journalEntryId;
        item.amount = amount;
        return item;
    }
}
