import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;

/**
 * Starts the server and accepts clients.
 *
 *   ServerSocket.accept()  blocks until a client connects and returns a Socket.
 *   For every Socket a NEW Thread runs a ClientHandler, so many clients are served at once.
 *
 * Run:  java ChatServer [port] [webPort]   (defaults: 5000 for Swing clients, 8080 for phones/browsers)
 * Console commands while running:  users  |  exit
 *
 * OOP concepts shown here: Composition (ChatServer HAS-A ChatRoom and ChatLogger),
 * Multithreading, Exception handling.
 */
public class ChatServer {

    public static final int DEFAULT_PORT = 5000;
    public static final int DEFAULT_WEB_PORT = 8080;

    private final int port;
    private final int webPort;
    private final ChatLogger logger;
    private final ChatRoom room;

    private ServerSocket serverSocket;
    private WebServer webServer;
    private volatile boolean running = false;

    public ChatServer(int port, int webPort) {
        this.port = port;
        this.webPort = webPort;
        this.logger = new ChatLogger();
        this.room = new ChatRoom(logger);
    }

    /** Opens the port and keeps accepting clients until stop() is called. */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);   // throws IOException if the port is busy
        running = true;

        logger.logEvent("Server started on port " + port);
        System.out.println("=== Chat server started ===");
        startWebServer();
        System.out.println("Chat port : " + port + "   (Swing clients: java ChatGUI)");
        printAddresses();
        System.out.println("Log file  : " + logger.getLogFile().getPath());
        System.out.println("Type 'users' to list online users, 'exit' to stop.");

        startConsoleListener();

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                Thread thread = new Thread(new ClientHandler(clientSocket, room),
                        "client-" + clientSocket.getRemoteSocketAddress());
                thread.start();                   // one thread per client
            } catch (SocketException e) {
                if (running) {                    // a real error, not just stop() closing the socket
                    logger.logEvent("Accept failed: " + e.getMessage());
                }
            } catch (IOException e) {
                if (running) {
                    logger.logEvent("Accept failed: " + e.getMessage());
                    System.err.println("Could not accept a client: " + e.getMessage());
                }
            }
        }
    }

    /** Stops accepting clients and disconnects everybody. Safe to call twice. */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (webServer != null) {
            webServer.stop();
        }
        room.shutdown("Server is shutting down.");
        try {
            serverSocket.close();                 // makes accept() throw, ending the loop
        } catch (IOException ignored) {
            // already closing
        }
        logger.logEvent("Server stopped");
        System.out.println("Server stopped.");
    }

    /** Small daemon thread that reads commands typed into the server console. */
    private void startConsoleListener() {
        Thread console = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim().toLowerCase();
                        if (line.equals("exit") || line.equals("quit")) {
                            stop();
                            return;
                        } else if (line.equals("users")) {
                            System.out.println("Online (" + room.getUserCount() + "): " + room.getOnlineUsernames());
                        }
                    }
                } catch (IOException ignored) {
                    // console closed
                }
            }
        }, "server-console");
        console.setDaemon(true);
        console.start();
    }

    /** Starts the phone/browser side. A failure here must not stop the normal chat server. */
    private void startWebServer() {
        try {
            webServer = new WebServer(webPort, room);
            webServer.start();
            logger.logEvent("Web server started on port " + webPort);
        } catch (IOException e) {
            webServer = null;
            System.err.println("Web server NOT started on port " + webPort + ": " + e.getMessage());
        }
    }

    /** Prints every address other devices on the same Wi-Fi / LAN can use. */
    private void printAddresses() {
        System.out.println("Open on THIS computer  : http://localhost:" + webPort);
        System.out.println("Open on phones / other devices (same Wi-Fi):");
        boolean found = false;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        found = true;
                        System.out.println("      http://" + addr.getHostAddress() + ":" + webPort
                                + "      (Swing server IP: " + addr.getHostAddress() + ", port " + port + ")");
                    }
                }
            }
        } catch (IOException e) {
            // fall through to the message below
        }
        if (!found) {
            System.out.println("      (could not detect the IP address - run ipconfig / ifconfig)");
        }
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (port < 1 || port > 65535) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid port '" + args[0] + "'. Use a number between 1 and 65535.");
                return;
            }
        }

        int webPort = DEFAULT_WEB_PORT;
        if (args.length > 1) {
            try {
                webPort = Integer.parseInt(args[1]);
                if (webPort < 1 || webPort > 65535 || webPort == port) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid web port '" + args[1] + "'. Use a different number between 1 and 65535.");
                return;
            }
        }

        final ChatServer server = new ChatServer(port, webPort);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                server.stop();                    // Ctrl+C also shuts down cleanly
            }
        }));

        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Cannot start server on port " + port + ": " + e.getMessage());
            System.err.println("Is another server already running on that port?");
        }
    }
}
