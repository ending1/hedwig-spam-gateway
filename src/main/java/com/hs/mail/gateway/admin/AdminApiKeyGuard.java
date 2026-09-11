package com.hs.mail.gateway.admin;

import com.hs.mail.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

/** {@code gateway.admin.api-key}가 설정돼 있으면 X-Admin-Key 헤더가 일치해야 admin API를 쓸 수 있다. */
@Component
public class AdminApiKeyGuard {

    private final GatewayProperties.Admin config;

    public AdminApiKeyGuard(GatewayProperties properties) {
        this.config = properties.getAdmin();
    }

    public boolean isAuthorized(String providedKey) {
        String expected = config.getApiKey();
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        return expected.equals(providedKey);
    }
}
