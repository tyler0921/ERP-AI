package com.ledgerguard.company;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_company_roles")
@Getter
@Setter
@NoArgsConstructor
public class UserCompanyRole {

    @EmbeddedId
    private UserCompanyRoleId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    public UserCompanyRole(Long userId, Long companyId, Role role) {
        this.id = new UserCompanyRoleId(userId, companyId);
        this.role = role;
    }
}
