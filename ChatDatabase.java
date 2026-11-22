import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Saves the chat history in a MySQL database using JDBC.
 *
 * Settings (URL, user, password) are read from the file db.properties.
 * The table "messages" is created automatically the first time the server runs.
 *
 *      messages(id, sender, content, type, sent_at)
 *
 * JDBC steps used here: DriverManager.getConnection -> PreparedStatement -> executeUpdate / executeQuery -> ResultSet.
 *
 * If the database cannot be reached the chat still works (the .txt log file is still written),
 * the history is just not saved to the database.
 */
public class ChatDatabase {

    private static final String CONFIG_FILE = "db.properties";

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS messages ("
          + " id INT AUTO_INCREMENT PRIMARY KEY,"
          + " sender VARCHAR(50) NOT NULL,"
          + " content TEXT NOT NULL,"
          + " type VARCHAR(10) NOT NULL,"
          + " sent_at DATETIME NOT NULL"
          + ") DEFAULT CHARSET=utf8mb4";

    private static final String INSERT_MESSAGE =
            "INSERT INTO messages (sender, content, type, sent_at) VALUES (?, ?, ?, ?)";

    private static final String SELECT_RECENT =
            "SELECT sender, content, type, sent_at FROM messages ORDER BY id DESC LIMIT ?";

    private final String url;
    private final String user;
    private final String password;

    private Connection connection;
    private boolean enabled = false;          // true once connect() has succeeded
    private boolean errorReported = false;

    public ChatDatabase() {
        Properties settings = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_FILE)) {
            settings.load(in);
        } catch (IOException e) {
            System.err.println("[Database] Could not read " + CONFIG_FILE + ", using default settings.");
        }
        this.url = settings.getProperty("db.url",
                "jdbc:mysql://localhost:3306/chat_app?createDatabaseIfNotExist=true");
        this.user = settings.getProperty("db.user", "root");
        this.password = settings.getProperty("db.password", "");
    }

    /** Connects and creates the table. @return true if the database is ready to use */
    public synchronized boolean connect() {
        try {
            connection = DriverManager.getConnection(url, user, password);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(CREATE_TABLE);
            }
            enabled = true;
            return true;
        } catch (SQLException e) {
            connection = null;
            System.err.println("[Database] Not connected - chat history will NOT be saved to MySQL: " + e.getMessage());
            return false;
        }
    }

    /** Saves one chat message or system notice. Never throws, so a database problem cannot stop the chat. */
    public synchronized void saveMessage(Message message) {
        try {
            if (!isConnected()) {
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement(INSERT_MESSAGE)) {
                statement.setString(1, message.getSender());
                statement.setString(2, message.getContent());
                statement.setString(3, message.getType().name());
                statement.setTimestamp(4, Timestamp.valueOf(message.getTimestamp()));
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            reportOnce("Cannot save message: " + e.getMessage());
        }
    }

    /** @return the newest messages, oldest first (empty list if the database is not available) */
    public synchronized List<Message> getRecentMessages(int limit) {
        List<Message> messages = new ArrayList<Message>();
        try {
            if (!isConnected()) {
                return messages;
            }
            try (PreparedStatement statement = connection.prepareStatement(SELECT_RECENT)) {
                statement.setInt(1, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        messages.add(new Message(
                                rows.getString("sender"),
                                rows.getString("content"),
                                Message.Type.valueOf(rows.getString("type")),
                                rows.getTimestamp("sent_at").toLocalDateTime()));
                    }
                }
            }
        } catch (SQLException e) {
            reportOnce("Cannot read history: " + e.getMessage());
        }
        Collections.reverse(messages);   // the query returned newest first
        return messages;
    }

    public synchronized void close() {
        enabled = false;
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // already closing
            }
            connection = null;
        }
    }

    /** Opens the connection again if MySQL closed it (for example after a long idle time). */
    private boolean isConnected() throws SQLException {
        if (!enabled) {
            return false;                       // connect() failed at start-up, do not keep retrying
        }
        if (connection == null || !connection.isValid(2)) {
            connection = DriverManager.getConnection(url, user, password);
        }
        return true;
    }

    private void reportOnce(String text) {
        if (!errorReported) {
            errorReported = true;
            System.err.println("[Database] " + text);
        }
    }
}
