package com.ledgerguard.user;

import com.ledgerguard.company.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 인증/사용자 관련 요청·응답 DTO 모음. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record SignupRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72, message = "비밀번호는 8~72자여야 합니다.") String password,
            @NotBlank @Size(max = 100) String name) {
    }

    public record LoginRequest(
            @NotBlank String email,
            @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {
    }

    public record UserResponse(Long id, String email, String name) {
        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getName());
        }
    }

    public record MembershipResponse(Long companyId, String companyName, Role role) {
    }

    public record MeResponse(Long id, String email, String name, List<MembershipResponse> companies) {
    }
}
