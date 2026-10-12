package com.ledgerguard.copilot;

import com.ledgerguard.common.PageResponse;
import com.ledgerguard.copilot.CopilotDtos.AskRequest;
import com.ledgerguard.copilot.CopilotDtos.PlanRequest;
import com.ledgerguard.copilot.CopilotDtos.QuestionSummary;
import com.ledgerguard.copilot.CopilotDtos.QuestionView;
import com.ledgerguard.explanation.ExplanationDtos.EditRequest;
import com.ledgerguard.security.AuthUser;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/companies/{companyId}/copilot/questions")
public class CopilotController {

    private final CopilotService service;

    public CopilotController(CopilotService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionView ask(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                            @Valid @RequestBody AskRequest request) {
        return service.ask(user.userId(), companyId, request.question());
    }

    @GetMapping
    public PageResponse<QuestionSummary> list(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                              @RequestParam(defaultValue = "0") int page) {
        return service.list(user.userId(), companyId, page);
    }

    @GetMapping("/{id}")
    public QuestionView get(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId, @PathVariable Long id) {
        return service.get(user.userId(), companyId, id);
    }

    @PostMapping("/{id}/retry")
    public QuestionView retry(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId, @PathVariable Long id) {
        return service.retry(user.userId(), companyId, id);
    }

    @PutMapping("/{id}/plan")
    public QuestionView editPlan(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                 @PathVariable Long id, @Valid @RequestBody PlanRequest request) {
        return service.editPlan(user.userId(), companyId, id, request.plan());
    }

    @PutMapping("/{id}/answer")
    public QuestionView editAnswer(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                   @PathVariable Long id, @Valid @RequestBody EditRequest request) {
        return service.editAnswer(user.userId(), companyId, id, request);
    }

    @PostMapping("/{id}/answer/regenerate")
    public QuestionView regenerateAnswer(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                         @PathVariable Long id) {
        return service.regenerateAnswer(user.userId(), companyId, id);
    }
}
