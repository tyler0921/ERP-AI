package com.ledgerguard.user;

import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.company.Company;
import com.ledgerguard.company.CompanyRepository;
import com.ledgerguard.company.UserCompanyRole;
import com.ledgerguard.company.UserCompanyRoleRepository;
import com.ledgerguard.security.AuthUser;
import com.ledgerguard.user.AuthDtos.MeResponse;
import com.ledgerguard.user.AuthDtos.MembershipResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    private final UserRepository users;
    private final UserCompanyRoleRepository roles;
    private final CompanyRepository companies;

    public MeController(UserRepository users, UserCompanyRoleRepository roles, CompanyRepository companies) {
        this.users = users;
        this.roles = roles;
        this.companies = companies;
    }

    @GetMapping("/api/v1/me")
    @Transactional(readOnly = true)
    public MeResponse me(@AuthenticationPrincipal AuthUser authUser) {
        User user = users.findById(authUser.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_INVALID_TOKEN));
        List<UserCompanyRole> memberships = roles.findByIdUserId(user.getId());
        Map<Long, Company> companyById = companies
                .findAllById(memberships.stream().map(m -> m.getId().getCompanyId()).toList())
                .stream().collect(Collectors.toMap(Company::getId, Function.identity()));
        List<MembershipResponse> result = memberships.stream()
                .map(m -> new MembershipResponse(m.getId().getCompanyId(),
                        companyById.get(m.getId().getCompanyId()).getName(), m.getRole()))
                .toList();
        return new MeResponse(user.getId(), user.getEmail(), user.getName(), result);
    }
}
