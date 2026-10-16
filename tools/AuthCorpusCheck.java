import com.hs.mail.gateway.auth.AuthChecker;
import com.hs.mail.gateway.auth.AuthResult;
import com.hs.mail.gateway.auth.DnsJavaLookup;
import com.hs.mail.gateway.config.GatewayProperties;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 개발용 도구(운영 코드 아님): EML 폴더의 메일에 SPF/DKIM/DMARC 검증을 돌려 결과 분포를 본다.
 * 실제 DNS를 조회하므로 DKIM 검증기가 실제 서명에서 맞게 동작하는지(정규화/키 해석) 확인하는 용도다.
 * SPF는 원래 접속 IP가 필요한데 EML에는 마지막 Received 헤더의 IP를 근사값으로 쓴다.
 *
 * 사용: java -cp target/classes;(의존 jar) AuthCorpusCheck <폴더> [표본 수] [seed]
 * 사내 메일 원본은 저장소에 두지 않는다(결과 통계만 본다).
 */
public class AuthCorpusCheck {

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        int sample = a.length > 1 ? Integer.parseInt(a[1]) : 200;
        long seed = a.length > 2 ? Long.parseLong(a[2]) : 1L;
        List<File> files = new ArrayList<>();
        File[] list = dir.listFiles();
        if (list == null) {
            System.err.println("폴더를 읽을 수 없음: " + dir);
            return;
        }
        for (File f : list) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".eml")) {
                files.add(f);
            }
        }
        Collections.shuffle(files, new Random(seed));
        GatewayProperties props = new GatewayProperties();
        props.getAuth().setTimeoutMillis(15000);
        AuthChecker checker = new AuthChecker(new DnsJavaLookup(props), props);

        Map<String, Integer> dkim = new LinkedHashMap<>(), spf = new LinkedHashMap<>(), dmarc = new LinkedHashMap<>();
        int signed = 0, n = 0;
        List<String> dkimFails = new ArrayList<>();
        for (File f : files) {
            if (n >= sample) {
                break;
            }
            byte[] raw = Files.readAllBytes(f.toPath());
            String text = new String(raw, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(java.util.Arrays.asList(text.split("\r\n|\n", -1)));
            if (lines.size() > 0 && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            String mailFrom = null, ip = "203.0.113.1";
            for (String l : lines) {
                if (l.isEmpty()) {
                    break;
                }
                String lower = l.toLowerCase();
                if (lower.startsWith("return-path:")) {
                    mailFrom = l.substring(12).replaceAll("[<>\\s]", "");
                }
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(\\d+\\.\\d+\\.\\d+\\.\\d+)\\]").matcher(text.substring(0, Math.min(text.length(), 6000)));
            if (m.find()) {
                ip = m.group(1);
            }
            AuthResult r = checker.check(ip, "", mailFrom, lines);
            n++;
            inc(dkim, r.dkim);
            inc(spf, r.spf);
            inc(dmarc, r.dmarc);
            boolean hasSig = text.substring(0, Math.min(text.length(), 20000)).toLowerCase().contains("\ndkim-signature:");
            if (hasSig) {
                signed++;
                if (!"pass".equals(r.dkim) && dkimFails.size() < 15) {
                    com.hs.mail.gateway.auth.DkimVerifier.Result dr = new com.hs.mail.gateway.auth.DkimVerifier().verify(lines, new com.hs.mail.gateway.auth.DnsJavaLookup(props)); dkimFails.add(f.getName() + " dkim=" + r.dkim + " from=" + r.fromDomain + " :: " + dr.detail);
                }
            }
        }
        System.out.println("검사 " + n + "건, DKIM 서명이 있는 메일 " + signed + "건");
        System.out.println("DKIM  " + dkim);
        System.out.println("SPF   " + spf + "  (IP는 Received 근사값이라 참고용)");
        System.out.println("DMARC " + dmarc);
        System.out.println("서명이 있는데 pass가 아닌 예(최대 15):");
        for (String s : dkimFails) {
            System.out.println("  " + s);
        }
    }

    private static void inc(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        m.put(k, v == null ? 1 : v + 1);
    }
}
