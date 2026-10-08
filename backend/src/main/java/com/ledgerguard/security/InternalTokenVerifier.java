package com.ledgerguard.security;

import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AI 서비스 → backend 내부 API(/internal/**)의 공유 토큰(X-Internal-Token) 확인. */
@Component
public class InternalTokenVerifier {

    private final byte[] token;

    public InternalTokenVerifier(@Value("${app.internal-token}") String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    public void verify(String header) {
        // 길이에 따른 시간 차이로 토큰을 추측하지 못하게 상수 시간 비교
        if (header == null || !MessageDigest.isEqual(token, header.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.INTERNAL_TOKEN_INVALID);
        }
    }
}
