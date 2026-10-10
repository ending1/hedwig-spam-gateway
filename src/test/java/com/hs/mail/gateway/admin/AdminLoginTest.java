package com.hs.mail.gateway.admin;

import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.Cookie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 관리자 ID/PW 로그인: 세션, 잠금, CSRF, API 키와의 공존. */
class AdminLoginTest {

    private static final String HASH = new BCryptPasswordEncoder().encode("correct-horse-battery");

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private GatewayProperties props(String apiKey, boolean withUser) {
        GatewayProperties p = new GatewayProperties();
        p.getAdmin().setApiKey(apiKey);
        if (withUser) {
            GatewayProperties.AdminUser u = new GatewayProperties.AdminUser();
            u.setUsername("admin");
            u.setPasswordHash(HASH);
            p.getAdmin().getUsers().add(u);
        }
        return p;
    }

    private void request(String method, String sessionToken, String csrf) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, "/admin/spam-reports");
        if (sessionToken != null) {
            req.setCookies(new Cookie(AdminApiKeyGuard.SESSION_COOKIE, sessionToken));
        }
        if (csrf != null) {
            req.addHeader(AdminApiKeyGuard.CSRF_HEADER, csrf);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    void 올바른_ID_비밀번호로_로그인하면_세션이_생기고_ID는_대소문자를_구분하지_않는다() throws Exception {
        AdminSessionService svc = new AdminSessionService(props("k", true));
        assertTrue(svc.isLoginEnabled());
        AdminSessionService.Session s = svc.login("ADMIN", "correct-horse-battery", "10.0.0.1");
        assertNotNull(s.getToken());
        assertNotNull(s.getCsrfToken());
        assertEquals("ADMIN", s.getUsername());
        assertNotNull(svc.validate(s.getToken()));
        svc.logout(s.getToken());
        assertNull(svc.validate(s.getToken()));
    }

    @Test
    void 틀린_비밀번호와_없는_ID는_같은_오류로_거부한다() throws Exception {
        AdminSessionService svc = new AdminSessionService(props("k", true));
        AdminSessionService.LoginException a = assertThrows(AdminSessionService.LoginException.class,
                () -> svc.login("admin", "wrong-password-1", "10.0.0.1"));
        AdminSessionService.LoginException b = assertThrows(AdminSessionService.LoginException.class,
                () -> svc.login("nobody", "correct-horse-battery", "10.0.0.1"));
        assertEquals(a.getMessage(), b.getMessage(), "ID 존재 여부를 알려주지 않는다");
        assertFalse(a.isLocked());
    }

    @Test
    void 연속_실패하면_잠기고_잠긴_동안은_올바른_비밀번호도_거부하며_다른_IP는_영향받지_않는다() throws Exception {
        GatewayProperties p = props("k", true);
        p.getAdmin().setMaxLoginFailures(3);
        AdminSessionService svc = new AdminSessionService(p);
        for (int i = 0; i < 3; i++) {
            assertThrows(AdminSessionService.LoginException.class, () -> svc.login("admin", "bad-bad-bad", "10.0.0.9"));
        }
        AdminSessionService.LoginException locked = assertThrows(AdminSessionService.LoginException.class,
                () -> svc.login("admin", "correct-horse-battery", "10.0.0.9"));
        assertTrue(locked.isLocked());
        assertNotNull(svc.login("admin", "correct-horse-battery", "10.0.0.10"), "다른 IP는 잠기지 않는다");
    }

    @Test
    void 세션은_만료되고_로그인_계정이_없으면_로그인이_비활성이다() throws Exception {
        GatewayProperties p = props("k", true);
        p.getAdmin().setSessionMinutes(1);
        AdminSessionService svc = new AdminSessionService(p);
        assertFalse(new AdminSessionService(props("k", false)).isLoginEnabled());
        AdminSessionService.Session s = svc.login("admin", "correct-horse-battery", "1.1.1.1");
        assertNotNull(svc.validate(s.getToken()));
        // 만료시간을 과거로 돌려 만료를 시뮬레이션
        java.lang.reflect.Field f = AdminSessionService.Session.class.getDeclaredField("expiresAt");
        f.setAccessible(true);
        f.setLong(s, System.currentTimeMillis() - 1);
        assertNull(svc.validate(s.getToken()));
    }

    @Test
    void 가드는_API키_또는_로그인_세션을_허용하고_조회가_아니면_CSRF_토큰이_필요하다() throws Exception {
        GatewayProperties p = props("secret-key", true);
        AdminSessionService svc = new AdminSessionService(p);
        AdminApiKeyGuard guard = new AdminApiKeyGuard(p, svc);
        AdminSessionService.Session s = svc.login("admin", "correct-horse-battery", "1.1.1.1");

        // API 키(스크립트용)
        request("POST", null, null);
        assertTrue(guard.isAuthorized("secret-key"));
        assertFalse(guard.isAuthorized("wrong"));
        assertFalse(guard.isAuthorized(null));

        // 세션: 조회는 쿠키만으로
        request("GET", s.getToken(), null);
        assertTrue(guard.isAuthorized(null));
        // 변경 요청은 CSRF 토큰이 맞아야 함
        request("POST", s.getToken(), null);
        assertFalse(guard.isAuthorized(null), "CSRF 토큰 없음");
        request("POST", s.getToken(), "wrong-token");
        assertFalse(guard.isAuthorized(null), "CSRF 토큰 불일치");
        request("POST", s.getToken(), s.getCsrfToken());
        assertTrue(guard.isAuthorized(null));
        // 로그아웃 후에는 거부
        svc.logout(s.getToken());
        request("GET", s.getToken(), null);
        assertFalse(guard.isAuthorized(null));
        // 위조된 쿠키
        request("GET", "forged", null);
        assertFalse(guard.isAuthorized(null));
    }

    @Test
    void 로그인_계정만_설정하고_API키가_없어도_인증없이_열리지_않는다() throws Exception {
        GatewayProperties p = props(null, true);
        AdminApiKeyGuard guard = new AdminApiKeyGuard(p, new AdminSessionService(p));
        request("GET", null, null);
        assertFalse(guard.isAuthorized(null));
        assertFalse(guard.isAuthorized(""));
    }

    @Test
    void 키도_계정도_없으면_개발_모드로_열린다() throws Exception {
        GatewayProperties p = props(null, false);
        AdminApiKeyGuard guard = new AdminApiKeyGuard(p, new AdminSessionService(p));
        assertTrue(guard.isAuthorized(null));
    }

    @Test
    void 해시가_BCrypt가_아닌_계정은_로그인에_쓰지_않는다() throws Exception {
        GatewayProperties p = new GatewayProperties();
        GatewayProperties.AdminUser u = new GatewayProperties.AdminUser();
        u.setUsername("admin");
        u.setPasswordHash("plain-text-password");
        p.getAdmin().getUsers().add(u);
        AdminSessionService svc = new AdminSessionService(p);
        assertFalse(svc.isLoginEnabled());
        assertThrows(AdminSessionService.LoginException.class, () -> svc.login("admin", "plain-text-password", "1.1.1.1"));
    }
}
