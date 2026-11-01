import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * The Swing chat window (client application). Start it with:  java ChatGUI
 *
 * OOP concepts shown here:
 *  - Inheritance    : ChatGUI extends JFrame.
 *  - Interface      : ChatGUI implements MessageHandler, so ChatClient can deliver
 *                     events to it (polymorphism).
 *  - Association    : ChatGUI uses a ChatClient object to talk to the server.
 *  - Encapsulation  : all components are private fields.
 *
 * Threading rule of Swing: components may only be changed on the Event Dispatch
 * Thread (EDT). Network callbacks arrive on another thread, so they use
 * SwingUtilities.invokeLater(...). Connecting uses a SwingWorker so the window
 * never freezes.
 */
public class ChatGUI extends JFrame implements MessageHandler {

    private static final long serialVersionUID = 1L;
    private static final Color GREEN = new Color(0, 130, 0);
    private static final Color RED = new Color(190, 0, 0);
    private static final Color ORANGE = new Color(210, 120, 0);

    // connection panel
    private final JTextField hostField = new JTextField("localhost", 10);
    private final JTextField portField = new JTextField(String.valueOf(ChatServer.DEFAULT_PORT), 5);
    private final JTextField usernameField = new JTextField(12);
    private final JButton connectButton = new JButton("Connect");

    // chat area
    private final JTextArea chatArea = new JTextArea();
    private final JTextField messageField = new JTextField();
    private final JButton sendButton = new JButton("Send");

    // online users
    private final DefaultListModel<String> userListModel = new DefaultListModel<String>();
    private final JList<String> userList = new JList<String>(userListModel);
    private final JLabel usersTitle = new JLabel("Online Users (0)");

    // status bar
    private final JLabel statusLabel = new JLabel();

    private ChatClient client;      // null while not connected

    public ChatGUI() {
        super("Java Chat Application");
        buildLayout();
        wireEvents();
        setConnected(false);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(760, 520);
        setMinimumSize(new Dimension(620, 400));
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------------
    //  Building the window
    // ------------------------------------------------------------------
    private void buildLayout() {
        // top: connection settings
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        top.add(new JLabel("Server:"));
        top.add(hostField);
        top.add(new JLabel("Port:"));
        top.add(portField);
        top.add(new JLabel("Username:"));
        top.add(usernameField);
        top.add(connectButton);

        // centre: chat history
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        chatArea.setMargin(new java.awt.Insets(6, 6, 6, 6));
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setBorder(BorderFactory.createTitledBorder("Chat"));

        // right: online users
        usersTitle.setFont(usersTitle.getFont().deriveFont(Font.BOLD));
        JPanel usersPanel = new JPanel(new BorderLayout(0, 4));
        usersPanel.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 8));
        usersPanel.add(usersTitle, BorderLayout.NORTH);
        usersPanel.add(new JScrollPane(userList), BorderLayout.CENTER);
        usersPanel.setPreferredSize(new Dimension(170, 0));

        // bottom: message box + Send button, then status line
        JPanel inputPanel = new JPanel(new BorderLayout(6, 0));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        inputPanel.add(messageField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createEmptyBorder(2, 10, 6, 10));
        statusPanel.add(statusLabel, BorderLayout.WEST);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(inputPanel, BorderLayout.CENTER);
        bottom.add(statusPanel, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(chatScroll, BorderLayout.CENTER);
        add(usersPanel, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
    }

    private void wireEvents() {
        connectButton.addActionListener(e -> {
            if (client != null && client.isConnected()) {
                disconnect();
            } else {
                connect();
            }
        });
        usernameField.addActionListener(e -> connectButton.doClick());   // Enter in username box
        sendButton.addActionListener(e -> sendMessage());
        messageField.addActionListener(e -> sendMessage());              // Enter in message box

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (client != null) {
                    client.disconnect();         // tell the server we are leaving
                }
            }
        });
    }

    // ------------------------------------------------------------------
    //  User actions
    // ------------------------------------------------------------------
    private void connect() {
        String host = hostField.getText().trim();
        String username = usernameField.getText().trim();

        String problem = User.describeUsernameProblem(username);   // rejects empty names
        if (problem != null) {
            warn(problem);
            usernameField.requestFocus();
            return;
        }
        if (host.isEmpty()) {
            warn("Please enter the server address.");
            return;
        }
        final int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            warn("Port must be a number between 1 and 65535.");
            return;
        }

        setBusy("Connecting to " + host + ":" + port + " ...");
        final ChatClient newClient = new ChatClient(host, port, username, this);

        // Connect on a background thread so the window stays responsive.
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                newClient.connect();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    if (newClient.isConnected()) {
                        client = newClient;
                        chatArea.setText("");
                        appendLine("--- Connected ---");
                        setConnected(true);
                        messageField.requestFocus();
                    } else {
                        setConnected(false);      // connection dropped immediately after joining
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    setConnected(false);
                } catch (ExecutionException e) {
                    setConnected(false);
                    Throwable cause = e.getCause();
                    if (cause instanceof ChatClient.JoinRejectedException) {
                        warn(cause.getMessage());
                        usernameField.requestFocus();
                    } else {
                        error("Could not connect to the server.\n\n"
                                + "Check that the server is running and that the address and port are correct.\n"
                                + "Details: " + cause.getMessage());
                    }
                }
            }
        }.execute();
    }

    private void disconnect() {
        if (client != null) {
            client.disconnect();
            client = null;
        }
        appendLine("--- You disconnected ---");
        setConnected(false);
    }

    private void sendMessage() {
        String text = messageField.getText().trim();
        if (text.isEmpty() || client == null) {
            return;                                   // empty messages are never sent
        }
        try {
            client.sendMessage(text);
            messageField.setText("");
        } catch (IOException e) {
            error("Message could not be sent: " + e.getMessage());
            client.disconnect();
            handleDisconnect("Connection lost.");
        }
    }

    // ------------------------------------------------------------------
    //  MessageHandler implementation: events coming FROM the network
    //  (called on a background thread -> always hop onto the EDT)
    // ------------------------------------------------------------------
    @Override
    public void handleMessage(final Message message) {
        SwingUtilities.invokeLater(() -> appendLine(message.toDisplayString(false)));
    }

    @Override
    public void handleUserList(final List<String> usernames) {
        SwingUtilities.invokeLater(() -> {
            userListModel.clear();
            for (String name : usernames) {
                userListModel.addElement(name);
            }
            usersTitle.setText("Online Users (" + usernames.size() + ")");
        });
    }

    @Override
    public void handleDisconnect(final String reason) {
        SwingUtilities.invokeLater(() -> {
            client = null;
            appendLine("--- Disconnected: " + reason + " ---");
            setConnected(false);
        });
    }

    // ------------------------------------------------------------------
    //  GUI state helpers (EDT only)
    // ------------------------------------------------------------------
    private void setConnected(boolean connected) {
        hostField.setEnabled(!connected);
        portField.setEnabled(!connected);
        usernameField.setEnabled(!connected);
        connectButton.setEnabled(true);
        connectButton.setText(connected ? "Disconnect" : "Connect");
        messageField.setEnabled(connected);
        sendButton.setEnabled(connected);

        if (connected) {
            statusLabel.setForeground(GREEN);
            statusLabel.setText("\u25CF Connected as " + usernameField.getText().trim()
                    + " to " + hostField.getText().trim() + ":" + portField.getText().trim());
        } else {
            statusLabel.setForeground(RED);
            statusLabel.setText("\u25CF Disconnected");
            userListModel.clear();
            usersTitle.setText("Online Users (0)");
        }
    }

    private void setBusy(String text) {
        connectButton.setEnabled(false);
        statusLabel.setForeground(ORANGE);
        statusLabel.setText("\u25CF " + text);
    }

    private void appendLine(String line) {
        chatArea.append(line + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());   // auto-scroll
    }

    private void warn(String text) {
        JOptionPane.showMessageDialog(this, text, "Cannot join", JOptionPane.WARNING_MESSAGE);
    }

    private void error(String text) {
        JOptionPane.showMessageDialog(this, text, "Connection error", JOptionPane.ERROR_MESSAGE);
    }

    // ------------------------------------------------------------------
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // the default look and feel is fine too
        }
        SwingUtilities.invokeLater(() -> new ChatGUI().setVisible(true));
    }
}
