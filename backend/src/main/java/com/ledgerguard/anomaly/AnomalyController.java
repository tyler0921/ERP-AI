package com.ledgerguard.anomaly;

import com.ledgerguard.anomaly.AnomalyDtos.ReopenRequest;
import com.ledgerguard.explanation.ExplanationDtos.EditRequest;
import com.ledgerguard.anomaly.AnomalyDtos.ResultListItem;
import com.ledgerguard.anomaly.AnomalyDtos.ResultResponse;
import com.ledgerguard.anomaly.AnomalyDtos.ReviewRequest;
import com.ledgerguard.anomaly.AnomalyDtos.ScanRequest;
import com.ledgerguard.anomaly.AnomalyDtos.ScanResponse;
import com.ledgerguard.anomaly.AnomalyDtos.SettingsRequest;
import com.ledgerguard.anomaly.AnomalyDtos.SettingsResponse;
import com.ledgerguard.anomaly.AnomalyDtos.SummaryResponse;
import com.ledgerguard.anomaly.AnomalyEnums.ResultStatus;
import com.ledgerguard.anomaly.AnomalyEnums.RiskLevel;
import com.ledgerguard.anomaly.AnomalyEnums.SourceType;
import com.ledgerguard.common.PageResponse;
import com.ledgerguard.security.AuthUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/v1/companies/{companyId}")
public class AnomalyController {

    private final AnomalyService service;

    public AnomalyController(AnomalyService service) {
        this.service = service;
    }

    @GetMapping("/anomaly-settings")
    public SettingsResponse getSettings(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId) {
        return service.getSettings(user.userId(), companyId);
    }

    @PutMapping("/anomaly-settings")
    public SettingsResponse updateSettings(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                           @Valid @RequestBody SettingsRequest request) {
        return service.updateSettings(user.userId(), companyId, request);
    }

    @PostMapping("/anomalies/scan")
    public ScanResponse scan(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                             @Valid @RequestBody ScanRequest request) {
        return service.scan(user.userId(), companyId, request);
    }

    @GetMapping("/anomalies/scans")
    public List<ScanResponse> scans(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId) {
        return service.scans(user.userId(), companyId);
    }

    @GetMapping("/anomalies/summary")
    public SummaryResponse summary(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId) {
        return service.summary(user.userId(), companyId);
    }

    @GetMapping("/anomalies")
    public PageResponse<ResultListItem> list(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable Long companyId,
            @RequestParam(required = false) ResultStatus status,
            @RequestParam(required = false) RiskLevel level,
            @RequestParam(required = false) SourceType sourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20)
            @SortDefault.SortDefaults({
                    @SortDefault(sort = "riskScore", direction = Sort.Direction.DESC),
                    @SortDefault(sort = "txDate", direction = Sort.Direction.DESC),
                    @SortDefault(sort = "id", direction = Sort.Direction.DESC)
            }) Pageable pageable) {
        AnomalyService.Filter filter = new AnomalyService.Filter(status, level, sourceType, from, to);
        return PageResponse.from(service.list(user.userId(), companyId, filter, pageable));
    }

    @GetMapping("/anomalies/{id}")
    public ResultResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                              @PathVariable Long id) {
        return service.get(user.userId(), companyId, id);
    }

    @PostMapping("/anomalies/{id}/review")
    public ResultResponse review(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                 @PathVariable Long id, @Valid @RequestBody ReviewRequest request) {
        return service.review(user.userId(), companyId, id, request);
    }

    @PutMapping("/anomalies/{id}/explanation")
    public ResultResponse editExplanation(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                          @PathVariable Long id, @Valid @RequestBody EditRequest request) {
        return service.editExplanation(user.userId(), companyId, id, request);
    }

    @PostMapping("/anomalies/{id}/explanation/regenerate")
    public ResultResponse regenerateExplanation(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                                @PathVariable Long id) {
        return service.regenerateExplanation(user.userId(), companyId, id);
    }

    @PostMapping("/anomalies/scans/{scanId}/summary/regenerate")
    public ScanResponse regenerateScanSummary(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                              @PathVariable Long scanId) {
        return service.regenerateScanSummary(user.userId(), companyId, scanId);
    }

    @PostMapping("/anomalies/{id}/reopen")
    public ResultResponse reopen(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                 @PathVariable Long id, @Valid @RequestBody ReopenRequest request) {
        return service.reopen(user.userId(), companyId, id, request.note());
    }
}
