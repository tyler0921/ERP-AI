package com.ledgerguard.company;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class CompanyDtos {

    private CompanyDtos() {
    }

    private static final String BUSINESS_NO = "^\\d{3}-?\\d{2}-?\\d{5}$";

    public record CreateCompanyRequest(
            @NotBlank @Size(max = 200) String name,
            @Pattern(regexp = BUSINESS_NO, message = "사업자등록번호 형식이 올바르지 않습니다.") String businessNo) {
    }

    /** null인 필드는 변경하지 않는다. */
    public record UpdateCompanyRequest(
            @Size(min = 1, max = 200) String name,
            @Pattern(regexp = BUSINESS_NO, message = "사업자등록번호 형식이 올바르지 않습니다.") String businessNo) {
    }

    public record CompanyResponse(Long id, String name, String businessNo, Role role, Instant createdAt) {
        public static CompanyResponse from(Company company, Role role) {
            return new CompanyResponse(company.getId(), company.getName(), company.getBusinessNo(), role,
                    company.getCreatedAt());
        }
    }

    public record AddMemberRequest(
            @NotBlank @Email String email,
            @NotNull Role role) {
    }

    public record ChangeRoleRequest(@NotNull Role role) {
    }

    public record MemberResponse(Long userId, String email, String name, Role role) {
    }
}
