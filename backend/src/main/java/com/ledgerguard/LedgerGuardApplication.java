package com.ledgerguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// 인증은 JWT 필터와 AuthService가 담당한다. 기본 인메모리 사용자(생성 비밀번호)는 쓰지 않는다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class LedgerGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerGuardApplication.class, args);
    }
}
