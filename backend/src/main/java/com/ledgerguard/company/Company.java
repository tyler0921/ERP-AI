package com.ledgerguard.company;

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

@Entity
@Table(name = "companies")
@Getter
@Setter
@NoArgsConstructor
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "business_no", length = 20)
    private String businessNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Company(String name, String businessNo) {
        this.name = name;
        this.businessNo = businessNo;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
