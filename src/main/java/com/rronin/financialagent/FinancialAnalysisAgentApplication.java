package com.rronin.financialagent;

import com.rronin.financialagent.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties(AgentProperties.class)
@SpringBootApplication
public class FinancialAnalysisAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinancialAnalysisAgentApplication.class, args);
    }
}
