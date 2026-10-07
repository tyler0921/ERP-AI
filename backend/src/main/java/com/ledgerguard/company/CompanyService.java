package com.ledgerguard.company;

import com.ledgerguard.account.AccountService;
import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.company.CompanyDtos.CompanyResponse;
import com.ledgerguard.company.CompanyDtos.CreateCompanyRequest;
import com.ledgerguard.company.CompanyDtos.UpdateCompanyRequest;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyService {

    private final CompanyRepository companies;
    private final UserCompanyRoleRepository roles;
    private final CompanyAccessService access;
    private final AccountService accountService;
    private final AuditService audit;

    public CompanyService(CompanyRepository companies, UserCompanyRoleRepository roles,
                          CompanyAccessService access, AccountService accountService, AuditService audit) {
        this.companies = companies;
        this.roles = roles;
        this.access = access;
        this.accountService = accountService;
        this.audit = audit;
    }

    /** 회사를 만들고 생성자를 ADMIN으로 등록하며 기본 계정과목을 채운다. 모두 한 트랜잭션이다. */
    @Transactional
    public CompanyResponse create(Long userId, CreateCompanyRequest req) {
        Company company = companies.save(new Company(req.name().trim(), req.businessNo()));
        roles.save(new UserCompanyRole(userId, company.getId(), Role.ADMIN));
        accountService.seedDefaults(company.getId());

        CompanyResponse response = CompanyResponse.from(company, Role.ADMIN);
        audit.record(company.getId(), userId, AuditActions.COMPANY_CREATE, AuditActions.TARGET_COMPANY,
                company.getId(), null, response);
        return response;
    }

    @Transactional(readOnly = true)
    public List<CompanyResponse> list(Long userId) {
        List<UserCompanyRole> memberships = roles.findByIdUserId(userId);
        Map<Long, Company> byId = companies
                .findAllById(memberships.stream().map(m -> m.getId().getCompanyId()).toList())
                .stream().collect(Collectors.toMap(Company::getId, Function.identity()));
        return memberships.stream()
                .map(m -> CompanyResponse.from(byId.get(m.getId().getCompanyId()), m.getRole()))
                .sorted((a, b) -> Long.compare(a.id(), b.id()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CompanyResponse get(Long userId, Long companyId) {
        Role role = access.requireMember(userId, companyId);
        return CompanyResponse.from(find(companyId), role);
    }

    @Transactional
    public CompanyResponse update(Long userId, Long companyId, UpdateCompanyRequest req) {
        Role role = access.requireAdmin(userId, companyId);
        Company company = find(companyId);
        CompanyResponse before = CompanyResponse.from(company, role);

        if (req.name() != null) {
            company.setName(req.name().trim());
        }
        if (req.businessNo() != null) {
            company.setBusinessNo(req.businessNo());
        }

        CompanyResponse after = CompanyResponse.from(company, role);
        audit.record(companyId, userId, AuditActions.COMPANY_UPDATE, AuditActions.TARGET_COMPANY, companyId,
                before, after);
        return after;
    }

    private Company find(Long companyId) {
        return companies.findById(companyId).orElseThrow(() -> new ApiException(ErrorCode.COMPANY_ACCESS_DENIED));
    }
}
