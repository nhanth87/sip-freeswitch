package et.elisa.esl;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Small synchronous ESL client. Every call is bounded and returns a soft failure when FS is unavailable. */
@ApplicationScoped
public class EslClient {
    @ConfigProperty(name = "sf.esl.host") String host;
    @ConfigProperty(name = "sf.esl.port") int port;
    @ConfigProperty(name = "sf.esl.password") String password;
    private volatile boolean live;
    public boolean live() { return live; }
    public synchronized String api(String command) { return execute("api " + command); }
    public synchronized String bgapi(String command) { return execute("bgapi " + command); }
    private String execute(String command) {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(2_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            readHeaders(in); out.write("auth " + password + "\n\n"); out.flush();
            String auth = readHeaders(in);
            if (!auth.contains("+OK")) throw new IOException("ESL authentication failed");
            out.write(command + "\n\n"); out.flush(); live = true;
            return readHeaders(in);
        } catch (IOException ex) { live = false; return "-ERR ESL unavailable: " + ex.getMessage(); }
    }
    private static String readHeaders(BufferedReader in) throws IOException {
        StringBuilder value = new StringBuilder(); String line;
        while ((line = in.readLine()) != null) { value.append(line).append('\n'); if (line.isEmpty()) break; }
        return value.toString();
    }
}
