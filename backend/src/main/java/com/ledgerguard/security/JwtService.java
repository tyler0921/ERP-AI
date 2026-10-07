package com.ledgerguard.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtService {

    public static final String ACCESS = "access";
    public static final String REFRESH = "refresh";
    private static final String TYPE_CLAIM = "typ";

    private final SecretKey key;
    private final JwtProperties props;

    public JwtService(JwtProperties props) {
        byte[] bytes = props.secret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret(JWT_SECRET)은 32바이트 이상이어야 합니다.");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.props = props;
    }

    public String createAccessToken(Long userId) {
        return build(userId, ACCESS, Duration.ofMinutes(props.accessTokenMinutes()));
    }

    public String createRefreshToken(Long userId) {
        return build(userId, REFRESH, Duration.ofDays(props.refreshTokenDays()));
    }

    public long accessTokenSeconds() {
        return Duration.ofMinutes(props.accessTokenMinutes()).toSeconds();
    }

    /**
     * 서명/만료를 검증하고 토큰 내용을 돌려준다.
     *
     * @throws io.jsonwebtoken.ExpiredJwtException 만료된 경우
     * @throws JwtException                        그 외 유효하지 않은 경우
     */
    public ParsedToken parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        try {
            return new ParsedToken(Long.valueOf(claims.getSubject()), claims.get(TYPE_CLAIM, String.class));
        } catch (NumberFormatException e) {
            throw new JwtException("잘못된 subject", e);
        }
    }

    private String build(Long userId, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(TYPE_CLAIM, type)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public record ParsedToken(Long userId, String type) {
    }
}
