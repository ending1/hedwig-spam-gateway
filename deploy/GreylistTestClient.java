import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class GreylistTestClient {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

            readMultiline(in); // banner
            send(out, "EHLO greylist-test");
            readMultiline(in);
            send(out, "MAIL FROM:<sender@greylist-test.example>");
            readMultiline(in);
            send(out, "RCPT TO:<victim@handysoft.co.kr>");
            String rcptResponse = readMultiline(in);
            System.out.println("VERDICT: " + (rcptResponse.startsWith("450") ? "DEFER" : rcptResponse.startsWith("250") ? "ALLOW" : "OTHER:" + rcptResponse));
            send(out, "QUIT");
            try { readMultiline(in); } catch (Exception ignored) {}
        }
    }

    // 마지막 라인("250 ..." - 대시 없음)까지 읽고 그 마지막 라인을 반환
    private static String readMultiline(BufferedReader in) throws IOException {
        String line;
        String last = null;
        while ((line = in.readLine()) != null) {
            last = line;
            if (line.length() < 4 || line.charAt(3) != '-') {
                break;
            }
        }
        return last;
    }

    private static void send(PrintWriter out, String line) {
        out.print(line + "\r\n");
        out.flush();
    }
}
