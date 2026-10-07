package com.ledgerguard.company;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
public class UserCompanyRoleId implements Serializable {

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "company_id")
    private Long companyId;

    public UserCompanyRoleId(Long userId, Long companyId) {
        this.userId = userId;
        this.companyId = companyId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserCompanyRoleId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(companyId, that.companyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, companyId);
    }
}
