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
        SpamClassifier base;
        switch (config.getProvider()) {
            case GEMMA_LOCAL:
                base = new GemmaOllamaClassifier(config, rag);
                break;
            case GEMINI:
                base = new GeminiClassifier(config, rag);
                break;
            case CLAUDE:
                base = new ClaudeClassifier(config, rag);
                break;
            case NONE:
            default:
                return new NoopSpamClassifier();
        }
        return config.getCache().isEnabled() ? new CachingSpamClassifier(base, config.getCache()) : base;
    }
}
