package com.example.game;

import java.io.*;
import java.net.*;
import java.util.*;
import javax.swing.*;

public class NetworkManager {

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private ServerSocket serverSocket;
    private volatile boolean closed;
    private volatile boolean isHost;

    private int opponentScore;
    private volatile boolean opponentDead;
    private volatile boolean opponentWatching;

    // stored callbacks for reconnection loop
    private Runnable onConnectedCb, onDeadCb, onScoreCb, onDisconnectCb, onWatchingChangedCb;
    private java.util.function.Consumer<int[][]> onBoardCb;
    private java.util.function.Consumer<String> onErrorCb;

    // ---- network utilities ----

    static String getLocalIP() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception e) { }
        return "127.0.0.1";
    }

    // ---- host / join ----

    public void host(int port,
                     Runnable onConnected, java.util.function.Consumer<String> onError,
                     Runnable onDead, Runnable onScore, Runnable onDisconnect,
                     java.util.function.Consumer<int[][]> onBoard,
                     Runnable onWatchingChanged) {
        this.isHost = true;
        this.onConnectedCb = onConnected;
        this.onErrorCb = onError;
        this.onDeadCb = onDead;
        this.onScoreCb = onScore;
        this.onDisconnectCb = onDisconnect;
        this.onBoardCb = onBoard;
        this.onWatchingChangedCb = onWatchingChanged;

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(port));
                while (!closed) {
                    socket = serverSocket.accept();
                    setupStreams();
                    opponentScore = 0;
                    opponentDead = false;
                    opponentWatching = false;
                    SwingUtilities.invokeLater(onConnectedCb);
                    readLoop();
                    // client disconnected — clean up and re-accept
                    try { if (socket != null) socket.close(); } catch (IOException x) { }
                    socket = null;
                    in = null;
                    out = null;
                    if (!closed) SwingUtilities.invokeLater(onDisconnectCb);
                }
            } catch (IOException e) {
                try { if (serverSocket != null) serverSocket.close(); } catch (IOException x) { }
                if (!closed)
                    SwingUtilities.invokeLater(() -> onErrorCb.accept("Host failed: " + e.getMessage()));
            }
        }).start();
    }

    public void join(String host, int port,
                     Runnable onConnected, java.util.function.Consumer<String> onError,
                     Runnable onDead, Runnable onScore, Runnable onDisconnect,
                     java.util.function.Consumer<int[][]> onBoard,
                     Runnable onWatchingChanged) {
        this.isHost = false;
        this.onConnectedCb = onConnected;
        this.onErrorCb = onError;
        this.onDeadCb = onDead;
        this.onScoreCb = onScore;
        this.onDisconnectCb = onDisconnect;
        this.onBoardCb = onBoard;
        this.onWatchingChangedCb = onWatchingChanged;

        new Thread(() -> {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 5000);
                setupStreams();
                SwingUtilities.invokeLater(onConnectedCb);
                readLoop();
            } catch (IOException e) {
                if (!closed)
                    SwingUtilities.invokeLater(() -> onErrorCb.accept("Connect failed: " + e.getMessage()));
            } finally {
                try { if (socket != null) socket.close(); } catch (IOException x) { }
                socket = null;
                in = null;
                out = null;
            }
            if (!closed) SwingUtilities.invokeLater(onDisconnectCb);
        }).start();
    }

    private void setupStreams() throws IOException {
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(socket.getOutputStream(), true);
    }

    // ---- read loop ----

    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("SCORE ")) {
                    opponentScore = Integer.parseInt(line.substring(6));
                    SwingUtilities.invokeLater(onScoreCb);
                } else if (line.equals("DEAD")) {
                    opponentDead = true;
                    SwingUtilities.invokeLater(onDeadCb);
                } else if (line.equals("READY")) {
                    opponentDead = false;
                    SwingUtilities.invokeLater(onScoreCb);
                } else if (line.startsWith("BOARD ")) {
                    int[][] b = parseBoard(line.substring(6));
                    SwingUtilities.invokeLater(() -> onBoardCb.accept(b));
                } else if (line.equals("WATCHING")) {
                    opponentWatching = true;
                    SwingUtilities.invokeLater(onWatchingChangedCb);
                } else if (line.equals("UNWATCHING")) {
                    opponentWatching = false;
                    SwingUtilities.invokeLater(onWatchingChangedCb);
                } else if (line.startsWith("SYNC ")) {
                    // SYNC <score>,<16 board values>
                    String data = line.substring(5);
                    int comma = data.indexOf(',');
                    opponentScore = Integer.parseInt(data.substring(0, comma).trim());
                    int[][] b = parseBoard(data.substring(comma + 1));
                    SwingUtilities.invokeLater(() -> {
                        onScoreCb.run();
                        onBoardCb.accept(b);
                    });
                }
            }
        } catch (IOException e) { }
    }

    private int[][] parseBoard(String data) {
        String[] parts = data.split(",");
        int[][] b = new int[4][4];
        for (int i = 0; i < 16 && i < parts.length; i++)
            b[i / 4][i % 4] = Integer.parseInt(parts[i].trim());
        return b;
    }

    // ---- send (non-blocking, safe to call from EDT) ----

    private void sendAsync(String msg) {
        if (out == null) return;
        PrintWriter o = out;
        new Thread(() -> { synchronized (o) { o.println(msg); } }).start();
    }

    public void sendScore(int score) {
        sendAsync("SCORE " + score);
    }

    public void sendDead() {
        sendAsync("DEAD");
    }

    public void sendReady() {
        sendAsync("READY");
    }

    public void sendBoard(int[][] board) {
        StringBuilder sb = new StringBuilder("BOARD ");
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 4; c++)
                sb.append(r == 0 && c == 0 ? "" : ",").append(board[r][c]);
        sendAsync(sb.toString());
    }

    public void sendWatching()   { sendAsync("WATCHING"); }
    public void sendUnwatching() { sendAsync("UNWATCHING"); }

    public void sendSync(int score, int[][] board) {
        StringBuilder sb = new StringBuilder("SYNC ");
        sb.append(score);
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 4; c++)
                sb.append(",").append(board[r][c]);
        sendAsync(sb.toString());
    }

    // ---- accessors ----

    public int getOpponentScore()     { return opponentScore; }
    public boolean isOpponentDead()    { return opponentDead; }
    public boolean isOpponentWatching(){ return opponentWatching; }

    // ---- close ----

    public void cancelHost() {
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) { }
        closed = true;
    }

    public void close() {
        closed = true;
        try { if (out != null) out.println("DISCONNECT"); } catch (Exception e) { }
        try { if (socket != null) socket.close(); } catch (IOException e) { }
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) { }
    }
}
