package com.ledgerguard.document;

import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.document.DocumentDtos.AiResultCallback;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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
    private final byte[] token;

    public InternalDocumentController(DocumentService service, @Value("${app.internal-token}") String token) {
        this.service = service;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{id}/ai-result")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> aiResult(@RequestHeader(value = "X-Internal-Token", required = false) String header,
                                        @PathVariable Long id, @RequestBody AiResultCallback callback) {
        // 길이에 따른 시간 차이로 토큰을 추측하지 못하게 상수 시간 비교
        if (header == null || !MessageDigest.isEqual(token, header.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.INTERNAL_TOKEN_INVALID);
        }
        return Map.of("status", service.applyAiResult(id, callback) ? "applied" : "ignored");
    }
}
