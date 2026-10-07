package com.ledgerguard.transaction;

import com.ledgerguard.common.PageResponse;
import com.ledgerguard.security.AuthUser;
import com.ledgerguard.transaction.TransactionDtos.CreateTransactionRequest;
import com.ledgerguard.transaction.TransactionDtos.TransactionResponse;
import com.ledgerguard.transaction.TransactionDtos.UpdateTransactionRequest;
import com.ledgerguard.transaction.TransactionDtos.VoidRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/v1/companies/{companyId}/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse create(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                      @Valid @RequestBody CreateTransactionRequest request) {
        return service.create(user.userId(), companyId, request);
    }

    @GetMapping
    public PageResponse<TransactionResponse> list(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) TxStatus status,
            @RequestParam(required = false) String counterparty,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "txDate", direction = Sort.Direction.DESC) Pageable pageable) {
        TransactionService.Filter filter =
                new TransactionService.Filter(from, to, direction, status, counterparty, q);
        return PageResponse.from(service.list(user.userId(), companyId, filter, pageable));
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                   @PathVariable Long id) {
        return service.get(user.userId(), companyId, id);
    }

    @PatchMapping("/{id}")
    public TransactionResponse update(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                      @PathVariable Long id, @Valid @RequestBody UpdateTransactionRequest request) {
        return service.update(user.userId(), companyId, id, request);
    }

    @PostMapping("/{id}/void")
    public TransactionResponse voidTransaction(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                               @PathVariable Long id, @Valid @RequestBody VoidRequest request) {
        return service.voidTransaction(user.userId(), companyId, id, request);
    }
}
