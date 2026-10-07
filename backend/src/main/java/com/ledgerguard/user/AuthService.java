package com.ledgerguard.user;

import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.security.JwtService;
import com.ledgerguard.user.AuthDtos.LoginRequest;
import com.ledgerguard.user.AuthDtos.RefreshRequest;
import com.ledgerguard.user.AuthDtos.SignupRequest;
import com.ledgerguard.user.AuthDtos.TokenResponse;
import com.ledgerguard.user.AuthDtos.UserResponse;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;
    /** 존재하지 않는 이메일로 로그인할 때도 비슷한 시간이 걸리도록 사용하는 더미 해시. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                       AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
        this.dummyHash = passwordEncoder.encode("ledgerguard-dummy-password");
    }

    @Transactional
    public UserResponse signup(SignupRequest req) {
        String email = normalize(req.email());
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        User user = users.save(new User(email, passwordEncoder.encode(req.password()), req.name().trim()));
        UserResponse response = UserResponse.from(user);
        audit.record(null, user.getId(), AuditActions.USER_SIGNUP, AuditActions.TARGET_USER, user.getId(),
                null, response);
        return response;
    }

    /** 실패 이력이 롤백되지 않도록 트랜잭션 없이 실행한다. (AuditService가 자체 트랜잭션을 연다) */
    public TokenResponse login(LoginRequest req) {
        String email = normalize(req.email());
        Optional<User> found = users.findByEmail(email);

        boolean matched;
        if (found.isPresent()) {
            matched = passwordEncoder.matches(req.password(), found.get().getPasswordHash());
        } else {
            passwordEncoder.matches(req.password(), dummyHash);
            matched = false;
        }

        if (!matched) {
            Long userId = found.map(User::getId).orElse(null);
            audit.record(null, userId, AuditActions.LOGIN_FAILURE, AuditActions.TARGET_USER, userId,
                    null, Map.of("email", email));
            throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        Long userId = found.get().getId();
        audit.record(null, userId, AuditActions.LOGIN_SUCCESS, AuditActions.TARGET_USER, userId, null, null);
        return issueTokens(userId);
    }

    public TokenResponse refresh(RefreshRequest req) {
        JwtService.ParsedToken token;
        try {
            token = jwtService.parse(req.refreshToken());
        } catch (ExpiredJwtException e) {
            throw new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.AUTH_INVALID_TOKEN);
        }
        if (!JwtService.REFRESH.equals(token.type()) || !users.existsById(token.userId())) {
            throw new ApiException(ErrorCode.AUTH_INVALID_TOKEN);
        }
        return issueTokens(token.userId());
    }

    private TokenResponse issueTokens(Long userId) {
        return new TokenResponse(jwtService.createAccessToken(userId), jwtService.createRefreshToken(userId),
                "Bearer", jwtService.accessTokenSeconds());
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
