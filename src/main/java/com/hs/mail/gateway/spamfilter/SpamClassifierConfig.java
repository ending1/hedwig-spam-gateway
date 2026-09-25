package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.config.GatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** gateway.spam-filter.provider 설정값에 따라 분류기 구현체를 선택한다. */
@Configuration
public class SpamClassifierConfig {

    @Bean
    public SpamClassifier spamClassifier(GatewayProperties properties, SpamRagService rag) {
        GatewayProperties.SpamFilter config = properties.getSpamFilter();
        if (!config.isEnabled()) {
            return new NoopSpamClassifier();
        }
        switch (config.getProvider()) {
            case GEMMA_LOCAL:
                return new GemmaOllamaClassifier(config, rag);
            case GEMINI:
                return new GeminiClassifier(config, rag);
            case CLAUDE:
                return new ClaudeClassifier(config, rag);
            case NONE:
            default:
                return new NoopSpamClassifier();
        }
    }
}
