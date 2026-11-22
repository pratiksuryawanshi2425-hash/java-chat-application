# Java Client-Server Chat Application

Swing GUI + `Socket`/`ServerSocket` + one thread per client + chat history saved to `chat_logs/` and to a **MySQL database (JDBC)**.
Requires **JDK 8 or newer** and a MySQL server. The only library is the MySQL JDBC driver in `lib/`.

## Project structure

```
ChatApplication/
├── User.java            ├── ClientHandler.java    ├── ChatGUI.java
├── Message.java         ├── ChatServer.java       ├── ChatLogger.java
├── MessageHandler.java  ├── ChatClient.java       ├── WebServer.java   (phones / browsers)
├── ChatRoom.java        ├── ChatDatabase.java     ├── db.properties   (MySQL settings)
├── web/index.html       ├── lib/mysql-connector-j-8.4.0.jar
└── chat_logs/           (the phone chat page; JDBC driver)
```

## How to run

Open a terminal **inside the `ChatApplication` folder** (so `chat_logs/` and `web/` are found).

1. **Set up MySQL** (once): make sure MySQL is running and put your MySQL user / password in `db.properties`.
   The database `chat_app` and its table `messages` are created automatically. If MySQL is not reachable the
   server prints a warning and the chat still works (only the database saving is skipped).
2. **Compile** everything once (use `;` instead of `:` on Windows):
   ```
   javac -cp ".:lib/*" *.java
   ```
3. **Start the server** on ONE computer:
   ```
   java -cp ".:lib/*" ChatServer                 # chat port 5000, web page port 8080
   java -cp ".:lib/*" ChatServer 6000 9000       # or choose both ports
   ```
   It prints the addresses other devices must use, for example `http://192.168.1.25:8080`.
   Type `users` to list who is online, `history` to show the last 20 messages saved in MySQL, `exit` (or Ctrl+C) to stop it.
4. **Laptop / desktop (Swing app)**: `java ChatGUI`, enter the server IP (or `localhost` on the server PC),
   port `5000`, a username, and click **Connect**. Open as many as you like.
5. **Phone / tablet / any browser**: connect it to the **same Wi-Fi** as the server computer and open
   `http://<server-ip>:8080` (the address printed by the server). Type a username and tap **Join**.
   Nothing needs to be installed on the phone. Phones and laptops chat in the same room.
6. **Firewall**: if another device cannot connect, allow Java (or ports 5000 and 8080) through the
   server computer's firewall. Windows: *Windows Defender Firewall -> Allow an app -> Java*.
7. **Chat history**: look in `chat_logs/` (one `chat_log_<date>_<time>.txt` per server start) or query MySQL:
   `SELECT * FROM messages;`

Username rules: 1–20 characters, letters / digits / `_` / `-` only, not empty, not already taken
(case-insensitive), and not `SERVER`.

## Classes

| Class | Role |
|---|---|
| `User` | Username, IP address and join time. Validates usernames. Immutable. |
| `Message` | Sender, content, timestamp and type (CHAT or SYSTEM). Converts itself to/from one line of text for the network. |
| `MessageHandler` | Interface with `handleMessage`, `handleUserList`, `handleDisconnect`. |
| `ChatRoom` | The shared room: keeps online users, rejects duplicates, broadcasts messages and user lists, logs everything. |
| `ClientHandler` | `Runnable` + `MessageHandler`. Serves one client on its own thread. |
| `ChatServer` | Opens the `ServerSocket`, accepts clients, starts one thread per client. |
| `ChatClient` | Connects to the server, sends messages, listens on a background thread. |
| `ChatGUI` | Swing window; also a `MessageHandler` that displays network events. |
| `ChatLogger` | Writes messages and events to `chat_logs/*.txt`. |
| `ChatDatabase` | JDBC: connects to MySQL, creates the `messages` table, saves every message and reads the recent history. |
| `WebServer` | Built-in HTTP server (JDK only). Serves `web/index.html` and connects each browser to the same `ChatRoom` as a `MessageHandler`, using Server-Sent Events for incoming messages. |

## Network protocol (one text line per command)

```
client -> server : JOIN|username      MSG|text      QUIT
server -> client : OK                 ERROR|reason
                   MSG|TYPE|sender|time|text
                   USERS|alice,bob    DISCONNECT|reason
```

## OOP concepts and where to find them

* **Classes and objects** – every class above; each client = one `User`, one `ClientHandler`, one `ChatClient`.
* **Encapsulation** – private final fields with getters in `User` and `Message`; GUI components are private.
* **Abstraction** – `MessageHandler` hides *how* an event is delivered; `ChatRoom` just calls `handler.handleMessage(...)`.
* **Interface** – `MessageHandler` (also `Runnable`).
* **Polymorphism** – `ChatRoom.broadcast()` calls `handleMessage()` on `ClientHandler` objects (writes to a socket); `ChatClient` calls the same method on `ChatGUI` (updates a text area).
* **Inheritance** – `ChatGUI extends JFrame`.
* **Association / composition** – `ChatServer` has a `ChatRoom` and `ChatLogger`; `ChatRoom` owns `Participant`s (`User` + `MessageHandler`); `ChatGUI` uses a `ChatClient`.
* **Exception handling** – `IOException`, `SocketException`, `SocketTimeoutException`, `NumberFormatException`, a custom `ChatClient.JoinRejectedException`, `try/catch/finally`, try-with-resources.

## Socket concepts

* `ServerSocket(port)` listens; `accept()` blocks until a client connects and returns a `Socket`.
* The client creates `new Socket()` and calls `connect(address, timeout)`.
* Text is exchanged line by line with `BufferedReader.readLine()` and `BufferedWriter.write()/newLine()/flush()` (UTF-8).
* A client that closes abruptly makes `readLine()` throw / return `null`; `ClientHandler.cleanup()` then removes the user and announces the leave.

## Multithreading concepts

* **Server**: main thread runs the accept loop; each client gets `new Thread(new ClientHandler(...))`.
* **Client**: a daemon "client-listener" thread blocks on `readLine()` so the GUI never freezes.
* **Thread safety**: `ChatRoom` methods are `synchronized`; `ChatLogger` is `synchronized`; each connection has a write lock so two threads never interleave their output. Broadcasts copy the handler list first, so a slow client cannot block the room.
* **Swing rule**: UI changes only on the Event Dispatch Thread → `SwingUtilities.invokeLater` for network callbacks and `SwingWorker` for connecting.

## GUI concepts

`JFrame` with `BorderLayout`: connection bar (north), chat `JTextArea` in a `JScrollPane` (centre, auto-scrolls),
online users `JList` + `DefaultListModel` (east), message `JTextField` + **Send** `JButton` (south),
and a coloured connection-status label (green = connected, orange = connecting, red = disconnected).
Enter sends a message. Window close sends `QUIT` so others see "X left the chat".

## File-handling concepts

`ChatLogger` creates the `chat_logs` folder (`File.mkdirs`), makes one timestamped `.txt` per server run, and appends each line
with `FileOutputStream(file, true)` wrapped in `BufferedWriter` inside try-with-resources. Example:

```
[2026-10-06 09:10:05] [EVENT] Server started on port 5000
[2026-10-06 09:10:05] [EVENT] alice joined the chat
[2026-10-06 09:10:06] alice: hello everyone
[2026-10-06 09:10:06] [EVENT] bob left the chat
```

## Database (JDBC) concepts

`ChatDatabase` uses the standard JDBC steps: `DriverManager.getConnection(url, user, password)` -> `PreparedStatement`
(with `?` parameters, so there is no SQL injection) -> `executeUpdate()` / `executeQuery()` -> `ResultSet`, all inside
try-with-resources. `ChatRoom.broadcast()` calls `saveMessage()` for every chat message and join/leave notice.

```
messages(id INT AUTO_INCREMENT PRIMARY KEY, sender VARCHAR(50), content TEXT, type VARCHAR(10), sent_at DATETIME)
```
