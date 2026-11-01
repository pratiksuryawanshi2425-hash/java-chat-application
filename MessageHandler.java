import java.util.List;

/**
 * Abstraction for "something that can receive chat events".
 *
 * OOP concepts shown here:
 *  - Interface     : defines WHAT must be done, not HOW.
 *  - Polymorphism  : two completely different classes implement it:
 *        ClientHandler (server side)  -> forwards the event over a socket to a remote client
 *        ChatGUI       (client side)  -> displays the event in a Swing window
 *    ChatRoom and ChatClient only know about "a MessageHandler", so they work with either one.
 */
public interface MessageHandler {

    /** A chat message or a system notice (join / leave) has arrived. */
    void handleMessage(Message message);

    /** The list of online usernames has changed. */
    void handleUserList(List<String> usernames);

    /** The connection is being closed (server stopped, connection lost, ...). */
    void handleDisconnect(String reason);
}
