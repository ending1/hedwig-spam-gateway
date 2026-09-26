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

    /** 신고 접수: report 키 또는 admin 키 중 하나가 맞으면 허용. 둘 다 미설정이면(개발) 허용. */
    public boolean isReportAuthorized(String reportKey, String adminKey) {
        String expectedReport = config.getReportApiKey();
        boolean reportConfigured = expectedReport != null && !expectedReport.isEmpty();
        boolean adminConfigured = config.getApiKey() != null && !config.getApiKey().isEmpty();
        if (!reportConfigured && !adminConfigured) {
            return true;
        }
        if (reportConfigured && expectedReport.equals(reportKey)) {
            return true;
        }
        return adminConfigured && config.getApiKey().equals(adminKey);
    }

    public boolean isAuthorized(String providedKey) {
        String expected = config.getApiKey();
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        return expected.equals(providedKey);
    }
}
