package com.hs.mail.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 빈 이름을 "gatewayProperties"로 고정한다 - BanListService의
 * {@code @Scheduled(fixedDelayString = "#{gatewayProperties.ban.pollIntervalSeconds * 1000}")}
 * 같은 SpEL 참조가 이 이름에 의존한다.
 */
@Configuration
public class PropertiesConfig {

    @Bean
    @ConfigurationProperties(prefix = "gateway")
    public GatewayProperties gatewayProperties() {
        return new GatewayProperties();
    }
}
