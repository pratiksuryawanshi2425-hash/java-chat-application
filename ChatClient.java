import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Network side of the client: connects to the server, sends messages, and listens
 * for incoming data on a background thread.
 *
 * It does NOT know anything about Swing. Everything it receives is passed to a
 * MessageHandler (the GUI), which is an example of abstraction and polymorphism:
 * the same ChatClient could also work with a console-based handler.
 *
 * Composition: ChatClient HAS-A Socket, reader, writer and listener.
 */
public class ChatClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int HANDSHAKE_TIMEOUT_MS = 5000;

    /** Thrown when the server refuses the username (empty, invalid or already taken). */
    public static class JoinRejectedException extends Exception {
        private static final long serialVersionUID = 1L;

        public JoinRejectedException(String reason) {
            super(reason);
        }
    }

    private final String host;
    private final int port;
    private final String username;
    private final MessageHandler listener;
    private final Object writeLock = new Object();

    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;
    private volatile boolean connected = false;
    private volatile boolean intentionalDisconnect = false;

    public ChatClient(String host, int port, String username, MessageHandler listener) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.listener = listener;
    }

    /**
     * Connects, sends the username and waits for the server's answer.
     * Blocks, so call it from a background thread (the GUI uses a SwingWorker).
     *
     * @throws IOException             server unreachable / connection problem
     * @throws JoinRejectedException   server refused the username
     */
    public void connect() throws IOException, JoinRejectedException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

            socket = s;
            writeLine("JOIN|" + username);

            String response = in.readLine();
            if (response == null) {
                throw new IOException("The server closed the connection.");
            }
            if (response.startsWith("ERROR|")) {
                throw new JoinRejectedException(response.substring(6));
            }
            if (!response.equals("OK")) {
                throw new IOException("Unexpected response from server: " + response);
            }
            s.setSoTimeout(0);                  // from now on, wait for messages indefinitely
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        } catch (JoinRejectedException e) {
            closeQuietly(s);
            throw e;
        }

        connected = true;
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                listen();
            }
        }, "client-listener");
        reader.setDaemon(true);
        reader.start();
    }

    /** Sends a chat message to the server. */
    public void sendMessage(String text) throws IOException {
        if (!connected) {
            throw new IOException("Not connected to the server.");
        }
        writeLine("MSG|" + text.replace('\n', ' ').replace('\r', ' '));
    }

    /** Leaves the chat politely (user clicked Disconnect or closed the window). */
    public void disconnect() {
        intentionalDisconnect = true;
        if (connected) {
            try {
                writeLine("QUIT");
            } catch (IOException ignored) {
                // server already gone; we are closing anyway
            }
        }
        connected = false;
        closeQuietly(socket);
    }

    public boolean isConnected() {
        return connected;
    }

    public String getUsername() {
        return username;
    }

    // ------------------------------------------------------------------
    //  Background thread: reads every line the server sends
    // ------------------------------------------------------------------
    private void listen() {
        String reason = "Connection to the server was lost.";
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("DISCONNECT|")) {
                    reason = line.substring(11);
                    break;
                }
                dispatch(line);
            }
        } catch (IOException e) {
            if (!intentionalDisconnect) {
                reason = "Connection lost: " + e.getMessage();
            }
        } finally {
            connected = false;
            closeQuietly(socket);
            if (!intentionalDisconnect) {
                listener.handleDisconnect(reason);
            }
        }
    }

    /** Turns a protocol line into a call on the MessageHandler. */
    private void dispatch(String line) {
        if (line.startsWith("MSG|")) {
            try {
                listener.handleMessage(Message.fromProtocol(line));
            } catch (IllegalArgumentException e) {
                System.err.println("Ignoring malformed message: " + e.getMessage());
            }
        } else if (line.startsWith("USERS|")) {
            String list = line.substring(6);
            List<String> names = new ArrayList<String>();
            if (!list.isEmpty()) {
                for (String name : list.split(",")) {
                    names.add(name);
                }
            }
            listener.handleUserList(names);
        }
    }

    private void writeLine(String line) throws IOException {
        synchronized (writeLock) {
            out.write(line);
            out.newLine();
            out.flush();
        }
    }

    private void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }
}
