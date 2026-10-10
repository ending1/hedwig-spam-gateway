package com.hs.mail.gateway.admin;

import com.hs.mail.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 관리자 웹 로그인(ID/PW) 세션. 계정은 설정 파일의 BCrypt 해시이고, 세션은 서버 메모리에만 둔다
 * (재시작하면 다시 로그인). 세션 토큰은 HttpOnly 쿠키로, CSRF 토큰은 로그인 응답으로 따로 내려준다.
 * 로그인 연속 실패는 IP+ID 단위로 잠가 무차별 대입을 늦춘다.
 */
@Component
public class AdminSessionService {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 없는 ID로 시도해도 BCrypt 비교 시간이 같도록 쓰는 더미 해시. */
    private static final String DUMMY_HASH = new BCryptPasswordEncoder().encode("dummy-password-for-timing");

    public static final class Session {
        private final String token;
        private final String csrfToken;
        private final String username;
        private volatile long expiresAt;

        Session(String token, String csrfToken, String username, long expiresAt) {
            this.token = token;
            this.csrfToken = csrfToken;
            this.username = username;
            this.expiresAt = expiresAt;
        }

        public String getToken() { return token; }
        public String getCsrfToken() { return csrfToken; }
        public String getUsername() { return username; }
    }

    /** 로그인 실패 결과. */
    public static final class LoginException extends Exception {
        private final boolean locked;

        LoginException(String message, boolean locked) {
            super(message);
            this.locked = locked;
        }

        public boolean isLocked() { return locked; }
    }

    private static final class Failures {
        int count;
        long lockedUntil;
        long lastAt;
    }

    private final GatewayProperties.Admin config;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public AdminSessionService(GatewayProperties properties) {
        this.config = properties.getAdmin();
    }

    /** 로그인 계정이 하나라도 설정돼 있는지. */
    public boolean isLoginEnabled() {
        for (GatewayProperties.AdminUser u : config.getUsers()) {
            if (valid(u)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valid(GatewayProperties.AdminUser u) {
        return u != null && u.getUsername() != null && !u.getUsername().trim().isEmpty()
                && u.getPasswordHash() != null && u.getPasswordHash().startsWith("$2");
    }

    public Session login(String username, String password, String clientIp) throws LoginException {
        long now = System.currentTimeMillis();
        String user = username == null ? "" : username.trim();
        String failKey = (clientIp == null ? "?" : clientIp) + "|" + user.toLowerCase(Locale.ROOT);
        Failures f = failures.get(failKey);
        if (f != null && f.lockedUntil > now) {
            long minutes = Math.max(1, TimeUnit.MILLISECONDS.toMinutes(f.lockedUntil - now) + 1);
            throw new LoginException("로그인 시도가 너무 많습니다. 약 " + minutes + "분 후에 다시 시도하세요.", true);
        }
        String hash = DUMMY_HASH;
        boolean known = false;
        for (GatewayProperties.AdminUser u : config.getUsers()) {
            if (valid(u) && u.getUsername().trim().equalsIgnoreCase(user)) {
                hash = u.getPasswordHash();
                known = true;
                break;
            }
        }
        boolean ok;
        try {
            ok = encoder.matches(password == null ? "" : password, hash) && known;
        } catch (IllegalArgumentException e) {
            ok = false;
        }
        if (!ok) {
            registerFailure(failKey, now);
            log.warn("관리자 로그인 실패: user='{}' ip={}", sanitize(user), clientIp);
            throw new LoginException("ID 또는 비밀번호가 올바르지 않습니다.", false);
        }
        failures.remove(failKey);
        purgeExpired(now);
        Session s = new Session(randomToken(), randomToken(), user, now + ttlMillis());
        sessions.put(s.token, s);
        log.info("관리자 로그인: user='{}' ip={}", sanitize(user), clientIp);
        return s;
    }

    private void registerFailure(String key, long now) {
        Failures f = failures.computeIfAbsent(key, k -> new Failures());
        synchronized (f) {
            if (now - f.lastAt > TimeUnit.MINUTES.toMillis(Math.max(1, config.getLockMinutes()) * 2L)) {
                f.count = 0;
            }
            f.lastAt = now;
            f.count++;
            if (f.count >= Math.max(1, config.getMaxLoginFailures())) {
                f.lockedUntil = now + TimeUnit.MINUTES.toMillis(Math.max(1, config.getLockMinutes()));
                f.count = 0;
            }
        }
        if (failures.size() > 10000) {
            failures.clear(); // 메모리 보호(공격 중이면 잠금 상태가 풀리는 것보다 서비스 유지가 우선)
        }
    }

    /** 유효한 세션이면 만료 시간을 연장하고 돌려준다. */
    public Session validate(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Session s = sessions.get(token);
        if (s == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (s.expiresAt < now) {
            sessions.remove(token);
            return null;
        }
        s.expiresAt = now + ttlMillis();
        return s;
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public int getSessionSeconds() {
        return (int) TimeUnit.MILLISECONDS.toSeconds(ttlMillis());
    }

    /** 상수 시간 비교. */
    static boolean safeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private long ttlMillis() {
        return TimeUnit.MINUTES.toMillis(Math.max(1, config.getSessionMinutes()));
    }

    private void purgeExpired(long now) {
        for (Iterator<Session> it = sessions.values().iterator(); it.hasNext(); ) {
            if (it.next().expiresAt < now) {
                it.remove();
            }
        }
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n\\t]", " ");
    }
}
