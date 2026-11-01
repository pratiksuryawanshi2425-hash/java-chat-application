import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared "room" that all clients talk through. There is exactly one ChatRoom
 * per server and it is used by many threads at once, so the methods that touch the
 * participant list are synchronized (thread safety).
 *
 * OOP concepts shown here:
 *  - Composition : a ChatRoom HAS-A ChatLogger and owns its Participants (they cannot
 *                  exist without the room).
 *  - Association : a Participant links a User object with a MessageHandler object.
 *  - Abstraction : the room only sees the MessageHandler interface, never the socket.
 */
public class ChatRoom {

    /** Private helper class: pairs a user with the handler used to reach that user. */
    private static class Participant {
        final User user;
        final MessageHandler handler;

        Participant(User user, MessageHandler handler) {
            this.user = user;
            this.handler = handler;
        }
    }

    // key = lower-case username, so "Ali" and "ali" count as duplicates
    private final Map<String, Participant> participants = new LinkedHashMap<String, Participant>();
    private final ChatLogger logger;

    public ChatRoom(ChatLogger logger) {
        this.logger = logger;
    }

    /**
     * Adds a user to the room.
     *
     * @return false if that username is already taken (nothing is changed in that case)
     */
    public synchronized boolean addUser(User user, MessageHandler handler) {
        String key = user.getUsername().toLowerCase();
        if (participants.containsKey(key)) {
            return false;
        }
        participants.put(key, new Participant(user, handler));
        return true;
    }

    /** @return true if the user was really in the room and has now been removed */
    public synchronized boolean removeUser(String username) {
        return participants.remove(username.toLowerCase()) != null;
    }

    public synchronized int getUserCount() {
        return participants.size();
    }

    /** Sorted list of the usernames that are online right now. */
    public synchronized List<String> getOnlineUsernames() {
        List<String> names = new ArrayList<String>();
        for (Participant p : participants.values()) {
            names.add(p.user.getUsername());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Logs the message and sends it to every connected client (polymorphic call). */
    public void broadcast(Message message) {
        logger.logMessage(message);
        for (MessageHandler handler : snapshotHandlers()) {
            try {
                handler.handleMessage(message);
            } catch (RuntimeException e) {
                logger.logEvent("Could not deliver a message to one client: " + e.getMessage());
            }
        }
    }

    /** Sends a system notice such as "Ali joined the chat". */
    public void announce(String text) {
        broadcast(Message.system(text));
    }

    /** Sends the up-to-date list of online users to everybody. */
    public void broadcastUserList() {
        List<String> names = getOnlineUsernames();
        for (MessageHandler handler : snapshotHandlers()) {
            try {
                handler.handleUserList(names);
            } catch (RuntimeException e) {
                logger.logEvent("Could not send user list to one client: " + e.getMessage());
            }
        }
    }

    public void logEvent(String event) {
        logger.logEvent(event);
    }

    /** Tells every client that the server is stopping and empties the room. */
    public void shutdown(String reason) {
        List<MessageHandler> handlers = snapshotHandlers();
        synchronized (this) {
            participants.clear();
        }
        for (MessageHandler handler : handlers) {
            handler.handleDisconnect(reason);
        }
    }

    /**
     * Copies the handlers while holding the lock, so the (slow) network writes
     * happen OUTSIDE the lock and one slow client cannot block the others.
     */
    private synchronized List<MessageHandler> snapshotHandlers() {
        List<MessageHandler> list = new ArrayList<MessageHandler>();
        for (Participant p : participants.values()) {
            list.add(p.handler);
        }
        return list;
    }
}
