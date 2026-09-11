import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class RblTestClient {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String bindIp = args[1];
        InetAddress local = InetAddress.getByName(bindIp);
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(5000);
            socket.bind(new InetSocketAddress(local, 0));
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            } catch (IOException e) {
                System.out.println("CONNECT_FAILED: " + e.getMessage());
                return;
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) {
                System.out.println("RESULT: connection closed immediately (no banner) - likely RBL/ban blocked");
            } else {
                System.out.println("RESULT: got banner -> " + line);
            }
        }
    }
}
