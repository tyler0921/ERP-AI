package com.ledgerguard.reconciliation;

import com.ledgerguard.bank.BankDtos.BankTransactionResponse;
import com.ledgerguard.bank.BankTxStatus;
import com.ledgerguard.common.PageResponse;
import com.ledgerguard.reconciliation.ReconciliationDtos.CancelRequest;
import com.ledgerguard.reconciliation.ReconciliationDtos.CandidateResponse;
import com.ledgerguard.reconciliation.ReconciliationDtos.ManualMatchRequest;
import com.ledgerguard.reconciliation.ReconciliationDtos.MatchResponse;
import com.ledgerguard.reconciliation.ReconciliationDtos.ReviewRequest;
import com.ledgerguard.reconciliation.ReconciliationDtos.RunRequest;
import com.ledgerguard.reconciliation.ReconciliationDtos.RunResponse;
import com.ledgerguard.reconciliation.ReconciliationDtos.SummaryResponse;
import com.ledgerguard.security.AuthUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies/{companyId}")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @GetMapping("/bank-transactions")
    public PageResponse<BankTransactionResponse> bankTransactions(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable Long companyId,
            @RequestParam(required = false) Long bankAccountId,
            @RequestParam(required = false) BankTxStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20)
            @SortDefault.SortDefaults({
                    @SortDefault(sort = "txDate", direction = Sort.Direction.DESC),
                    @SortDefault(sort = "id", direction = Sort.Direction.DESC)
            }) Pageable pageable) {
        ReconciliationService.Filter filter = new ReconciliationService.Filter(bankAccountId, status, from, to);
        return PageResponse.from(service.listBankTransactions(user.userId(), companyId, filter, pageable));
    }

    @GetMapping("/bank-transactions/{id}/candidates")
    public List<CandidateResponse> candidates(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                              @PathVariable Long id) {
        return service.candidates(user.userId(), companyId, id);
    }

    @PostMapping("/reconciliation/run")
    public RunResponse run(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                           @Valid @RequestBody RunRequest request) {
        return service.run(user.userId(), companyId, request.bankAccountId());
    }

    @GetMapping("/reconciliation/summary")
    public SummaryResponse summary(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                   @RequestParam(required = false) Long bankAccountId) {
        return service.summary(user.userId(), companyId, bankAccountId);
    }

    @PostMapping("/reconciliation/matches")
    @ResponseStatus(HttpStatus.CREATED)
    public MatchResponse manualMatch(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                     @Valid @RequestBody ManualMatchRequest request) {
        return service.manualMatch(user.userId(), companyId, request);
    }

    @GetMapping("/reconciliation/matches/{id}")
    public MatchResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                             @PathVariable Long id) {
        return service.getMatch(user.userId(), companyId, id);
    }

    @PostMapping("/reconciliation/matches/{id}/confirm")
    public MatchResponse confirm(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                 @PathVariable Long id, @Valid @RequestBody(required = false) ReviewRequest request) {
        return service.confirm(user.userId(), companyId, id, request == null ? null : request.note());
    }

    @PostMapping("/reconciliation/matches/{id}/reject")
    public MatchResponse reject(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                @PathVariable Long id, @Valid @RequestBody(required = false) ReviewRequest request) {
        return service.reject(user.userId(), companyId, id, request == null ? null : request.note());
    }

    @PostMapping("/reconciliation/matches/{id}/cancel")
    public MatchResponse cancel(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                @PathVariable Long id, @Valid @RequestBody CancelRequest request) {
        return service.cancel(user.userId(), companyId, id, request.reason());
    }
}
