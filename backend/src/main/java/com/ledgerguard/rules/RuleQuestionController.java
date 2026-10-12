package com.ledgerguard.rules;

import com.ledgerguard.common.PageResponse;
import com.ledgerguard.explanation.ExplanationDtos.EditRequest;
import com.ledgerguard.rules.RuleDtos.AskRequest;
import com.ledgerguard.rules.RuleDtos.QuestionSummary;
import com.ledgerguard.rules.RuleDtos.QuestionView;
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
@RequestMapping("/api/v1/companies/{companyId}/rules/questions")
public class RuleQuestionController {

    private final RuleQuestionService service;

    public RuleQuestionController(RuleQuestionService service) {
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
