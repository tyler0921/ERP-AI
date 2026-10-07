package com.ledgerguard.company;

import com.ledgerguard.company.CompanyDtos.CompanyResponse;
import com.ledgerguard.company.CompanyDtos.CreateCompanyRequest;
import com.ledgerguard.company.CompanyDtos.UpdateCompanyRequest;
import com.ledgerguard.security.AuthUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse create(@AuthenticationPrincipal AuthUser user,
                                  @Valid @RequestBody CreateCompanyRequest request) {
        return companyService.create(user.userId(), request);
    }

    @GetMapping
    public List<CompanyResponse> list(@AuthenticationPrincipal AuthUser user) {
        return companyService.list(user.userId());
    }

    @GetMapping("/{companyId}")
    public CompanyResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId) {
        return companyService.get(user.userId(), companyId);
    }

    @PatchMapping("/{companyId}")
    public CompanyResponse update(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                  @Valid @RequestBody UpdateCompanyRequest request) {
        return companyService.update(user.userId(), companyId, request);
    }
}
