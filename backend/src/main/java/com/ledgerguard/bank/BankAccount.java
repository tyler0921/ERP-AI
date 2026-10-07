package com.ledgerguard.bank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 회사의 은행 계좌. 전표의 은행 금액은 glAccountId(연결 계정과목) 라인으로 계산한다. */
@Entity
@Table(name = "bank_accounts")
@Getter
@Setter
@NoArgsConstructor
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "bank_name", length = 50)
    private String bankName;

    /** 개인정보 최소화를 위해 계좌번호는 끝 4자리만 저장한다. */
    @Column(name = "account_no_last4", length = 4)
    private String accountNoLast4;

    @Column(name = "gl_account_id", nullable = false, updatable = false)
    private Long glAccountId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
