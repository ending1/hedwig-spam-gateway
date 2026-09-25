import com.hs.mail.gateway.spamfilter.RagText;

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
 * 스팸 코퍼스에서 RAG 사례 파일(JSONL)을 만드는 개발용 도구. 캠페인(발신도메인+정규화 제목)당 대표 1건만 남기고
 * 같은 캠페인의 수신 건수를 count로 기록한다.
 *
 * 산출물: rag-all.jsonl(배포용 전체), rag-train.jsonl(평가용 - SpamCorpusAnalyzer와 같은 해시 분할의 train만),
 *         rag-test-keys.txt(홀드아웃 캠페인 키 - 평가 도구가 test 메일을 고를 때 사용)
 *
 * 개인정보: 발췌문은 RagText.mask로 이메일/긴 숫자열을 가린다. 자사 도메인 사칭 캠페인은 룰이 결정적으로 잡으므로
 * 사례에서 제외한다. 산출물은 실제 메일에서 파생된 데이터라 저장소에 커밋하지 않는다.
 *
 * 실행: java -cp "target/classes;javax.mail.jar;activation.jar;slf4j-api.jar" tools/BuildRagExamples.java
 *            &lt;코퍼스&gt; &lt;출력 디렉터리&gt; &lt;자사 도메인&gt;
 */
public class BuildRagExamples {

    static final int SNIPPET_CHARS = 300;
    static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b");
    static final Set<String> LEGIT_DOMAINS = new HashSet<>(Arrays.asList(
            "teams.mail.microsoft", "handysoft.atlassian.net", "flow.team", "channel.io", "email.claude.com",
            "github.com", "notifications.github.com", "slack.com", "notion.so", "google.com", "accounts.google.com",
            "microsoft.com", "atlassian.com", "amazonaws.com", "zoom.us"));

    public static void main(String[] args) throws Exception {
        Path corpus = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        String own = args[2].toLowerCase();
        Files.createDirectories(out);

        List<Path> files;
        try (Stream<Path> s = Files.walk(corpus)) {
            files = s.filter(p -> p.toString().endsWith(".eml")).collect(Collectors.toList());
        }
        Session session = Session.getDefaultInstance(new Properties());
        Map<String, String[]> repByCampaign = new LinkedHashMap<>();
        Map<String, Integer> countByCampaign = new HashMap<>();
        int skipped = 0;
        for (Path f : files) {
            if (Files.size(f) > 3_000_000) { skipped++; continue; }
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
                if (ownFrom || LEGIT_DOMAINS.contains(domain) || internalOrigin) continue;

                String key = domain + "|" + normalizeSubject(subject);
                countByCampaign.merge(key, 1, Integer::sum);
                if (!repByCampaign.containsKey(key)) {
                    String visible = RagText.visibleText(extractText(m));
                    String snippet = RagText.mask(visible);
                    if (snippet.length() > SNIPPET_CHARS) snippet = snippet.substring(0, SNIPPET_CHARS);
                    repByCampaign.put(key, new String[]{RagText.mask(subject), snippet, domain});
                }
            } catch (Exception ignore) { skipped++; }
        }

        StringBuilder all = new StringBuilder(), train = new StringBuilder(), testKeys = new StringBuilder();
        int nAll = 0, nTrain = 0;
        for (Map.Entry<String, String[]> e : repByCampaign.entrySet()) {
            String[] r = e.getValue();
            String line = "{\"subject\":" + js(r[0]) + ",\"snippet\":" + js(r[1]) + ",\"domain\":" + js(r[2])
                    + ",\"count\":" + countByCampaign.get(e.getKey()) + "}\n";
            all.append(line);
            nAll++;
            if (Math.floorMod(e.getKey().hashCode(), 10) < 7) { train.append(line); nTrain++; }
            else testKeys.append(e.getKey().replace("\n", " ")).append("\n");
        }
        Files.write(out.resolve("rag-all.jsonl"), all.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(out.resolve("rag-train.jsonl"), train.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(out.resolve("rag-test-keys.txt"), testKeys.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("campaign examples: all=" + nAll + " train=" + nTrain + " test=" + (nAll - nTrain) + " skipped=" + skipped);
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
                if (sb.length() > 6000) break;
            }
            return sb.toString();
        }
        return "";
    }

    static String js(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
