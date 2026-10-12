package com.ledgerguard.copilot;

import com.ledgerguard.copilot.CopilotDtos.PlanCallback;
import com.ledgerguard.security.InternalTokenVerifier;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** LLM worker의 계획 콜백 (내부 토큰). 브라우저는 BFF를 통해 /api/v1만 호출하므로 닿을 수 없다. */
@RestController
@RequestMapping("/internal/v1/copilot/questions")
public class InternalCopilotController {

    private final CopilotService service;
    private final InternalTokenVerifier tokenVerifier;

    public InternalCopilotController(CopilotService service, InternalTokenVerifier tokenVerifier) {
        this.service = service;
        this.tokenVerifier = tokenVerifier;
    }

    @PostMapping("/{id}/plan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> plan(@RequestHeader(value = "X-Internal-Token", required = false) String header,
                                    @PathVariable Long id, @RequestBody PlanCallback callback) {
        tokenVerifier.verify(header);
        return Map.of("status", service.applyPlan(id, callback) ? "applied" : "ignored");
    }
}
