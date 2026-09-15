import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class PhishingDemoClient {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

            System.out.println("BANNER << " + readMultiline(in));
            send(out, "EHLO demo-client");
            System.out.println("EHLO << " + readMultiline(in));
            send(out, "MAIL FROM:<no-reply@slack.com>");
            System.out.println("MAIL FROM << " + readMultiline(in));
            send(out, "RCPT TO:<demo-target@handysoft.co.kr>");
            String rcptResp = readMultiline(in);
            System.out.println("RCPT TO << " + rcptResp);
            if (rcptResp == null || !rcptResp.startsWith("250")) {
                System.out.println("RCPT이 실패해 DATA를 진행하지 않음(그레이리스팅 대기중 등 정상 동작)");
                send(out, "QUIT");
                return;
            }
            send(out, "DATA");
            System.out.println("DATA << " + readMultiline(in));
            send(out, "Subject: OO님이 언급했습니다");
            send(out, "From: Slack <no-reply@slack.com>");
            send(out, "To: demo-target@handysoft.co.kr");
            send(out, "");
            send(out, "문의사항은 NadineEmerie6061@outlook.com 으로 연락 주세요.");
            send(out, ".");
            System.out.println("FINAL << " + readMultiline(in));
            send(out, "QUIT");
        }
    }

    private static String readMultiline(BufferedReader in) throws IOException {
        String line, last = null;
        while ((line = in.readLine()) != null) {
            last = line;
            if (line.length() < 4 || line.charAt(3) != '-') break;
        }
        return last;
    }

    private static void send(PrintWriter out, String line) {
        out.print(line + "\r\n");
        out.flush();
    }
}
