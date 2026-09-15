import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class BlacklistDemoClient {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            readMultiline(in);
            send(out, "EHLO demo-client"); readMultiline(in);
            send(out, "MAIL FROM:<attacker@known-spammer.example>"); readMultiline(in);
            send(out, "RCPT TO:<demo-target2@handysoft.co.kr>");
            System.out.println("RCPT TO << " + readMultiline(in));
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
