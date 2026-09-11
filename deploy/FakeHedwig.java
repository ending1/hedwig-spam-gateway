import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class FakeHedwig {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        ServerSocket server = new ServerSocket(port);
        System.out.println("FakeHedwig listening on " + port);
        while (true) {
            Socket client = server.accept();
            new Thread(() -> handle(client)).start();
        }
    }

    private static void handle(Socket client) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8);
            out.print("220 fake-hedwig.local ESMTP ready\r\n"); out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                String upper = line.toUpperCase(Locale.US);
                System.out.println(">> " + line);
                if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                    out.print("250 fake-hedwig.local Hello\r\n");
                } else if (upper.startsWith("MAIL FROM")) {
                    out.print("250 2.1.0 OK\r\n");
                } else if (upper.startsWith("RCPT TO")) {
                    out.print("250 2.1.5 OK\r\n");
                } else if (upper.equals("DATA")) {
                    out.print("354 Start mail input; end with <CRLF>.<CRLF>\r\n");
                    out.flush();
                    System.out.println("---- DATA CONTENT ----");
                    String dl;
                    while ((dl = in.readLine()) != null && !".".equals(dl)) {
                        System.out.println("DATA| " + dl);
                    }
                    System.out.println("---- END DATA ----");
                    out.print("250 2.6.0 Queued\r\n");
                } else if (upper.startsWith("QUIT")) {
                    out.print("221 Bye\r\n");
                    out.flush();
                    break;
                } else {
                    out.print("250 OK\r\n");
                }
                out.flush();
            }
            client.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
