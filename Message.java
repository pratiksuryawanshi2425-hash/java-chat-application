import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Immutable value object holding one chat message: sender, text and timestamp.
 *
 * It also knows how to convert itself to / from the single-line text format
 * that is sent over the socket:
 *
 *      MSG|TYPE|sender|timestamp|content
 *
 * OOP concepts shown here: Encapsulation (private final fields), Class/Object.
 */
public class Message {

    public enum Type { CHAT, SYSTEM }

    public static final String SYSTEM_SENDER = "SERVER";

    private static final DateTimeFormatter TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String sender;
    private final String content;
    private final LocalDateTime timestamp;
    private final Type type;

    public Message(String sender, String content, Type type) {
        this(sender, content, type, LocalDateTime.now());
    }

    public Message(String sender, String content, Type type, LocalDateTime timestamp) {
        this.sender = sender;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp;
    }

    /** Convenience factory for join / leave / server notices. */
    public static Message system(String text) {
        return new Message(SYSTEM_SENDER, text, Type.SYSTEM);
    }

    public String getSender() { return sender; }
    public String getContent() { return content; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public Type getType() { return type; }

    /** Text shown in the chat window (time only) or written to the log file (with date). */
    public String toDisplayString(boolean includeDate) {
        String time = timestamp.format(includeDate ? DATE_TIME : TIME_ONLY);
        if (type == Type.SYSTEM) {
            return "[" + time + "] *** " + content + " ***";
        }
        return "[" + time + "] " + sender + ": " + content;
    }

    /** Converts this message into one line for sending through the socket. */
    public String toProtocol() {
        return "MSG|" + type + "|" + sender + "|"
                + timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "|" + content;
    }

    /**
     * Rebuilds a Message from a protocol line.
     *
     * @throws IllegalArgumentException if the line is malformed
     */
    public static Message fromProtocol(String line) {
        String[] parts = line.split("\\|", 5); // limit 5 => the content may itself contain '|'
        if (parts.length < 5 || !parts[0].equals("MSG")) {
            throw new IllegalArgumentException("Malformed message: " + line);
        }
        try {
            return new Message(parts[2], parts[4], Type.valueOf(parts[1]),
                    LocalDateTime.parse(parts[3], DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed message: " + line, e);
        }
    }

    @Override
    public String toString() {
        return toDisplayString(false);
    }
}
