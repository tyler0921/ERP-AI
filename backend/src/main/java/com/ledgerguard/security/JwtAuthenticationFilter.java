package com.ledgerguard.security;

import com.ledgerguard.common.ErrorCode;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bearer Access Token을 검증해 SecurityContext를 채운다. 실패 사유는 EntryPoint가 응답에 사용한다. */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    static final String AUTH_ERROR_ATTR = "ledgerguard.auth.error";
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            try {
                JwtService.ParsedToken token = jwtService.parse(header.substring(BEARER.length()).trim());
                if (JwtService.ACCESS.equals(token.type())) {
                    var authentication = new UsernamePasswordAuthenticationToken(
                            new AuthUser(token.userId()), null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    request.setAttribute(AUTH_ERROR_ATTR, ErrorCode.AUTH_INVALID_TOKEN);
                }
            } catch (ExpiredJwtException e) {
                request.setAttribute(AUTH_ERROR_ATTR, ErrorCode.AUTH_TOKEN_EXPIRED);
            } catch (JwtException | IllegalArgumentException e) {
                request.setAttribute(AUTH_ERROR_ATTR, ErrorCode.AUTH_INVALID_TOKEN);
            }
        }
        chain.doFilter(request, response);
    }
}
