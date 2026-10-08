package com.ledgerguard.document;

import com.ledgerguard.document.DocumentDtos.AiResultCallback;
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

/**
 * AI 서비스 전용 내부 API. 사용자 JWT 대신 공유 토큰(X-Internal-Token)으로 인증한다.
 * Next.js BFF는 /api/v1만 프록시하므로 브라우저에서는 이 경로에 닿을 수 없다.
 */
@RestController
@RequestMapping("/internal/v1/documents")
public class InternalDocumentController {

    private final DocumentService service;
    private final InternalTokenVerifier tokenVerifier;

    public InternalDocumentController(DocumentService service, InternalTokenVerifier tokenVerifier) {
        this.service = service;
        this.tokenVerifier = tokenVerifier;
    }

    @PostMapping("/{id}/ai-result")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> aiResult(@RequestHeader(value = "X-Internal-Token", required = false) String header,
                                        @PathVariable Long id, @RequestBody AiResultCallback callback) {
        tokenVerifier.verify(header);
        return Map.of("status", service.applyAiResult(id, callback) ? "applied" : "ignored");
    }
}
