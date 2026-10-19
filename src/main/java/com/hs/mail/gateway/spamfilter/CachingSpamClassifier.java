package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.config.GatewayProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * LLM 판정 결과 캐시. 스팸은 같은 캠페인이 이름·숫자·링크만 바뀌어 대량으로 오므로, 발신 도메인 + 정규화한 제목/본문이
 * 같으면 이전 판정을 재사용해 LLM 호출(비용·지연)을 줄인다. 키에서 숫자/URL/이메일/기호를 지우므로 개인화된 변형이 같은
 * 키가 된다. 성공한 판정만 저장하고(오류는 저장 안 함), 스팸은 오래, 정상은 짧게 보관한다. 서버 메모리에만 있어 재시작하면
 * 비워진다. 키가 너무 짧은(내용이 거의 없는) 메일은 서로 다른 메일이 같은 키가 될 수 있어 캐시하지 않는다.
 */
public class CachingSpamClassifier implements SpamClassifier {

    private static final Pattern URL = Pattern.compile("https?://\\S+|www\\.\\S+");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w.-]+");
    private static final Pattern NOT_LETTER = Pattern.compile("[^\\p{L}]+");

    private static final class Entry {
        final SpamVerdict verdict;
        final long expiresAt;

        Entry(SpamVerdict verdict, long expiresAt) {
            this.verdict = verdict;
            this.expiresAt = expiresAt;
        }
    }

    private final SpamClassifier delegate;
    private final GatewayProperties.SpamFilter.Cache config;
    private final Map<String, Entry> cache;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();

    public CachingSpamClassifier(SpamClassifier delegate, GatewayProperties.SpamFilter.Cache config) {
        this.delegate = delegate;
        this.config = config;
        final int max = Math.max(10, config.getMaxEntries());
        this.cache = new LinkedHashMap<String, Entry>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > max;
            }
        };
    }

    @Override
    public SpamVerdict classify(SpamCheckRequest request) throws Exception {
        String key = keyOf(request, config.getMinTextChars());
        if (key == null) {
            skipped.incrementAndGet();
            return delegate.classify(request);
        }
        long now = System.currentTimeMillis();
        synchronized (cache) {
            Entry e = cache.get(key);
            if (e != null) {
                if (e.expiresAt >= now) {
                    hits.incrementAndGet();
                    return new SpamVerdict(e.verdict.isSpam(), e.verdict.getScore(), "[캐시] " + e.verdict.getReason(),
                            e.verdict.getProvider());
                }
                cache.remove(key);
            }
        }
        misses.incrementAndGet();
        SpamVerdict v = delegate.classify(request);
        long ttl = (v.isSpam() ? config.getSpamTtlMinutes() : config.getHamTtlMinutes()) * 60_000L;
        if (ttl > 0) {
            synchronized (cache) {
                cache.put(key, new Entry(v, now + ttl));
            }
        }
        return v;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    public Map<String, Object> stats() {
        long h = hits.get(), m = misses.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("hits", h);
        out.put("misses", m);
        out.put("skipped", skipped.get());
        out.put("hitRate", h + m == 0 ? 0.0 : Math.round(1000.0 * h / (h + m)) / 10.0);
        synchronized (cache) {
            purgeExpired();
            out.put("size", cache.size());
        }
        return out;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Entry> it = cache.values().iterator();
        while (it.hasNext()) {
            if (it.next().expiresAt < now) {
                it.remove();
            }
        }
    }

    /**
     * 캐시 키. 본문이 충분히 길면(정규화 200자 이상) 제목과 본문 앞 40자(인사말·수신자 이름 자리)를 빼고 뒤쪽 600자만 쓴다:
     * 캠페인 메일은 제목과 앞머리에 이름이 들어가 변형되는 일이 많다. 본문이 짧으면 제목+본문 전체를 쓴다.
     * 내용이 너무 짧으면 null(캐시하지 않음).
     */
    static String keyOf(SpamCheckRequest r, int minTextChars) {
        String subject = normalize(r.getSubject(), 120);
        String body = normalize(r.getBody(), 800);
        if (subject.length() + body.length() < minTextChars) {
            return null;
        }
        String content;
        if (body.length() >= 200) {
            content = body.substring(40, Math.min(body.length(), 640));
        } else {
            content = subject + "|" + body;
        }
        String dmarc = r.getAuth() == null || r.getAuth().dmarc == null ? "-" : r.getAuth().dmarc;
        return sha256(domainOf(r.getFrom()) + "|" + dmarc + "|" + content);
    }

    static String normalize(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = URL.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ");
        t = EMAIL.matcher(t).replaceAll(" ");
        t = NOT_LETTER.matcher(t).replaceAll(" ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String domainOf(String from) {
        if (from == null) {
            return "";
        }
        int at = from.lastIndexOf('@');
        return (at >= 0 ? from.substring(at + 1) : from).replaceAll("[<>\\s]", "").toLowerCase(Locale.ROOT);
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
