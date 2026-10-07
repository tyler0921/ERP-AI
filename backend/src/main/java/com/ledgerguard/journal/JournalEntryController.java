package com.ledgerguard.journal;

import com.ledgerguard.common.PageResponse;
import com.ledgerguard.journal.JournalDtos.JournalEntryRequest;
import com.ledgerguard.journal.JournalDtos.JournalEntryResponse;
import com.ledgerguard.journal.JournalDtos.JournalEntrySummary;
import com.ledgerguard.journal.JournalDtos.VoidRequest;
import com.ledgerguard.security.AuthUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/journal-entries")
public class JournalEntryController {

    private final JournalEntryService service;

    public JournalEntryController(JournalEntryService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JournalEntryResponse create(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                       @Valid @RequestBody JournalEntryRequest request) {
        return service.create(user.userId(), companyId, request);
    }

    @GetMapping
    public PageResponse<JournalEntrySummary> list(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) EntryStatus status,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long transactionId,
            @PageableDefault(size = 20, sort = "entryDate", direction = Sort.Direction.DESC) Pageable pageable) {
        JournalEntryService.Filter filter =
                new JournalEntryService.Filter(from, to, status, accountId, transactionId);
        return PageResponse.from(service.list(user.userId(), companyId, filter, pageable));
    }

    @GetMapping("/{id}")
    public JournalEntryResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                    @PathVariable Long id) {
        return service.get(user.userId(), companyId, id);
    }

    @PutMapping("/{id}")
    public JournalEntryResponse update(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                       @PathVariable Long id, @Valid @RequestBody JournalEntryRequest request) {
        return service.update(user.userId(), companyId, id, request);
    }

    @PostMapping("/{id}/void")
    public JournalEntryResponse voidEntry(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                          @PathVariable Long id, @Valid @RequestBody VoidRequest request) {
        return service.voidEntry(user.userId(), companyId, id, request);
    }
}
