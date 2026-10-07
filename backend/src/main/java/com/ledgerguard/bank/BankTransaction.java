package com.ledgerguard.bank;

import com.ledgerguard.transaction.Direction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 은행에서 받은 원천 거래. 업로드 후에는 대사 상태(status) 외에는 변경하지 않는다. */
@Entity
@Table(name = "bank_transactions")
@Getter
@Setter
@NoArgsConstructor
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "bank_account_id", nullable = false, updatable = false)
    private Long bankAccountId;

    @Column(name = "import_id", nullable = false, updatable = false)
    private Long importId;

    @Column(name = "row_no", nullable = false, updatable = false)
    private int rowNo;

    @Column(name = "tx_date", nullable = false, updatable = false)
    private LocalDate txDate;

    @Column(name = "tx_time", updatable = false)
    private LocalTime txTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private Direction direction;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(precision = 19, scale = 2, updatable = false)
    private BigDecimal balance;

    @Column(length = 200, updatable = false)
    private String counterparty;

    @Column(length = 500, updatable = false)
    private String memo;

    @Column(name = "dedupe_key", nullable = false, length = 64, updatable = false)
    private String dedupeKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BankTxStatus status = BankTxStatus.UNMATCHED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
