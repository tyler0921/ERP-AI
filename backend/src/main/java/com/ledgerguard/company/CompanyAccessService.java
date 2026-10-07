package com.ledgerguard.company;

import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 회사 단위 접근 제어의 단일 진입점.
 * 모든 /companies/{companyId}/** 처리는 가장 먼저 이 서비스로 (사용자, 회사) 멤버십과 Role을 확인한다.
 * 멤버가 아니면 회사의 존재 여부를 숨기기 위해 404(COMPANY_ACCESS_DENIED)를 반환한다.
 */
@Service
public class CompanyAccessService {

    private final UserCompanyRoleRepository roles;

    public CompanyAccessService(UserCompanyRoleRepository roles) {
        this.roles = roles;
    }

    /** 조회 권한: ADMIN, ACCOUNTANT, VIEWER 모두 */
    public Role requireMember(Long userId, Long companyId) {
        return roles.findByIdUserIdAndIdCompanyId(userId, companyId)
                .map(UserCompanyRole::getRole)
                .orElseThrow(() -> new ApiException(ErrorCode.COMPANY_ACCESS_DENIED));
    }

    /** 쓰기 권한: ADMIN, ACCOUNTANT */
    public Role requireWrite(Long userId, Long companyId) {
        Role role = requireMember(userId, companyId);
        if (!role.canWrite()) {
            throw new ApiException(ErrorCode.ROLE_FORBIDDEN);
        }
        return role;
    }

    /** 관리 권한: ADMIN */
    public Role requireAdmin(Long userId, Long companyId) {
        Role role = requireMember(userId, companyId);
        if (role != Role.ADMIN) {
            throw new ApiException(ErrorCode.ROLE_FORBIDDEN);
        }
        return role;
    }
}
