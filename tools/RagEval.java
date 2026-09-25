import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.spamfilter.*;

import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * RAG 유무에 따른 Gemini 판정을 같은 메일에 대해 비교하는 개발용 도구(운영 GeminiClassifier 코드를 그대로 사용).
 * API 키는 환경변수 GEMINI_API_KEY로만 받는다(파일/인자에 남기지 않는다).
 *
 * 평가 대상
 *  - SPAM: 홀드아웃(test) 캠페인 중 "룰기반이 스팸으로 못 잡은" 대표 메일을 무작위 N건.
 *          RAG 인덱스는 train 캠페인만으로 만들어 test 캠페인은 사례에 없다(정직한 평가).
 *  - HAM 대용: 외부 벤더 발신 정상 메일만(사내 업무 알림은 사내 정보가 있어 외부 API로 보내지 않는다).
 *
 * 실행: GEMINI_API_KEY=... java -cp "..." tools/RagEval.java &lt;코퍼스&gt; &lt;분석 디렉터리&gt; &lt;자사 도메인&gt; &lt;스팸 N&gt;
 */
public class RagEval {

    static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b");
    static final Set<String> EXTERNAL_LEGIT = new HashSet<>(Arrays.asList(
            "email.claude.com", "github.com", "notifications.github.com", "slack.com", "google.com",
            "accounts.google.com", "microsoft.com"));
    static final Set<String> ALL_LEGIT = new HashSet<>(Arrays.asList(
            "teams.mail.microsoft", "handysoft.atlassian.net", "flow.team", "channel.io", "email.claude.com",
            "github.com", "notifications.github.com", "slack.com", "notion.so", "google.com", "accounts.google.com",
            "microsoft.com", "atlassian.com", "amazonaws.com", "zoom.us"));

    static class Item {
        String file, domain;
        List<String> raw;
        String mailFrom, to, clientIp;
        String key;
    }

    public static void main(String[] args) throws Exception {
        Path corpus = Paths.get(args[0]);
        Path dir = Paths.get(args[1]);
        String own = args[2].toLowerCase();
        int nSpam = Integer.parseInt(args[3]);
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) throw new IllegalStateException("GEMINI_API_KEY 환경변수가 필요합니다");
        String model = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.5-flash-lite");

        Set<String> testKeys = new HashSet<>(Files.readAllLines(dir.resolve("rag-test-keys.txt"), StandardCharsets.UTF_8));

        // 분류기: RAG 없음 vs train 사례 인덱스 RAG
        GatewayProperties props = new GatewayProperties();
        props.getSpamFilter().setEnabled(true);
        props.getSpamFilter().setTimeoutMillis(60000);
        props.getSpamFilter().getGemini().setApiKey(apiKey);
        props.getSpamFilter().getGemini().setModel(model);
        props.getSpamFilter().getRag().setEnabled(true);
        props.getSpamFilter().getRag().setExamplesFile(dir.resolve("rag-train.jsonl").toString());
        SpamRagService rag = new SpamRagService(props);
        System.out.println("RAG active=" + rag.isActive() + " model=" + model);
        GeminiClassifier plain = new GeminiClassifier(props.getSpamFilter());
        GeminiClassifier withRag = new GeminiClassifier(props.getSpamFilter(), rag);

        // 룰기반(운영과 동일 설정) - 이미 룰이 잡는 메일은 LLM 평가 대상에서 뺀다.
        GatewayProperties rp = new GatewayProperties();
        rp.getRuleFilter().setEnabled(true);
        rp.getRuleFilter().setInternalDomains(Collections.singletonList(own));
        RuleBasedSpamChecker rules = new RuleBasedSpamChecker(rp, SpamRuleService.defaults());

        List<Item> spamPool = new ArrayList<>(), hamPool = new ArrayList<>();
        load(corpus, own, testKeys, rules, spamPool, hamPool);
        Collections.shuffle(spamPool, new Random(42));
        Collections.shuffle(hamPool, new Random(42));
        List<Item> spam = spamPool.subList(0, Math.min(nSpam, spamPool.size()));
        List<Item> ham = hamPool.subList(0, Math.min(60, hamPool.size()));
        System.out.println("spam 평가=" + spam.size() + " (풀 " + spamPool.size() + "), ham 대용 평가=" + ham.size() + " (풀 " + hamPool.size() + ")");

        int[] s = run("SPAM", spam, plain, withRag, rag);
        int[] h = run("HAM ", ham, plain, withRag, rag);

        System.out.println("\n=== 결과 (Gemini가 스팸으로 판정한 수) ===");
        System.out.println(String.format("룰이 못 잡은 미사용 스팸 %d건: RAG 없음 %d (%.1f%%) -> RAG 사용 %d (%.1f%%)",
                spam.size(), s[0], 100.0 * s[0] / Math.max(1, spam.size()), s[1], 100.0 * s[1] / Math.max(1, spam.size())));
        System.out.println(String.format("외부 벤더 정상 대용 %d건: RAG 없음 %d -> RAG 사용 %d (오탐)", ham.size(), h[0], h[1]));
        System.out.println("사례가 프롬프트에 붙은 비율: SPAM " + s[2] + "/" + spam.size() + ", HAM " + h[2] + "/" + ham.size()
                + "  | 호출 오류: " + (s[3] + h[3]));
    }

    /** returns {plainSpam, ragSpam, ragAttached, errors} */
    static int[] run(String label, List<Item> items, GeminiClassifier plain, GeminiClassifier withRag, SpamRagService rag) throws Exception {
        AtomicInteger pSpam = new AtomicInteger(), rSpam = new AtomicInteger(), attached = new AtomicInteger(), err = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> fs = new ArrayList<>();
        for (Item it : items) {
            fs.add(pool.submit(() -> {
                SpamCheckRequest req = SpamCheckRequest.from(it.mailFrom, Collections.singletonList(it.to == null ? "" : it.to),
                        it.raw, 4000, it.clientIp);
                if (!rag.retrieve(req).isEmpty()) attached.incrementAndGet();
                try {
                    SpamVerdict pv = plain.classify(req);
                    boolean wrong = label.startsWith("SPAM") != pv.isSpam();
                    if (pv.isSpam()) pSpam.incrementAndGet();
                    if (wrong) System.out.println("[" + label.trim() + " 불일치] " + it.domain + " | " + req.getSubject()
                            + " | 판정이유=" + pv.getReason());
                    if (withRag.classify(req).isSpam()) rSpam.incrementAndGet();
                } catch (Exception e) {
                    err.incrementAndGet();
                    System.out.println(label + " 오류: " + e.getMessage());
                }
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        System.out.println(label + " 완료: 없음=" + pSpam + " RAG=" + rSpam + " 사례부착=" + attached + " 오류=" + err);
        return new int[]{pSpam.get(), rSpam.get(), attached.get(), err.get()};
    }

    static void load(Path corpus, String own, Set<String> testKeys, RuleBasedSpamChecker rules,
                     List<Item> spamPool, List<Item> hamPool) throws Exception {
        List<Path> files;
        try (Stream<Path> st = Files.walk(corpus)) {
            files = st.filter(p -> p.toString().endsWith(".eml")).collect(Collectors.toList());
        }
        Session session = Session.getDefaultInstance(new Properties());
        Set<String> seenCampaign = new HashSet<>();
        for (Path f : files) {
            if (Files.size(f) > 3_000_000) continue;
            List<String> raw = Files.readAllLines(f, StandardCharsets.ISO_8859_1).stream()
                    .map(l -> new String(l.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)).collect(Collectors.toList());
            try (InputStream in = new BufferedInputStream(Files.newInputStream(f))) {
                MimeMessage m = new MimeMessage(session, in);
                String subject = m.getSubject() == null ? "" : m.getSubject();
                String domain = domainOf(m.getHeader("From", null));
                String[] recv = m.getHeader("Received");
                String ip = null;
                if (recv != null && recv.length > 0) {
                    Matcher im = IPV4.matcher(recv[0]);
                    if (im.find()) ip = im.group(1);
                }
                boolean internalOrigin = ip != null && isPrivate(ip);
                boolean ownFrom = domain.equals(own) || domain.endsWith("." + own);
                Item it = new Item();
                it.file = f.getFileName().toString();
                it.domain = domain;
                it.raw = raw;
                String rp = m.getHeader("Return-Path", null), from = m.getHeader("From", null);
                it.mailFrom = rp != null ? rp : (from == null ? "" : from);
                it.to = m.getHeader("To", null);
                it.clientIp = ip;
                if (EXTERNAL_LEGIT.contains(domain) && !internalOrigin) {
                    hamPool.add(it);
                    continue;
                }
                if (ownFrom || ALL_LEGIT.contains(domain) || internalOrigin) continue;
                String key = (domain + "|" + normalizeSubject(subject)).replace("\n", " ");
                if (!testKeys.contains(key) || !seenCampaign.add(key)) continue;
                SpamVerdict v = rules.evaluate(SpamCheckRequest.from(it.mailFrom,
                        Collections.singletonList(it.to == null ? "" : it.to), raw, 4000, ip));
                if (!v.isSpam()) spamPool.add(it);
            } catch (Exception ignore) { }
        }
    }

    static String normalizeSubject(String s) {
        return s.toLowerCase().replaceAll("^(re|fw|fwd)\\s*:\\s*", "").replaceAll("[\\(\\[]광고[\\)\\]]", "")
                .replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim();
    }

    static boolean isPrivate(String ip) {
        return ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.") || ip.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    static String domainOf(String from) {
        if (from == null) return "";
        try {
            InternetAddress[] a = InternetAddress.parse(from, false);
            if (a.length > 0 && a[0].getAddress() != null) {
                String addr = a[0].getAddress();
                int at = addr.lastIndexOf('@');
                if (at >= 0) return addr.substring(at + 1).toLowerCase();
            }
        } catch (Exception ignore) { }
        return "";
    }
}
