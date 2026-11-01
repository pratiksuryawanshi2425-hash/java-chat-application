import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Saves chat history and session events to a text file inside the chat_logs folder.
 * One new file is created every time the server starts, e.g.
 *      chat_logs/chat_log_2026-10-06_14-30-00.txt
 *
 * File handling concepts: File, BufferedWriter, append mode, try-with-resources, IOException.
 * All methods are synchronized because many ClientHandler threads write at the same time.
 */
public class ChatLogger {

    private static final String LOG_DIRECTORY = "chat_logs";
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter LINE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File logFile;
    private boolean errorReported = false;

    public ChatLogger() {
        File dir = new File(LOG_DIRECTORY);
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("[Logger] Could not create folder: " + dir.getAbsolutePath());
        }
        String name = "chat_log_" + LocalDateTime.now().format(FILE_STAMP) + ".txt";
        this.logFile = new File(dir, name);
    }

    public File getLogFile() {
        return logFile;
    }

    /** Saves a chat message or a join/leave notice. */
    public synchronized void logMessage(Message message) {
        if (message.getType() == Message.Type.SYSTEM) {
            write("[" + message.getTimestamp().format(LINE_STAMP) + "] [EVENT] " + message.getContent());
        } else {
            write(message.toDisplayString(true));
        }
    }

    /** Saves a session event such as "Server started" or "Connection reset". */
    public synchronized void logEvent(String event) {
        write("[" + LocalDateTime.now().format(LINE_STAMP) + "] [EVENT] " + event);
    }

    private void write(String line) {
        // Open in append mode, write one line, close automatically (try-with-resources).
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            // Logging problems must never crash the chat, so report once and carry on.
            if (!errorReported) {
                errorReported = true;
                System.err.println("[Logger] Cannot write to log file: " + e.getMessage());
            }
        }
    }
}
