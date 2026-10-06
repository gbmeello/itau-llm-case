package com.itau.purchaseagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PurchaseAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PurchaseAgentApplication.class, args);
    }
}
