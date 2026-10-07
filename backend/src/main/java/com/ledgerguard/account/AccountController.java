package com.ledgerguard.account;

import com.ledgerguard.account.AccountDtos.AccountResponse;
import com.ledgerguard.account.AccountDtos.CreateAccountRequest;
import com.ledgerguard.account.AccountDtos.UpdateAccountRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                      @RequestParam(required = false) AccountType type,
                                      @RequestParam(required = false) Boolean active) {
        return accountService.list(user.userId(), companyId, type, active);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                  @Valid @RequestBody CreateAccountRequest request) {
        return accountService.create(user.userId(), companyId, request);
    }

    @PatchMapping("/{id}")
    public AccountResponse update(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                  @PathVariable Long id, @Valid @RequestBody UpdateAccountRequest request) {
        return accountService.update(user.userId(), companyId, id, request);
    }
}
