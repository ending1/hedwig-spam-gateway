package com.hs.mail.gateway.admin;

import com.hs.mail.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

/**
 * 관리자 API 인증. 다음 중 하나면 허용한다.
 * <ul>
 *   <li>{@code X-Admin-Key} 헤더가 {@code gateway.admin.api-key}와 일치(스크립트/서버 간 호출용)</li>
 *   <li>관리자 웹 로그인 세션 쿠키가 유효(대시보드용). 조회가 아닌 요청은 {@code X-CSRF-Token}도 일치해야 한다.</li>
 * </ul>
 * API 키도 로그인 계정도 설정돼 있지 않으면(개발) 모두 허용한다.
 */
@Component
public class AdminApiKeyGuard {

    public static final String SESSION_COOKIE = "HEDWIG_ADMIN_SESSION";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private final GatewayProperties.Admin config;
    private final AdminSessionService sessions;

    public AdminApiKeyGuard(GatewayProperties properties, AdminSessionService sessions) {
        this.config = properties.getAdmin();
        this.sessions = sessions;
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
        boolean keyConfigured = expected != null && !expected.isEmpty();
        if (!keyConfigured && !sessions.isLoginEnabled()) {
            return true;
        }
        if (keyConfigured && AdminSessionService.safeEquals(expected, providedKey)) {
            return true;
        }
        return hasValidSession();
    }

    /** 지금 요청의 관리자 식별자: 로그인 세션이면 사용자 ID, 아니면 "api-key". 변경 이력에 쓴다. */
    public String currentActor() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes) {
            AdminSessionService.Session s = sessions.validate(sessionToken(((ServletRequestAttributes) attrs).getRequest()));
            if (s != null && s.getUsername() != null) {
                return s.getUsername();
            }
        }
        return "api-key";
    }

    private boolean hasValidSession() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes)) {
            return false;
        }
        HttpServletRequest req = ((ServletRequestAttributes) attrs).getRequest();
        AdminSessionService.Session s = sessions.validate(sessionToken(req));
        if (s == null) {
            return false;
        }
        String method = req.getMethod();
        boolean safe = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        return safe || AdminSessionService.safeEquals(s.getCsrfToken(), req.getHeader(CSRF_HEADER));
    }

    static String sessionToken(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (SESSION_COOKIE.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }
}
