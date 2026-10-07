package com.ledgerguard.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long accessTokenMinutes, long refreshTokenDays) {
}
