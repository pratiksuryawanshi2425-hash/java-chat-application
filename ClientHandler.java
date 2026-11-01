import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serves ONE connected client. ChatServer starts a new Thread for every accepted
 * socket, and that thread runs this class's run() method.
 *
 * Protocol (one text line each):
 *   client -> server :  JOIN|username     MSG|text     QUIT
 *   server -> client :  OK     ERROR|reason     MSG|TYPE|sender|time|text
 *                       USERS|a,b,c     DISCONNECT|reason
 *
 * OOP concepts shown here:
 *  - Multithreading : implements Runnable.
 *  - Interface / Polymorphism : implements MessageHandler, so ChatRoom can push
 *    events to this client without knowing anything about sockets.
 *  - Exception handling : IOException / SocketException handled, resources always closed.
 */
public class ClientHandler implements Runnable, MessageHandler {

    private static final int JOIN_TIMEOUT_MS = 30000;
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final Socket socket;
    private final ChatRoom room;
    private final Object sendLock = new Object();

    private BufferedReader in;
    private BufferedWriter out;
    private User user;          // null until the client has joined successfully
    private boolean closed = false;

    public ClientHandler(Socket socket, ChatRoom room) {
        this.socket = socket;
        this.room = room;
    }

    // ------------------------------------------------------------------
    //  Thread body
    // ------------------------------------------------------------------
    @Override
    public void run() {
        String who = socket.getRemoteSocketAddress().toString();
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            if (!performJoin()) {
                return;                      // rejected; finally-block closes the socket
            }

            String line;
            while ((line = in.readLine()) != null) {
                if (!processCommand(line)) {
                    break;                   // client sent QUIT
                }
            }
        } catch (SocketException e) {
            room.logEvent("Connection lost with " + describe(who) + ": " + e.getMessage());
        } catch (IOException e) {
            room.logEvent("I/O error with " + describe(who) + ": " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    /** Waits for the JOIN command, validates the username and registers the user. */
    private boolean performJoin() throws IOException {
        socket.setSoTimeout(JOIN_TIMEOUT_MS);       // don't wait forever for a silent client
        String line;
        try {
            line = in.readLine();
        } catch (java.net.SocketTimeoutException e) {
            room.logEvent("Client " + socket.getRemoteSocketAddress() + " did not send a username in time.");
            return false;
        }
        socket.setSoTimeout(0);

        if (line == null) {
            return false;
        }
        if (!line.startsWith("JOIN|")) {
            writeLine("ERROR|Protocol error: expected JOIN.");
            return false;
        }

        String name = line.substring(5).trim();
        String problem = User.describeUsernameProblem(name);
        if (problem != null) {
            writeLine("ERROR|" + problem);
            return false;
        }

        String ip = socket.getInetAddress().getHostAddress();
        User candidate = new User(name, ip);

        // The lock guarantees that "OK" is written BEFORE any broadcast from another thread
        // (broadcast -> handleMessage -> writeLine waits for the same lock).
        synchronized (sendLock) {
            if (!room.addUser(candidate, this)) {
                writeLine("ERROR|Username '" + name + "' is already taken. Choose another one.");
                room.logEvent("Rejected duplicate username '" + name + "' from " + ip);
                return false;
            }
            user = candidate;
            writeLine("OK");
        }

        room.logEvent("Connection accepted from " + ip + " as '" + name + "'");
        room.announce(name + " joined the chat");
        room.broadcastUserList();
        return true;
    }

    /** @return false when the client wants to leave */
    private boolean processCommand(String line) {
        if (line.equals("QUIT")) {
            return false;
        }
        if (line.startsWith("MSG|")) {
            String text = line.substring(4).trim();
            if (text.isEmpty()) {
                return true;                           // ignore empty messages
            }
            if (text.length() > MAX_MESSAGE_LENGTH) {
                text = text.substring(0, MAX_MESSAGE_LENGTH);
            }
            room.broadcast(new Message(user.getUsername(), text, Message.Type.CHAT));
        }
        return true;                                   // unknown commands are ignored
    }

    // ------------------------------------------------------------------
    //  MessageHandler implementation: events coming FROM the room TO this client
    // ------------------------------------------------------------------
    @Override
    public void handleMessage(Message message) {
        send(message.toProtocol());
    }

    @Override
    public void handleUserList(List<String> usernames) {
        send("USERS|" + String.join(",", usernames));
    }

    @Override
    public void handleDisconnect(String reason) {
        send("DISCONNECT|" + reason);
        closeSocket();
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------
    /** Sends a line; a failed send means the client is gone, so we close the socket. */
    private void send(String line) {
        try {
            writeLine(line);
        } catch (IOException e) {
            closeSocket();      // the reading loop will notice and run cleanup()
        }
    }

    private void writeLine(String line) throws IOException {
        synchronized (sendLock) {
            out.write(line);
            out.newLine();
            out.flush();
        }
    }

    /** Runs exactly once per client: removes the user, announces the leave, closes the socket. */
    private void cleanup() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        if (user != null && room.removeUser(user.getUsername())) {
            room.announce(user.getUsername() + " left the chat");
            room.broadcastUserList();
        }
        closeSocket();
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing more we can do
        }
    }

    private String describe(String fallback) {
        return user != null ? "'" + user.getUsername() + "'" : fallback;
    }
}
