package com.ledgerguard.closing;

import com.ledgerguard.closing.ClosingDtos.NoteRequest;
import com.ledgerguard.closing.ClosingDtos.ReportResponse;
import com.ledgerguard.closing.ClosingDtos.TrendPoint;
import com.ledgerguard.explanation.ExplanationDtos.EditRequest;
import com.ledgerguard.security.AuthUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/closing")
public class ClosingController {

    private final ClosingService service;

    public ClosingController(ClosingService service) {
        this.service = service;
    }

    @GetMapping("/trend")
    public List<TrendPoint> trend(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(defaultValue = "12") int months) {
        return service.trend(user.userId(), companyId, to, months);
    }

    @GetMapping("/{month}")
    public ReportResponse report(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                 @PathVariable String month) {
        return service.report(user.userId(), companyId, month);
    }

    @PostMapping("/{month}/explanation")
    public ReportResponse explain(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                  @PathVariable String month, @RequestParam(defaultValue = "false") boolean force) {
        return service.explain(user.userId(), companyId, month, force);
    }

    @PutMapping("/{month}/explanation")
    public ReportResponse editExplanation(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                          @PathVariable String month, @Valid @RequestBody EditRequest request) {
        return service.editExplanation(user.userId(), companyId, month, request);
    }

    @PostMapping("/{month}/close")
    public ReportResponse close(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                @PathVariable String month, @Valid @RequestBody NoteRequest request) {
        return service.close(user.userId(), companyId, month, request.note());
    }

    @PostMapping("/{month}/reopen")
    public ReportResponse reopen(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                 @PathVariable String month, @Valid @RequestBody NoteRequest request) {
        return service.reopen(user.userId(), companyId, month, request.note());
    }
}
