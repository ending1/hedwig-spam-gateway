package com.hs.mail.gateway.admin;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 관리자 대시보드 ID/PW 로그인. 세션은 HttpOnly + SameSite=Strict 쿠키, CSRF 토큰은 응답 본문으로 준다. */
@RestController
public class AdminAuthController {

    private final AdminSessionService sessions;

    public AdminAuthController(AdminSessionService sessions) {
        this.sessions = sessions;
    }

    /** body: username, password. */
    @PostMapping("/admin/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!sessions.isLoginEnabled()) {
            return error(HttpStatus.NOT_FOUND, "관리자 로그인 계정이 설정돼 있지 않습니다");
        }
        String username = body.get("username") == null ? "" : String.valueOf(body.get("username"));
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        try {
            AdminSessionService.Session s = sessions.login(username, password, request.getRemoteAddr());
            ResponseCookie cookie = ResponseCookie.from(AdminApiKeyGuard.SESSION_COOKIE, s.getToken())
                    .httpOnly(true).sameSite("Strict").path("/").maxAge(Duration.ofSeconds(sessions.getSessionSeconds()))
                    .build();
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("username", s.getUsername());
            res.put("csrfToken", s.getCsrfToken());
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(res);
        } catch (AdminSessionService.LoginException e) {
            return error(e.isLocked() ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    @PostMapping("/admin/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        sessions.logout(AdminApiKeyGuard.sessionToken(request));
        ResponseCookie expired = ResponseCookie.from(AdminApiKeyGuard.SESSION_COOKIE, "")
                .httpOnly(true).sameSite("Strict").path("/").maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expired.toString()).build();
    }

    /** 대시보드가 로드될 때 현재 로그인 상태와 CSRF 토큰을 얻는다. */
    @GetMapping("/admin/session")
    public ResponseEntity<?> session(HttpServletRequest request) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("loginEnabled", sessions.isLoginEnabled());
        AdminSessionService.Session s = sessions.validate(AdminApiKeyGuard.sessionToken(request));
        res.put("authenticated", s != null);
        if (s != null) {
            res.put("username", s.getUsername());
            res.put("csrfToken", s.getCsrfToken());
        }
        return ResponseEntity.ok(res);
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
