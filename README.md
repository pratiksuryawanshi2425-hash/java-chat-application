# Java Client-Server Chat Application

A multi-user chat room written in Java. Laptops/desktops use a **Swing** window, phones and other devices use a
**web page** in the browser, and everybody chats in the same room. Every message is saved to a text file **and** to a
**MySQL database (JDBC)**.

* Works on **Windows**, Linux and macOS.
* Needs **JDK 8 or newer** and a **MySQL server** (the database is optional at run time: if MySQL is not reachable the chat still works, only the database saving is skipped).
* The only external library is the MySQL JDBC driver, which is already included in `lib/`.

---

## 1. What is used for what

### Software you must install

| Software | Used for | Needed for |
|---|---|---|
| **JDK 8 or newer** (e.g. Eclipse Temurin 17 or 21) | Compiling (`javac`) and running (`java`) the project | Everything |
| **MySQL Server 8.x** | Stores the chat history in the table `messages` | Database feature only |
| A web browser (Chrome, Edge, Firefox, mobile browser) | Opens the phone / browser chat page | Web client only |
| *Optional:* MySQL Workbench or the `mysql` command line | Looking at the saved messages | Checking the database |
| *Optional:* Eclipse | Opening the project as an IDE project (`.project` and `.classpath` are included) | Development only |

### External library (included in the repository)

| File | What it is | Used by | Why |
|---|---|---|---|
| `lib/mysql-connector-j-8.4.0.jar` | **MySQL Connector/J 8.4.0** - the JDBC *driver* for MySQL (from Maven Central) | `ChatDatabase` (loaded automatically by `DriverManager`) | Lets Java talk to MySQL. It is the **only** non-JDK library. It must be on the classpath (`-cp ".;lib/*"`) when starting the **server**. The Swing client does not need it. |

### Built-in Java (JDK) features - nothing to download

| Feature in the app | Java API used | Where |
|---|---|---|
| Client-server networking over TCP | `java.net.Socket`, `ServerSocket` | `ChatServer`, `ClientHandler`, `ChatClient` |
| Desktop chat window | Swing / AWT (`javax.swing`, `java.awt`) | `ChatGUI` |
| One thread per client, background tasks | `Thread`, `Runnable`, `java.util.concurrent` | `ChatServer`, `ChatClient`, `WebServer` |
| Text-file chat log (`chat_logs/`) | `java.io` (`File`, `BufferedWriter`) | `ChatLogger` |
| Saving history to a database | **JDBC API** (`java.sql`: `Connection`, `PreparedStatement`, `ResultSet`) | `ChatDatabase` |
| Reading the MySQL settings | `java.util.Properties` | `ChatDatabase` (`db.properties`) |
| Phone / browser support | `com.sun.net.httpserver.HttpServer` (built-in web server) + Server-Sent Events | `WebServer` |
| Timestamps | `java.time` | `Message`, `ChatLogger`, `ChatDatabase` |

### Web page (no frameworks)

`web/index.html` is plain HTML + CSS + JavaScript. It uses only browser features: `fetch` (join / send), `EventSource`
(receive messages live) and `navigator.sendBeacon` (leave when the tab closes). No Node.js, npm or CDN is needed,
so it also works without internet access.

---

## 2. Project structure

```
java-chat-application/
├── User.java               Username, IP, join time
├── Message.java            One chat message
├── MessageHandler.java     Interface for receiving events
├── ChatRoom.java           The shared room (broadcast, log, save)
├── ClientHandler.java      Serves one Swing client (one thread each)
├── ChatServer.java         START THE SERVER HERE  (main)
├── ChatClient.java         Client-side networking
├── ChatGUI.java            START THE SWING CLIENT HERE  (main)
├── ChatLogger.java         Writes chat_logs/*.txt
├── ChatDatabase.java       JDBC - saves / reads history in MySQL
├── WebServer.java          Built-in web server for phones / browsers
├── db.properties           MySQL URL, user and password  (EDIT THIS)
├── web/index.html          The browser chat page
├── lib/mysql-connector-j-8.4.0.jar   JDBC driver
├── .project, .classpath    Eclipse project files
└── chat_logs/              Created automatically when the server runs
```

---

## 3. Setup (once)

### 3.1 Install the JDK

* **Windows**: download a JDK (e.g. *Eclipse Temurin 17 or 21, Windows x64 .msi*) from <https://adoptium.net> and run the installer.
  On the "Custom Setup" page enable **"Add to PATH"** and **"Set JAVA_HOME variable"**.
  Then **close and reopen** any Command Prompt / PowerShell window.
* **Linux**: `sudo apt install default-jdk` (Debian/Ubuntu). **macOS**: `brew install --cask temurin`.

Check that both commands work and print a version:

```
java -version
javac -version
```

> If `javac` is "not recognized", you installed only a JRE or PATH was not set. Reinstall the **JDK** with the PATH option above.

### 3.2 Install and start MySQL

* **Windows**: download **MySQL Installer** from <https://dev.mysql.com/downloads/installer/>, choose *Server only* (or *Developer Default*
  if you also want MySQL Workbench), keep the default port **3306**, and **set a root password** when asked - remember it.
  The installer starts MySQL as a Windows service (usually named `MySQL80` or `MySQL84`, set to start automatically).
  To check / start it: press `Win + R`, type `services.msc`, find *MySQL...*, and make sure the status is **Running**.
* **Linux**: `sudo apt install mysql-server` and `sudo systemctl start mysql`. **macOS**: `brew install mysql` and `brew services start mysql`.

You do **not** need to create the database or the table by hand - the server creates the database `chat_app` and the table `messages` on its first start.

> **Linux tip:** on Ubuntu the MySQL `root` user often cannot log in with a password. Create a normal user once, then use it in `db.properties`:
> `sudo mysql -e "CREATE USER 'chatuser'@'localhost' IDENTIFIED BY 'chatpass'; GRANT ALL ON chat_app.* TO 'chatuser'@'localhost';"`

### 3.3 Put your MySQL login in `db.properties`

Open `db.properties` in Notepad (or any editor) and change the user and password to your MySQL login:

```
db.url=jdbc:mysql://localhost:3306/chat_app?createDatabaseIfNotExist=true
db.user=root
db.password=YOUR_MYSQL_PASSWORD
```

* Keep MySQL on the same computer as the server, or replace `localhost` with the MySQL computer's address.
* If you changed the MySQL port during install, change `3306` too.
* Do not put quotes around the password. If it contains a backslash, write it as `\\`.
* Do not upload your real password to GitHub.

---

## 4. How to run

The server and all clients run from the **project folder** (the folder that contains `ChatServer.java`, `web/` and `lib/`).

### 4.1 Open a terminal in the project folder

* **Windows**: open the project folder in File Explorer, click the **address bar**, type `cmd` and press Enter.
  (Or: Shift + right-click an empty area -> *Open in Terminal* / *Open PowerShell window here*.)
  Or type: `cd C:\path\to\java-chat-application`
* **Linux / macOS**: `cd /path/to/java-chat-application`

The commands below are the same in **Command Prompt and PowerShell**. Keep the double quotes around the classpath
(in PowerShell the `;` would otherwise be treated as "end of command").

### 4.2 Compile (once, and again after you change any `.java` file)

| Windows (Command Prompt or PowerShell) | Linux / macOS |
|---|---|
| `javac -cp ".;lib/*" *.java` | `javac -cp ".:lib/*" *.java` |

(The only difference is `;` on Windows and `:` on Linux / macOS.)

No output means success.

### 4.3 Start the server (on ONE computer)

| | Windows | Linux / macOS |
|---|---|---|
| Default ports (chat 5000, web 8080) | `java -cp ".;lib/*" ChatServer` | `java -cp ".:lib/*" ChatServer` |
| Your own ports | `java -cp ".;lib/*" ChatServer 6000 9000` | `java -cp ".:lib/*" ChatServer 6000 9000` |

You should see something like:

```
=== Chat server started ===
Chat port : 5000   (Swing clients: java ChatGUI)
Open on THIS computer  : http://localhost:8080
Open on phones / other devices (same Wi-Fi):
      http://192.168.1.25:8080      (Swing server IP: 192.168.1.25, port 5000)
Log file  : chat_logs\chat_log_2026-11-22_17-46-48.txt
Database  : connected (chat history is saved in MySQL)
```

* `Database  : connected ...` means JDBC works. If you see `[Database] Not connected ...` see *Troubleshooting* below - the chat still runs.
* The first time on Windows, **Windows Defender Firewall** asks whether Java may communicate on the network. Click **Allow access**
  (tick **Private networks**, which is your home / college Wi-Fi). Without this, other devices cannot connect.
* Server console commands (type them in the server window and press Enter):

| Command | What it does |
|---|---|
| `users` | Lists who is online |
| `history` | Prints the last 20 messages saved in MySQL |
| `exit` | Stops the server (Ctrl+C also works) |

### 4.4 Connect clients

**Laptop / desktop (Swing app)** - open a *new* terminal in the project folder and run (no `lib` needed here):

```
java ChatGUI
```

Enter the server IP (use `localhost` on the same computer as the server), port `5000`, a username, and click **Connect**.
You can open as many windows as you like.

**Phone / tablet / any browser**

1. Connect the phone to the **same Wi-Fi** as the server computer.
2. Open `http://<server-ip>:8080` in the phone's browser - use the address printed by the server, e.g. `http://192.168.1.25:8080`.
3. Type a username and tap **Join**. Nothing needs to be installed on the phone.

On the server computer itself you can simply open `http://localhost:8080`.

To find the server computer's IP manually: **Windows** `ipconfig` (look for *IPv4 Address* under your Wi-Fi / Ethernet adapter),
**Linux** `hostname -I`, **macOS** `ipconfig getifaddr en0`.

Username rules: 1-20 characters, letters / digits / `_` / `-` only, not empty, not already taken
(case-insensitive), and not `SERVER`.

### 4.5 Running from Eclipse instead (optional)

1. *File -> Import -> General -> Existing Projects into Workspace* and select the project folder.
2. Right-click `ChatServer.java` -> *Run As -> Java Application* (the MySQL driver is already in `.classpath`). Eclipse runs from the project folder, so `db.properties` and `web/` are found.
3. Type server commands (`users`, `history`, `exit`) in Eclipse's *Console* view.
4. Right-click `ChatGUI.java` -> *Run As -> Java Application* for each Swing client.

---

## 5. Where is the chat history?

| Place | How to look |
|---|---|
| **MySQL** (database `chat_app`, table `messages`) | Type `history` in the server console, or in MySQL: `USE chat_app;` then `SELECT * FROM messages;` |
| **Text file** | `chat_logs/chat_log_<date>_<time>.txt` - one new file per server start |

Command-line way to query MySQL (Windows: use the *MySQL Command Line Client* from the Start menu if `mysql` is not in PATH):

```
mysql -u root -p
USE chat_app;
SELECT * FROM messages;
```

Table layout:

```
messages(id INT AUTO_INCREMENT PRIMARY KEY, sender VARCHAR(50), content TEXT, type VARCHAR(10), sent_at DATETIME)
```

---

## 6. Troubleshooting

| Problem / message | Cause and fix |
|---|---|
| `'javac' is not recognized` / `command not found` | JDK not installed or not in PATH. Install the **JDK**, reopen the terminal, run `javac -version`. |
| `Could not find or load main class ChatServer` | Wrong folder, not compiled yet, or the classpath is missing the `.` (current folder). Run from the project folder and use exactly `-cp ".;lib/*"` (Windows) / `-cp ".:lib/*"` (Linux/macOS). |
| PowerShell: `The term 'lib/*' is not recognized` | The classpath was not in double quotes. Write `-cp ".;lib/*"`. |
| `[Database] Not connected ... No suitable driver` | The server was started without the driver on the classpath. Use the full `java -cp ".;lib/*" ChatServer` command. |
| `[Database] Not connected ... Access denied for user` | Wrong user / password in `db.properties`. |
| `[Database] Not connected ... Communications link failure` | MySQL is not running (check `services.msc` on Windows) or the host / port in `db.url` is wrong. |
| `Cannot start server on port 5000` | Another server is already running on that port. Close it or choose another port: `ChatServer 6000 9000`. |
| Browser shows `web/index.html not found` | The server was not started from inside the project folder. |
| Phone / other computer cannot connect | Not on the same Wi-Fi (some guest / college networks block device-to-device traffic), wrong IP, or the firewall blocks Java. Windows: *Windows Security -> Firewall & network protection -> Allow an app through firewall -> Java (OpenJDK Platform binary) -> tick Private*. Or allow TCP ports 5000 and 8080. |
| Chat works but nothing is saved in MySQL | Look at the first lines the server printed: the `Database` line shows whether it connected. |

---

# Reference: classes and concepts

## Classes

| Class | Role |
|---|---|
| `User` | Username, IP address and join time. Validates usernames. Immutable. |
| `Message` | Sender, content, timestamp and type (CHAT or SYSTEM). Converts itself to/from one line of text for the network. |
| `MessageHandler` | Interface with `handleMessage`, `handleUserList`, `handleDisconnect`. |
| `ChatRoom` | The shared room: keeps online users, rejects duplicates, broadcasts messages and user lists, logs everything (text file + database). |
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
* **Association / composition** – `ChatServer` has a `ChatRoom`, a `ChatLogger` and a `ChatDatabase`; `ChatRoom` owns `Participant`s (`User` + `MessageHandler`); `ChatGUI` uses a `ChatClient`.
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
