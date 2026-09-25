import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.spamfilter.RuleBasedSpamChecker;
import com.hs.mail.gateway.spamfilter.SpamCheckRequest;
import com.hs.mail.gateway.spamfilter.SpamRuleEntry;
import com.hs.mail.gateway.spamfilter.SpamRuleService;
import com.hs.mail.gateway.spamfilter.SpamVerdict;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 사내 스팸 코퍼스(EML)에서 (1) 캠페인 중복 제거 (2) 룰 후보(KEYWORD n-gram) 추출 (3) 캠페인 단위 홀드아웃 평가를
 * 수행하는 개발용 오프라인 도구. 운영 빌드에는 포함되지 않는다(src 밖).
 *
 * 실행: java -cp "target/classes;javax.mail.jar;activation.jar;slf4j-api.jar" tools/SpamCorpusAnalyzer.java
 *            &lt;코퍼스 디렉터리&gt; &lt;출력 디렉터리&gt; &lt;자사 도메인&gt;
 *
 * 산출물은 실제 메일 제목/도메인 샘플을 담을 수 있으므로 저장소에 커밋하지 말고 별도 디렉터리에 둔다.
 */
public class SpamCorpusAnalyzer {

    static final int MIN_CAMPAIGNS = 12;
    static final int MIN_DOMAINS = 6;
    static final int MAX_CANDIDATES = 80;
    static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b");
    static final Pattern TOKEN_SPLIT = Pattern.compile("[^0-9A-Za-z가-힣]+");

    /** 코퍼스에서 확인된 정상 SaaS/알림 발신 도메인 - 스팸함에 섞여 있어서 약한 ham 대용 집합으로 쓴다. */
    static final Set<String> LEGIT_DOMAINS = new HashSet<>(Arrays.asList(
            "teams.mail.microsoft", "handysoft.atlassian.net", "flow.team", "channel.io", "email.claude.com",
            "github.com", "notifications.github.com", "slack.com", "notion.so", "google.com", "accounts.google.com",
            "microsoft.com", "atlassian.com", "amazonaws.com", "zoom.us"));

    static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the", "and", "for", "you", "your", "with", "that", "this", "from", "have", "are", "will", "com", "www",
            "http", "https", "html", "mail", "email", "kr", "co", "net", "org", "nbsp", "img", "src", "div", "span",
            "font", "style", "table", "href", "class", "width", "height", "color", "align", "center", "px", "left",
            "right", "top", "bottom", "border", "background", "text", "size", "family", "line", "padding", "margin",
            "안녕하세요", "감사합니다", "있습니다", "합니다", "입니다", "드립니다", "바랍니다", "위한", "통해", "대한",
            "and", "or", "of", "to", "in", "on", "is", "it", "be", "as", "at", "by", "we", "our", "us", "not"));

    static class Msg {
        String file, fromDomain, subject, campaign, clientIp;
        String mailFrom, toHeader, headers, body;
        List<String> rawLines;
        String visible;
        boolean spoof, legit, internalOrigin;
        List<String> tokens = new ArrayList<>();
    }

    public static void main(String[] args) throws Exception {
        Path corpus = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        String own = args[2].toLowerCase();
        Files.createDirectories(out);

        List<Msg> all = load(corpus, own);
        List<Msg> mining = all.stream().filter(m -> !m.spoof && !m.legit).collect(Collectors.toList());
        List<Msg> ham = all.stream().filter(m -> m.legit).collect(Collectors.toList());

        Map<String, List<Msg>> campaigns = new LinkedHashMap<>();
        for (Msg m : mining) campaigns.computeIfAbsent(m.campaign, k -> new ArrayList<>()).add(m);
        List<String> trainKeys = new ArrayList<>(), testKeys = new ArrayList<>();
        for (String k : campaigns.keySet()) {
            (Math.floorMod(k.hashCode(), 10) < 7 ? trainKeys : testKeys).add(k);
        }

        // 캠페인당 대표 1건으로 n-gram의 문서빈도(DF)와 서로 다른 발신 도메인 수를 센다.
        Map<String, Set<String>> ngramCampaigns = new HashMap<>();
        Map<String, Set<String>> ngramDomains = new HashMap<>();
        for (String k : trainKeys) {
            Msg rep = campaigns.get(k).get(0);
            for (String g : ngrams(rep.tokens)) {
                ngramCampaigns.computeIfAbsent(g, x -> new HashSet<>()).add(k);
                ngramDomains.computeIfAbsent(g, x -> new HashSet<>()).add(rep.fromDomain);
            }
        }
        Map<String, Integer> hamDf = new HashMap<>();
        for (Msg h : ham) for (String g : ngrams(h.tokens)) hamDf.merge(g, 1, Integer::sum);

        List<String> ranked = ngramCampaigns.keySet().stream()
                .filter(g -> ngramCampaigns.get(g).size() >= MIN_CAMPAIGNS)
                .filter(g -> ngramDomains.get(g).size() >= MIN_DOMAINS)
                .filter(g -> !hamDf.containsKey(g))
                .filter(g -> !allStop(g))
                .sorted((x, y) -> {
                    int d = ngramDomains.get(y).size() - ngramDomains.get(x).size();
                    return d != 0 ? d : ngramCampaigns.get(y).size() - ngramCampaigns.get(x).size();
                })
                .collect(Collectors.toList());

        // 이미 더 높은 순위의 bigram에 포함되는 unigram은 중복이라 제거한다.
        List<String> picked = new ArrayList<>();
        for (String g : ranked) {
            if (picked.size() >= MAX_CANDIDATES) break;
            boolean redundant = false;
            if (!g.contains(" ")) {
                for (String p : picked) if (p.contains(" ") && Arrays.asList(p.split(" ")).contains(g)) { redundant = true; break; }
            }
            if (!redundant) picked.add(g);
        }

        List<SpamCheckRequest> hamReqs = new ArrayList<>();
        for (Msg h : ham) hamReqs.add(SpamCheckRequest.from(h.mailFrom,
                Collections.singletonList(h.toHeader == null ? "" : h.toHeader), h.rawLines, 4000, h.clientIp));
        int baselineFp = fpCount(checker(own, SpamRuleService.defaultEntries()), hamReqs);
        int budget = baselineFp + HAM_FP_BUDGET;
        List<SpamRuleEntry> acceptedRules = new ArrayList<>(SpamRuleService.defaultEntries());
        List<String> afterHam = new ArrayList<>();
        for (String g : picked) {
            int c0 = ngramCampaigns.get(g).size(), d0 = ngramDomains.get(g).size();
            double w0 = (c0 >= 40 && d0 >= 20) ? 2.5 : 1.5;
            List<SpamRuleEntry> trial = new ArrayList<>(acceptedRules);
            trial.add(new SpamRuleEntry(null, SpamRuleEntry.RuleType.KEYWORD, toRegex(g), w0, true, null));
            if (fpCount(checker(own, trial), hamReqs) <= budget) {
                acceptedRules = trial;
                afterHam.add(g);
            }
        }
        int droppedByHam = picked.size() - afterHam.size();
        picked = afterHam;
        List<SpamRuleEntry> candidates = new ArrayList<>();
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < picked.size(); i++) {
            String g = picked.get(i);
            int c = ngramCampaigns.get(g).size(), d = ngramDomains.get(g).size();
            double weight = (c >= 40 && d >= 20) ? 2.5 : 1.5;
            String regex = toRegex(g);
            String reason = "[후보 campaigns=" + c + " domains=" + d + "] 코퍼스 자동 추출";
            candidates.add(new SpamRuleEntry(null, SpamRuleEntry.RuleType.KEYWORD, regex, weight, true, reason));
            json.append("  {\"ruleType\":\"KEYWORD\",\"pattern\":").append(jsonStr(regex))
                    .append(",\"weight\":").append(weight).append(",\"enabled\":false,\"reason\":")
                    .append(jsonStr(reason)).append("}").append(i < picked.size() - 1 ? ",\n" : "\n");
        }
        json.append("]\n");
        Files.write(out.resolve("candidates.json"), json.toString().getBytes(StandardCharsets.UTF_8));

        // 홀드아웃 평가: 기존 룰 vs 기존+후보, 미사용 캠페인(test)과 약한 ham 집합 각각에 대해.
        List<Msg> testMsgs = new ArrayList<>();
        for (String k : testKeys) testMsgs.add(campaigns.get(k).get(0));
        RuleBasedSpamChecker base = checker(own, SpamRuleService.defaultEntries());
        List<SpamRuleEntry> merged = new ArrayList<>(SpamRuleService.defaultEntries());
        merged.addAll(candidates);
        RuleBasedSpamChecker withCand = checker(own, merged);
        int[] tb = spamCount(base, testMsgs), tc = spamCount(withCand, testMsgs);
        int[] hb = spamCount(base, ham), hc = spamCount(withCand, ham);

        StringBuilder rep = new StringBuilder();
        rep.append("# 스팸 코퍼스 분석 리포트\n\n");
        rep.append("- 전체 메시지: ").append(all.size()).append("\n");
        rep.append("- 자사 도메인 사칭(internal-domain-spoof 대상): ").append(all.stream().filter(m -> m.spoof).count()).append("\n");
        rep.append("- 약한 ham 대용(정상 SaaS 도메인/사내 IP 발신): ").append(ham.size()).append("\n");
        rep.append("- 마이닝 대상 메시지: ").append(mining.size()).append(" → 중복 제거 후 캠페인 ").append(campaigns.size())
                .append(" (train ").append(trainKeys.size()).append(" / test ").append(testKeys.size()).append(")\n\n");
        rep.append("## 홀드아웃 평가 (test 캠페인은 후보 추출에 쓰이지 않음)\n\n");
        rep.append("| 대상 | 건수 | 기존 룰만 스팸 판정 | 기존+후보 스팸 판정 |\n|---|---|---|---|\n");
        rep.append("| test 캠페인(미사용 스팸) | ").append(testMsgs.size()).append(" | ").append(tb[0]).append(" | ").append(tc[0]).append(" |\n");
        rep.append("| ham 대용(오탐 프록시) | ").append(ham.size()).append(" | ").append(hb[0]).append(" | ").append(hc[0]).append(" |\n\n");
        rep.append("## 상위 발신 도메인 (캠페인 수 기준)\n\n");
        Map<String, Set<String>> domCamp = new HashMap<>();
        for (String k : campaigns.keySet()) domCamp.computeIfAbsent(campaigns.get(k).get(0).fromDomain, x -> new HashSet<>()).add(k);
        domCamp.entrySet().stream().sorted((a, b) -> b.getValue().size() - a.getValue().size()).limit(30)
                .forEach(e -> rep.append("- ").append(e.getKey()).append(": 캠페인 ").append(e.getValue().size()).append("\n"));
        rep.append("\n## 후보 룰 ").append(picked.size()).append("개 (campaigns/domains)\n\n");
        for (String g : picked) rep.append("- `").append(g).append("` ").append(ngramCampaigns.get(g).size())
                .append("/").append(ngramDomains.get(g).size()).append("\n");
        Files.write(out.resolve("report.md"), rep.toString().getBytes(StandardCharsets.UTF_8));

        System.out.println("messages=" + all.size() + " mining=" + mining.size() + " campaigns=" + campaigns.size()
                + " train/test=" + trainKeys.size() + "/" + testKeys.size() + " ham=" + ham.size());
        System.out.println("candidates=" + picked.size() + " (오탐 예산 초과로 제외: " + droppedByHam + ")");
        System.out.println("TEST 캠페인 스팸판정: 기존=" + tb[0] + "/" + testMsgs.size() + "  기존+후보=" + tc[0] + "/" + testMsgs.size());
        System.out.println("HAM  대용  스팸판정: 기존=" + hb[0] + "/" + ham.size() + "  기존+후보=" + hc[0] + "/" + ham.size());
    }

    static RuleBasedSpamChecker checker(String own, List<SpamRuleEntry> rules) {
        GatewayProperties props = new GatewayProperties();
        props.getRuleFilter().setEnabled(true);
        props.getRuleFilter().setInternalDomains(Collections.singletonList(own));
        return new RuleBasedSpamChecker(props, SpamRuleService.fixed(rules));
    }

    static int[] spamCount(RuleBasedSpamChecker c, List<Msg> msgs) {
        int spam = 0;
        for (Msg m : msgs) {
            SpamVerdict v = c.evaluate(SpamCheckRequest.from(m.mailFrom,
                    Collections.singletonList(m.toHeader == null ? "" : m.toHeader), m.rawLines, 4000, m.clientIp));
            if (v.isSpam()) spam++;
        }
        return new int[]{spam};
    }

    static String toRegex(String g) {
        String[] toks = g.split(" ");
        String body = Arrays.stream(toks).map(Pattern::quote).collect(Collectors.joining("\\s*"));
        char first = toks[0].charAt(0), last = toks[toks.length - 1].charAt(toks[toks.length - 1].length() - 1);
        String pre = first < 128 ? "\\b" : "", post = last < 128 ? "\\b" : "";
        return pre + body + post;
    }

    static boolean usable(String t) {
        if (t.length() < 2 || t.length() > 20 || t.matches("\\d+")) return false;
        if (t.matches("[0-9a-f]{3,8}") || t.matches("\\d+(px|pt|em|rem|x)")) return false;
        if (t.matches("[a-z0-9]+") && t.length() < 4) return false;
        return true;
    }

    static final int HAM_FP_BUDGET = 3;

    static int fpCount(RuleBasedSpamChecker c, List<SpamCheckRequest> reqs) {
        int n = 0;
        for (SpamCheckRequest r : reqs) if (c.evaluate(r).isSpam()) n++;
        return n;
    }

    static boolean allStop(String g) {
        for (String t : g.split(" ")) if (!STOP.contains(t)) return false;
        return true;
    }

    static List<String> ngrams(List<String> tokens) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            boolean korean = t.chars().anyMatch(ch -> ch >= '가' && ch <= '힣');
            if (!STOP.contains(t) && korean && t.length() >= 3) out.add(t);
            if (i + 1 < tokens.size()) out.add(t + " " + tokens.get(i + 1));
        }
        return new ArrayList<>(out);
    }

    static List<Msg> load(Path corpus, String own) throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.walk(corpus)) {
            files = s.filter(p -> p.toString().endsWith(".eml")).collect(Collectors.toList());
        }
        Session session = Session.getDefaultInstance(new Properties());
        List<Msg> out = new ArrayList<>();
        for (Path f : files) {
            if (Files.size(f) > 3_000_000) continue;
            List<String> raw = Files.readAllLines(f, StandardCharsets.ISO_8859_1).stream()
                    .map(l -> new String(l.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)).collect(Collectors.toList());
            try (InputStream in = new BufferedInputStream(Files.newInputStream(f))) {
                MimeMessage m = new MimeMessage(session, in);
                Msg x = new Msg();
                x.rawLines = raw;
                x.file = f.getFileName().toString();
                x.subject = m.getSubject() == null ? "" : m.getSubject();
                String from = m.getHeader("From", null);
                x.fromDomain = domainOf(from);
                String rp = m.getHeader("Return-Path", null);
                x.mailFrom = rp != null ? rp : (from == null ? "" : from);
                x.toHeader = m.getHeader("To", null);
                StringBuilder h = new StringBuilder();
                Enumeration<?> lines = m.getAllHeaderLines();
                while (lines.hasMoreElements()) h.append((String) lines.nextElement()).append("\n");
                x.headers = h.toString();
                String[] recv = m.getHeader("Received");
                if (recv != null && recv.length > 0) {
                    Matcher im = IPV4.matcher(recv[0]);
                    if (im.find()) x.clientIp = im.group(1);
                }
                x.internalOrigin = x.clientIp != null && isPrivate(x.clientIp);
                String text = extractText(m);
                x.body = text.length() > 4000 ? text.substring(0, 4000) : text;
                x.visible = htmlToText(text);
                boolean ownFrom = x.fromDomain.equals(own) || x.fromDomain.endsWith("." + own);
                x.spoof = ownFrom && !x.internalOrigin;
                x.legit = LEGIT_DOMAINS.contains(x.fromDomain) || (ownFrom && x.internalOrigin);
                x.campaign = x.fromDomain + "|" + normalizeSubject(x.subject);
                String plain = (x.subject + " " + htmlToText(text)).toLowerCase();
                for (String t : TOKEN_SPLIT.split(plain)) {
                    if (usable(t)) x.tokens.add(t);
                    if (x.tokens.size() > 400) break;
                }
                out.add(x);
            } catch (Exception ignore) { }
        }
        return out;
    }

    static String htmlToText(String h) {
        String t = h.replaceAll("(?is)<(style|script|head)[^>]*>.*?</\\1>", " ").replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("<[^>]+>", " ").replaceAll("&[a-zA-Z#0-9]+;", " ");
        return t.length() > 6000 ? t.substring(0, 6000) : t;
    }

    static boolean isPrivate(String ip) {
        return ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.") || ip.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    static String normalizeSubject(String s) {
        return s.toLowerCase().replaceAll("^(re|fw|fwd)\\s*:\\s*", "").replaceAll("[\\(\\[]광고[\\)\\]]", "")
                .replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim();
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

    static String extractText(Part p) throws Exception {
        if (p.isMimeType("text/*")) {
            Object c = p.getContent();
            return c instanceof String ? (String) c : "";
        }
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                sb.append(extractText(mp.getBodyPart(i))).append("\n");
                if (sb.length() > 4000) break;
            }
            return sb.toString();
        }
        return "";
    }

    static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
