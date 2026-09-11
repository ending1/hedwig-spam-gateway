import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class RuleFilterTestClient {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

            readMultiline(in);
            send(out, "EHLO rule-test"); readMultiline(in);
            send(out, "MAIL FROM:<lucky-winner@totally-legit-prize.biz>"); readMultiline(in);
            send(out, "RCPT TO:<victim@handysoft.co.kr>"); readMultiline(in);
            send(out, "DATA"); readMultiline(in);
            send(out, "Subject: URGENT!!! ACT NOW - CLAIM YOUR PRIZE!!!");
            send(out, "From: lucky-winner@totally-legit-prize.biz");
            send(out, "To: victim@handysoft.co.kr");
            send(out, "");
            send(out, "You have won free money! wire transfer nigerian prince, act now, 100% free, no obligation, risk free, click here!");
            send(out, ".");
            System.out.println("<< " + readMultiline(in));
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
