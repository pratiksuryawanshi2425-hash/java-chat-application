import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * Represents one person connected to the chat.
 *
 * OOP concepts shown here:
 *  - Class / Object : every connected client is represented by one User object.
 *  - Encapsulation  : all fields are private and final; they are exposed only
 *                     through getters, so a User can never be modified after creation.
 */
public class User {

    public static final int MAX_USERNAME_LENGTH = 20;

    private static final Pattern VALID_NAME =
            Pattern.compile("[A-Za-z0-9_-]{1," + MAX_USERNAME_LENGTH + "}");
    private static final String RESERVED_NAME = "server";

    private final String username;
    private final String ipAddress;
    private final LocalDateTime joinedAt;

    public User(String username, String ipAddress) {
        String problem = describeUsernameProblem(username);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        this.username = username;
        this.ipAddress = ipAddress;
        this.joinedAt = LocalDateTime.now();
    }

    /**
     * Validates a username.
     *
     * @return null if the username is acceptable, otherwise a human-readable reason.
     */
    public static String describeUsernameProblem(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Username cannot be empty.";
        }
        if (name.length() > MAX_USERNAME_LENGTH) {
            return "Username must be at most " + MAX_USERNAME_LENGTH + " characters.";
        }
        if (!VALID_NAME.matcher(name).matches()) {
            return "Username may only contain letters, digits, '_' and '-' (no spaces).";
        }
        if (name.equalsIgnoreCase(RESERVED_NAME)) {
            return "The name 'SERVER' is reserved.";
        }
        return null;
    }

    public static boolean isValidUsername(String name) {
        return describeUsernameProblem(name) == null;
    }

    public String getUsername() {
        return username;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    /** Usernames are compared case-insensitively ("Ali" and "ali" are the same user). */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User)) return false;
        return username.equalsIgnoreCase(((User) o).username);
    }

    @Override
    public int hashCode() {
        return username.toLowerCase().hashCode();
    }

    @Override
    public String toString() {
        return username + " (" + ipAddress + ")";
    }
}
