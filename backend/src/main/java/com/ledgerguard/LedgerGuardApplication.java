package com.ledgerguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LedgerGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerGuardApplication.class, args);
    }
}
