package com.example.game;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.Random;

public class Game2048 extends JPanel {

    private static final int SIZE = 4;
    private static final int MIN_SIZE = 320;
    private static final Path SAVE_FILE = Paths.get(
            System.getProperty("user.home"), ".game2048", "save.dat");

    private int[][] board = new int[SIZE][SIZE];
    private int score;
    private int highScore;
    private boolean gameOver;
    private boolean darkMode;
    private JLabel scoreLabel;
    private JLabel highScoreLabel;
    private boolean multiplayer;
    private NetworkManager network;
    private int opponentScore;
    private volatile boolean opponentDead;
    private volatile boolean gameEnded;
    private volatile boolean spectating;
    private volatile boolean beingWatched;
    volatile boolean disconnected;
    private final int[][] opponentBoard = new int[SIZE][SIZE];
    private JButton watchBtn, deathRestartBtn, spectateBackBtn;
    private final Random rand = new Random();

    private Color bgColor, textColor, emptyTileColor;
    private Color[] tileColors;

    public Game2048(boolean dark) {
        this.darkMode = dark;
        setPreferredSize(new Dimension(540, 540));
        setMinimumSize(new Dimension(MIN_SIZE, MIN_SIZE));
        setFocusable(true);
        setLayout(null);
        applyTheme();

        // ---- death overlay buttons (multiplayer only, initially hidden) ----
        watchBtn = new JButton("Watch");
        watchBtn.setFocusable(false);
        watchBtn.setVisible(false);
        add(watchBtn);

        deathRestartBtn = new JButton("Restart");
        deathRestartBtn.setFocusable(false);
        deathRestartBtn.setVisible(false);
        add(deathRestartBtn);

        spectateBackBtn = new JButton("←");
        spectateBackBtn.setFocusable(false);
        spectateBackBtn.setContentAreaFilled(false);
        spectateBackBtn.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
        spectateBackBtn.setVisible(false);
        add(spectateBackBtn);

        // ---- button actions ----
        watchBtn.addActionListener(e -> {
            spectating = true;
            if (network != null) network.sendWatching();
            layoutOverlayButtons();
            repaint();
        });

        deathRestartBtn.addActionListener(e -> {
            reset();
            if (multiplayer && network != null) network.sendReady();
        });

        spectateBackBtn.addActionListener(e -> {
            if (network != null) network.sendUnwatching();
            reset();
            if (multiplayer && network != null) network.sendReady();
        });

        addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) { layoutOverlayButtons(); }
        });

        addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                // G key: toggle spectating in multiplayer
                if (e.getKeyCode() == KeyEvent.VK_G && multiplayer && network != null) {
                    if (spectating) {
                        spectating = false;
                        network.sendUnwatching();
                    } else {
                        spectating = true;
                        network.sendWatching();
                    }
                    layoutOverlayButtons();
                    repaint();
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_R) {
                    if (spectating) return;
                    reset();
                    if (multiplayer && network != null) network.sendReady();
                    return;
                }
                if (gameOver || gameEnded || spectating) return;
                boolean moved = false;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_UP:    moved = moveUp();    break;
                    case KeyEvent.VK_DOWN:  moved = moveDown();  break;
                    case KeyEvent.VK_LEFT:  moved = moveLeft();  break;
                    case KeyEvent.VK_RIGHT: moved = moveRight(); break;
                }
                if (moved) {
                    spawnTile();
                    if (network != null) {
                        network.sendScore(score);
                        network.sendBoard(board);
                    }
                    if (isGameOver()) {
                        gameOver = true;
                        if (network != null) { network.sendDead(); gameEnded = true; }
                    }
                    updateScoreLabel();
                    if (!multiplayer) saveGame();
                    repaint();
                    layoutOverlayButtons();
                }
            }
        });

        if (!loadGame()) reset();
    }

    public void setDarkMode(boolean dark) { this.darkMode = dark; applyTheme(); repaint(); }
    public boolean isDarkMode() { return darkMode; }

    private void applyTheme() {
        if (darkMode) {
            bgColor = new Color(0x1A1A18);
            textColor = new Color(0xB0A898);
            emptyTileColor = new Color(0x2E2D2A);
            tileColors = new Color[]{ emptyTileColor,
                    new Color(0x3E3D38), new Color(0x4A4840), new Color(0x6B5030),
                    new Color(0x7B4020), new Color(0x8B3818), new Color(0x9B3010),
                    new Color(0x8B8028), new Color(0x8B7820), new Color(0x8B7018),
                    new Color(0x8B6808), new Color(0x8B6000), new Color(0x7B5000),
            };
        } else {
            bgColor = new Color(0xBBADA0);
            textColor = new Color(0x776E65);
            emptyTileColor = new Color(0xCDC1B4);
            tileColors = new Color[]{ emptyTileColor,
                    new Color(0xEEE4DA), new Color(0xEDE0C8), new Color(0xF2B179),
                    new Color(0xF59563), new Color(0xF67C5F), new Color(0xF65E3B),
                    new Color(0xEDCF72), new Color(0xEDCC61), new Color(0xEDC850),
                    new Color(0xEDC53F), new Color(0xEDC22E),
            };
        }
        setBackground(bgColor);
    }

    static boolean isSystemDark() {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"defaults", "read", "-g", "AppleInterfaceStyle"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            return "Dark".equals(line);
        } catch (Exception e) { return false; }
    }

    private int tileSize() { return Math.min(getWidth(), getHeight()) / (SIZE + 1); }
    private int gap()       { return tileSize() / 10; }
    private int radius()    { return Math.max(4, tileSize() / 12); }

    public int getScore() { return score; }
    public int getHighScore() { return highScore; }
    public void setScoreLabel(JLabel label) { this.scoreLabel = label; updateScoreLabel(); }
    public void setHighScoreLabel(JLabel label) { this.highScoreLabel = label; updateHighScoreLabel(); }
    private void updateScoreLabel() {
        if (score > highScore) highScore = score;
        if (scoreLabel != null) scoreLabel.setText("Score: " + score);
        updateHighScoreLabel();
    }
    private void updateHighScoreLabel() {
        if (highScoreLabel != null) highScoreLabel.setText("Best: " + highScore);
    }

    public void setMultiplayer(NetworkManager net) {
        this.multiplayer = true;
        this.network = net;
        this.opponentDead = false;
        this.gameEnded = false;
        this.spectating = false;
        this.beingWatched = false;
        this.disconnected = false;
        // callbacks are now passed to host()/join() directly;
        // reset is handled by the caller (main)
    }

    int[][] getBoardSnapshot() {
        int[][] copy = new int[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++)
            System.arraycopy(board[r], 0, copy[r], 0, SIZE);
        return copy;
    }

    public void leaveMultiplayer() {
        multiplayer = false;
        if (network != null) { network.close(); network = null; }
    }

    public boolean isInMultiplayer() { return multiplayer; }

    public void saveGame() {
        try {
            Files.createDirectories(SAVE_FILE.getParent());
            BufferedWriter w = Files.newBufferedWriter(SAVE_FILE);
            w.write(score + "\n");
            w.write(highScore + "\n");
            w.write(darkMode + "\n");
            for (int r = 0; r < SIZE; r++) {
                for (int c = 0; c < SIZE; c++)
                    w.write(board[r][c] + (c < SIZE - 1 ? " " : ""));
                w.write("\n");
            }
            w.close();
        } catch (IOException e) { }
    }

    private boolean loadGame() {
        try {
            BufferedReader r = Files.newBufferedReader(SAVE_FILE);
            score = Integer.parseInt(r.readLine());
            String secondLine = r.readLine();
            try {
                highScore = Integer.parseInt(secondLine);
                darkMode = Boolean.parseBoolean(r.readLine());
            } catch (NumberFormatException e) {
                // old save format: second line is darkMode, no highScore
                darkMode = Boolean.parseBoolean(secondLine);
                highScore = score;
            }
            applyTheme();
            for (int row = 0; row < SIZE; row++) {
                String[] parts = r.readLine().split(" ");
                for (int col = 0; col < SIZE; col++)
                    board[row][col] = Integer.parseInt(parts[col]);
            }
            r.close();
            return true;
        } catch (Exception e) { return false; }
    }

    private void deleteSave() { try { Files.deleteIfExists(SAVE_FILE); } catch (IOException e) { } }

    private void reset() {
        if (spectating && network != null) network.sendUnwatching();
        board = new int[SIZE][SIZE];
        score = 0;
        gameOver = false;
        gameEnded = false;
        opponentDead = false;
        spectating = false;
        beingWatched = false;
        disconnected = false;
        watchBtn.setVisible(false);
        deathRestartBtn.setVisible(false);
        spectateBackBtn.setVisible(false);
        if (!multiplayer) deleteSave();
        updateScoreLabel();
        updateHighScoreLabel();
        spawnTile();
        spawnTile();
        repaint();
    }

    private void spawnTile() {
        int empty = 0;
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                if (board[r][c] == 0) empty++;
        if (empty == 0) return;
        int target = rand.nextInt(empty);
        int val = rand.nextInt(10) < 9 ? 2 : 4;
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                if (board[r][c] == 0) {
                    if (target == 0) { board[r][c] = val; return; }
                    target--;
                }
    }

    private boolean moveLeft() {
        boolean moved = false;
        for (int r = 0; r < SIZE; r++) {
            int[] row = board[r], merged = new int[SIZE];
            boolean[] justMerged = new boolean[SIZE];
            int pos = 0;
            for (int c = 0; c < SIZE; c++) {
                if (row[c] == 0) continue;
                if (pos > 0 && merged[pos - 1] == row[c] && !justMerged[pos - 1]) {
                    merged[pos - 1] *= 2;
                    score += merged[pos - 1];
                    justMerged[pos - 1] = true;
                    moved = true;
                } else merged[pos++] = row[c];
            }
            while (pos < SIZE) merged[pos++] = 0;
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] != merged[c]) moved = true;
                board[r][c] = merged[c];
            }
        }
        return moved;
    }

    private boolean moveRight() { reverseRows(); boolean m = moveLeft(); reverseRows(); return m; }
    private boolean moveUp()    { transpose();  boolean m = moveLeft(); transpose();  return m; }
    private boolean moveDown()  { transpose(); reverseRows(); boolean m = moveLeft(); reverseRows(); transpose(); return m; }

    private void reverseRows() {
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE / 2; c++) {
                int t = board[r][c];
                board[r][c] = board[r][SIZE - 1 - c];
                board[r][SIZE - 1 - c] = t;
            }
    }

    private void transpose() {
        for (int r = 0; r < SIZE; r++)
            for (int c = r + 1; c < SIZE; c++) {
                int t = board[r][c];
                board[r][c] = board[c][r];
                board[c][r] = t;
            }
    }

    private boolean isGameOver() {
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] == 0) return false;
                if (c < SIZE - 1 && board[r][c] == board[r][c + 1]) return false;
                if (r < SIZE - 1 && board[r][c] == board[r + 1][c]) return false;
            }
        return true;
    }

    private Color tileColor(int val) {
        if (val == 0) return emptyTileColor;
        int idx = 1 + (int) (Math.log(val) / Math.log(2));
        return idx < tileColors.length ? tileColors[idx] : tileColors[tileColors.length - 1];
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int ts = tileSize(), gp = gap(), rd = radius();
        int boardW = SIZE * (ts + gp) + gp, boardH = SIZE * (ts + gp) + gp;
        // opponent score bar in multiplayer mode
        int topReserved = 0;
        if (multiplayer) {
            topReserved = Math.max(20, getHeight() / 20);
            g2.setColor(textColor);
            int oppFs = Math.max(10, topReserved / 2);
            g2.setFont(new Font("SansSerif", Font.PLAIN, oppFs));
            FontMetrics ofm = g2.getFontMetrics();
            String oppText;
            if (spectating) {
                oppText = "Watching opponent...";
            } else {
                oppText = "Opponent: " + opponentScore + (opponentDead ? " (dead)" : "");
            }
            int oppTextX = (getWidth() - ofm.stringWidth(oppText)) / 2;
            g2.drawString(oppText, oppTextX,
                    (topReserved + ofm.getAscent() - ofm.getDescent()) / 2);
            // green dot when opponent is watching
            if (beingWatched && !spectating) {
                int dotR = Math.max(3, topReserved / 6);
                int dotX = oppTextX + ofm.stringWidth(oppText) + dotR * 2;
                int dotY = topReserved / 2 - dotR;
                g2.setColor(new Color(0x4CAF50));
                g2.fillOval(dotX, dotY, dotR * 2, dotR * 2);
            }
        }

        int offX = (getWidth() - boardW) / 2;
        int offY = (getHeight() - boardH - gp * 2 - 24) / 2 + topReserved / 2;

        g2.setColor(bgColor);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 15, 15);

        int[][] renderBoard = spectating ? opponentBoard : board;
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                int x = offX + gp + c * (ts + gp);
                int y = offY + gp + r * (ts + gp);
                int val = renderBoard[r][c];

                g2.setColor(tileColor(val));
                g2.fillRoundRect(x, y, ts, ts, rd, rd);

                if (val != 0) {
                    g2.setColor(val <= 4 ? textColor : Color.WHITE);
                    int fs = Math.max(12, ts / (val < 100 ? 3 : val < 1000 ? 4 : 5));
                    g2.setFont(new Font("SansSerif", Font.BOLD, fs));
                    FontMetrics fm = g2.getFontMetrics();
                    String s = String.valueOf(val);
                    g2.drawString(s, x + (ts - fm.stringWidth(s)) / 2,
                            y + (ts + fm.getAscent() - fm.getDescent()) / 2);
                }
            }
        }

        // score below board
        g2.setColor(textColor);
        int scoreFs = Math.max(10, ts / 3);
        g2.setFont(new Font("SansSerif", Font.BOLD, scoreFs));
        FontMetrics fm2 = g2.getFontMetrics();
        String scoreText = "Score: " + score;
        g2.drawString(scoreText, (getWidth() - fm2.stringWidth(scoreText)) / 2,
                offY + boardH + gp * 2 + fm2.getAscent());

        if (gameOver && !spectating) {
            g2.setColor(new Color(0, 0, 0, 180));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 15, 15);
            g2.setColor(Color.WHITE);
            int w = getWidth(), h = getHeight();
            int fs = Math.max(14, w / 22);
            g2.setFont(new Font("SansSerif", Font.BOLD, fs));
            FontMetrics fm = g2.getFontMetrics();
            String msg;
            if (multiplayer) {
                msg = opponentDead ? "You Won!" : "You Lost!";
            } else {
                msg = "徐小猪猪你输啦啦啦啦啦";
            }
            g2.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2 - 10);

            if (!multiplayer) {
                int sf = Math.max(10, w / 32);
                g2.setFont(new Font("SansSerif", Font.PLAIN, sf));
                fm = g2.getFontMetrics();
                g2.drawString("Press R to restart", (w - fm.stringWidth("Press R to restart")) / 2, h / 2 + 30);
            }
        }

        // spectating overlays
        if (spectating) {
            int w = getWidth(), h = getHeight();
            if (beingWatched) {
                // mutual spectating
                g2.setColor(new Color(0, 0, 0, 180));
                g2.fillRoundRect(0, 0, w, h, 15, 15);
                g2.setColor(Color.WHITE);
                int fs = Math.max(12, w / 26);
                g2.setFont(new Font("SansSerif", Font.BOLD, fs));
                FontMetrics fm = g2.getFontMetrics();
                String m = "不要再视奸对方了";
                g2.drawString(m, (w - fm.stringWidth(m)) / 2, h / 2);
            } else if (opponentDead) {
                // opponent is dead while spectating
                g2.setColor(new Color(0, 0, 0, 180));
                g2.fillRoundRect(0, 0, w, h, 15, 15);
                g2.setColor(Color.WHITE);
                int fs = Math.max(12, w / 26);
                g2.setFont(new Font("SansSerif", Font.BOLD, fs));
                FontMetrics fm = g2.getFontMetrics();
                String m = "Opponent is dead";
                g2.drawString(m, (w - fm.stringWidth(m)) / 2, h / 2);
            }
        }
    }

    void layoutOverlayButtons() {
        int w = getWidth(), h = getHeight();
        boolean d = darkMode;

        int btnW = Math.max(60, Math.min(140, w / 5));
        int btnH = Math.max(24, Math.min(40, h / 14));
        int btnFs = Math.max(10, Math.min(16, w / 36));
        int gap = Math.max(8, w / 30);

        // ---- death overlay buttons ----
        if (gameOver && multiplayer && !spectating) {
            int totalW = btnW * 2 + gap;
            int startX = (w - totalW) / 2;
            int y = h / 2 + Math.max(10, h / 24);

            watchBtn.setBounds(startX, y, btnW, btnH);
            watchBtn.setFont(new Font("SansSerif", Font.BOLD, btnFs));
            watchBtn.setVisible(!opponentDead);  // only if opponent is still alive

            deathRestartBtn.setBounds(startX + btnW + gap, y, btnW, btnH);
            deathRestartBtn.setFont(new Font("SansSerif", Font.BOLD, btnFs));
            deathRestartBtn.setVisible(true);

            spectateBackBtn.setVisible(false);
        } else if (spectating) {
            watchBtn.setVisible(false);
            deathRestartBtn.setVisible(false);

            int arrowFs = Math.max(14, Math.min(22, w / 28));
            spectateBackBtn.setFont(new Font("SansSerif", Font.BOLD, arrowFs));
            spectateBackBtn.setForeground(d ? new Color(0xB0A898) : new Color(0x776E65));
            spectateBackBtn.setBounds(4, 4, arrowFs * 3, arrowFs * 2);
            spectateBackBtn.setVisible(true);
        } else {
            watchBtn.setVisible(false);
            deathRestartBtn.setVisible(false);
            spectateBackBtn.setVisible(false);
        }
    }

    // ========== Custom icons ==========

    // Simple pushpin icon: a vertical line (needle) + circle/oval head
    static class PinIcon implements Icon {
        private final int w, h;
        private final boolean filled;
        PinIcon(int s, boolean filled) { this.w = s; this.h = s; this.filled = filled; }
        public int getIconWidth()  { return w; }
        public int getIconHeight() { return h; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(c.getForeground());
            int cx = x + w / 2, r = w / 5;
            // needle
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawLine(cx, y + h - 2, cx, y + r + 1);
            // head
            if (filled) {
                g2.fillOval(cx - r, y, r * 2, r * 2 + 2);
            } else {
                g2.drawOval(cx - r, y, r * 2, r * 2 + 2);
            }
        }
    }

    // Half-moon icon for dark mode toggle
    static class MoonIcon implements Icon {
        private final int s;
        MoonIcon(int s) { this.s = s; }
        public int getIconWidth()  { return s; }
        public int getIconHeight() { return s; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(c.getForeground());
            int pad = 2;
            g2.fillOval(x + pad, y + pad, s - pad * 2, s - pad * 2);
        }
    }

    // ========== main ==========

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            boolean systemDark = isSystemDark();
            JFrame frame = new JFrame("2048");
            CardLayout cards = new CardLayout();
            JPanel mainPanel = new JPanel(cards);

            // ---- shared state ----
            final boolean[] darkMode = { systemDark };
            final JToggleButton[] darkBtnHolder = new JToggleButton[1];
            final JToggleButton[] pinBtnHolder = new JToggleButton[1];

            // -- helper: dark/pin top bar --
            java.util.function.Function<String, JPanel> makeTopBar = (title) -> {
                JPanel bar = new JPanel(new BorderLayout());
                bar.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 8));

                JLabel lbl = new JLabel(title);
                bar.add(lbl, BorderLayout.WEST);

                JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
                right.setOpaque(false);

                JLabel hsLabel = new JLabel();
                right.add(hsLabel);

                JToggleButton darkBtn = new JToggleButton();
                darkBtn.setIcon(new MoonIcon(14));
                darkBtn.setFocusable(false);
                darkBtn.setContentAreaFilled(false);
                darkBtn.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
                darkBtn.setToolTipText("暗夜模式");
                darkBtn.setSelected(darkMode[0]);
                right.add(darkBtn);
                darkBtnHolder[0] = darkBtn;

                JToggleButton pinBtn = new JToggleButton();
                pinBtn.setIcon(new PinIcon(14, false));
                pinBtn.setSelectedIcon(new PinIcon(14, true));
                pinBtn.setFocusable(false);
                pinBtn.setContentAreaFilled(false);
                pinBtn.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
                pinBtn.setToolTipText("窗口置顶");
                right.add(pinBtn);
                pinBtnHolder[0] = pinBtn;
                pinBtn.addActionListener(e -> frame.setAlwaysOnTop(pinBtn.isSelected()));

                bar.add(right, BorderLayout.EAST);
                return bar;
            };

            java.util.function.Consumer<JPanel> styleBar = (bar) -> {
                bar.setBackground(darkMode[0] ? new Color(0x1A1A18) : new Color(0xBBADA0));
                Color fg = darkMode[0] ? new Color(0xB0A898) : new Color(0x776E65);
                for (java.awt.Component c : bar.getComponents()) {
                    if (c instanceof JLabel) c.setForeground(fg);
                    if (c instanceof JButton) {
                        if (((JButton) c).isContentAreaFilled()) { c.setBackground(fg); c.setForeground(bar.getBackground()); }
                        else c.setForeground(fg);
                    }
                    if (c instanceof JPanel) {
                        for (java.awt.Component cc : ((JPanel) c).getComponents()) {
                            if (cc instanceof JLabel) cc.setForeground(fg);
                            if (cc instanceof JToggleButton) {
                                JToggleButton b = (JToggleButton) cc;
                                b.setBackground(bar.getBackground());
                                if (b.getIcon() instanceof MoonIcon)
                                    b.setForeground(b.isSelected() ? Color.WHITE : fg);
                                else b.setForeground(b.isSelected() ? Color.RED : fg);
                            }
                        }
                    }
                }
            };

            // ==================== single-player card ====================

            Game2048 spGame = new Game2048(darkMode[0]);
            JPanel topBar = makeTopBar.apply("");
            // add back button to single-player top bar
            JButton spBackBtn = new JButton("←");
            spBackBtn.setFont(new Font("SansSerif", Font.BOLD, 16));
            spBackBtn.setFocusable(false);
            spBackBtn.setContentAreaFilled(false);
            spBackBtn.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
            topBar.add(spBackBtn, BorderLayout.WEST);
            JPanel spPanel = new JPanel(new BorderLayout());
            spPanel.add(topBar, BorderLayout.NORTH);
            spPanel.add(spGame, BorderLayout.CENTER);
            // find highscore label in topbar
            final JLabel[] spHsLabel = {null};
            for (java.awt.Component c : topBar.getComponents()) {
                if (c instanceof JPanel) {
                    for (java.awt.Component cc : ((JPanel) c).getComponents()) {
                        if (cc instanceof JLabel) { spHsLabel[0] = (JLabel) cc; break; }
                    }
                }
            }
            spGame.setHighScoreLabel(spHsLabel[0]);

            JToggleButton spDarkBtn = darkBtnHolder[0];
            spDarkBtn.addActionListener(e -> {
                darkMode[0] = spDarkBtn.isSelected();
                spGame.setDarkMode(darkMode[0]);
                styleBar.accept(topBar);
            });
            styleBar.accept(topBar);

            // ==================== menu card ====================

            Color[] mColors = { darkMode[0] ? new Color(0x1A1A18) : new Color(0xBBADA0),
                                darkMode[0] ? new Color(0xB0A898) : new Color(0x776E65) };

            JPanel menuPanel = new JPanel() {
                protected void paintComponent(Graphics g) {
                    g.setColor(mColors[0]);
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            menuPanel.setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridwidth = GridBagConstraints.REMAINDER;
            gbc.fill = GridBagConstraints.NONE;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.weightx = 0;
            gbc.weighty = 0;

            JLabel menuTitle = new JLabel("2048", SwingConstants.CENTER);
            menuPanel.add(menuTitle, gbc);

            JButton btnSingle = new JButton("Single Player");
            JButton btnMulti  = new JButton("Multiplayer");
            btnSingle.setFocusable(false);
            btnMulti.setFocusable(false);
            menuPanel.add(btnSingle, gbc);
            menuPanel.add(btnMulti,  gbc);

            JLabel menuHs = new JLabel("Best: " + spGame.getHighScore(), SwingConstants.CENTER);
            JPanel hsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
            hsPanel.setOpaque(false);
            hsPanel.add(menuHs);
            menuPanel.add(hsPanel, gbc);

            // dark mode + pin toggles in one row
            JToggleButton menuDarkBtn = new JToggleButton();
            menuDarkBtn.setFocusable(false);
            menuDarkBtn.setContentAreaFilled(false);
            menuDarkBtn.setToolTipText("暗夜模式");
            menuDarkBtn.setSelected(darkMode[0]);

            JToggleButton menuPinBtn = new JToggleButton();
            menuPinBtn.setFocusable(false);
            menuPinBtn.setContentAreaFilled(false);
            menuPinBtn.setToolTipText("窗口置顶");
            menuPinBtn.addActionListener(e -> frame.setAlwaysOnTop(menuPinBtn.isSelected()));

            JPanel menuToggleRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
            menuToggleRow.setOpaque(false);
            menuToggleRow.add(menuDarkBtn);
            menuToggleRow.add(menuPinBtn);
            menuPanel.add(menuToggleRow, gbc);

            // ---- dynamic menu styling ----
            Runnable styleMenu = () -> {
                int w = menuPanel.getWidth();
                if (w < 100) w = 540; // fallback for initial pack
                boolean d = darkMode[0];
                Color bg = d ? new Color(0x1A1A18) : new Color(0xBBADA0);
                Color fg = d ? new Color(0xB0A898) : new Color(0x776E65);
                Color btnFg = d ? new Color(0xF5F0E6) : bg;

                int titleFs = Math.max(24, Math.min(72, w / 8));
                int btnFs   = Math.max(12, Math.min(32, w / 22));
                int scoreFs = Math.max(10, Math.min(24, w / 26));
                int iconSz  = Math.max(10, Math.min(22, w / 28));
                int padX = Math.max(8, w / 30);
                int padY = Math.max(4, w / 60);
                int topGap = Math.max(4, w / 40);

                menuTitle.setForeground(fg);
                menuTitle.setFont(new Font("SansSerif", Font.BOLD, titleFs));

                java.util.function.Consumer<JButton> applyBtn = b -> {
                    b.setFont(new Font("SansSerif", Font.PLAIN, btnFs));
                    b.setBackground(fg);
                    b.setForeground(btnFg);
                    b.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(fg, 1),
                            BorderFactory.createEmptyBorder(padY, padX, padY, padX)));
                };
                applyBtn.accept(btnSingle);
                applyBtn.accept(btnMulti);

                menuHs.setForeground(fg);
                menuHs.setFont(new Font("SansSerif", Font.PLAIN, scoreFs));

                menuDarkBtn.setIcon(new MoonIcon(iconSz));
                menuDarkBtn.setBackground(bg);
                menuDarkBtn.setForeground(d ? Color.WHITE : Color.BLACK);
                menuDarkBtn.setBorder(BorderFactory.createEmptyBorder(topGap, topGap + 2, topGap, topGap + 2));

                menuPinBtn.setIcon(new PinIcon(iconSz, false));
                menuPinBtn.setSelectedIcon(new PinIcon(iconSz, true));
                menuPinBtn.setBorder(BorderFactory.createEmptyBorder(topGap, topGap + 2, topGap, topGap + 2));
                menuPinBtn.setForeground(menuPinBtn.isSelected() ? Color.RED : fg);

                int hMargin = Math.max(16, w / 10);
                gbc.insets = new Insets(topGap, hMargin, topGap, hMargin);
            };
            styleMenu.run();
            menuPanel.addComponentListener(new java.awt.event.ComponentAdapter() {
                public void componentResized(java.awt.event.ComponentEvent e) { styleMenu.run(); }
            });

            menuDarkBtn.addActionListener(e -> {
                darkMode[0] = menuDarkBtn.isSelected();
                mColors[0] = darkMode[0] ? new Color(0x1A1A18) : new Color(0xBBADA0);
                mColors[1] = darkMode[0] ? new Color(0xB0A898) : new Color(0x776E65);
                spGame.setDarkMode(darkMode[0]);
                styleBar.accept(topBar);
                styleMenu.run();
                menuPanel.repaint();
            });

            Runnable refreshMenuHs = () -> {
                menuHs.setText("Best: " + spGame.getHighScore());
            };

            // ==================== multiplayer setup card ====================

            JPanel mSetupWrapper = new JPanel(new BorderLayout());

            // top bar with back button at left
            JPanel mSetupTopBar = new JPanel(new BorderLayout());
            mSetupTopBar.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 8));

            JButton mBackBtn = new JButton("←");
            mBackBtn.setFont(new Font("SansSerif", Font.BOLD, 16));
            mBackBtn.setFocusable(false);
            mBackBtn.setContentAreaFilled(false);
            mBackBtn.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
            mSetupTopBar.add(mBackBtn, BorderLayout.WEST);
            mSetupWrapper.add(mSetupTopBar, BorderLayout.NORTH);

            // center form
            JPanel mSetupPanel = new JPanel(new GridBagLayout());
            GridBagConstraints mg = new GridBagConstraints();
            mg.gridwidth = GridBagConstraints.REMAINDER;
            mg.fill = GridBagConstraints.NONE;
            mg.anchor = GridBagConstraints.CENTER;
            mg.weightx = 0;
            mg.weighty = 0;

            JLabel mTitle = new JLabel("Multiplayer", SwingConstants.CENTER);
            mSetupPanel.add(mTitle, mg);

            JLabel ipLabel = new JLabel("Your IP: " + NetworkManager.getLocalIP(), SwingConstants.CENTER);
            mSetupPanel.add(ipLabel, mg);

            JTextField portField = new JTextField("8765", 6);
            JPanel portRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
            final JLabel portLabel = new JLabel("Port:");
            portRow.add(portLabel);
            portRow.add(portField);
            mSetupPanel.add(portRow, mg);

            JButton hostBtn = new JButton("Host Game");
            hostBtn.setFocusable(false);
            mSetupPanel.add(hostBtn, mg);

            JLabel orLabel = new JLabel("— or —", SwingConstants.CENTER);
            mSetupPanel.add(orLabel, mg);

            JTextField joinIpField = new JTextField(15);
            JPanel joinIpRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
            final JLabel serverIpLabel = new JLabel("Server IP:");
            joinIpRow.add(serverIpLabel);
            joinIpRow.add(joinIpField);
            mSetupPanel.add(joinIpRow, mg);

            JButton joinBtn = new JButton("Join Game");
            joinBtn.setFocusable(false);
            mSetupPanel.add(joinBtn, mg);

            JLabel statusLabel = new JLabel("", SwingConstants.CENTER);
            mSetupPanel.add(statusLabel, mg);

            mSetupWrapper.add(mSetupPanel, BorderLayout.CENTER);

            // ---- dynamic setup styling ----
            Runnable styleSetup = () -> {
                int w = mSetupWrapper.getWidth();
                if (w < 100) w = 540;
                boolean d = darkMode[0];
                Color bg = d ? new Color(0x1A1A18) : new Color(0xBBADA0);
                Color fg = d ? new Color(0xB0A898) : new Color(0x776E65);
                Color btnFg = d ? new Color(0xF5F0E6) : bg;

                int titleFs = Math.max(16, Math.min(36, w / 16));
                int labelFs = Math.max(11, Math.min(20, w / 30));
                int btnFs   = Math.max(12, Math.min(24, w / 26));
                int arrowFs = Math.max(14, Math.min(22, w / 28));
                int padY    = Math.max(4, w / 60);
                int padX    = Math.max(8, w / 30);
                int hMargin = Math.max(16, w / 10);

                mSetupTopBar.setBackground(bg);
                mBackBtn.setForeground(fg);
                mBackBtn.setFont(new Font("SansSerif", Font.BOLD, arrowFs));

                mSetupPanel.setBackground(bg);
                mTitle.setForeground(fg);
                mTitle.setFont(new Font("SansSerif", Font.BOLD, titleFs));
                ipLabel.setForeground(fg);
                ipLabel.setFont(new Font("SansSerif", Font.PLAIN, labelFs));
                orLabel.setForeground(fg);
                orLabel.setFont(new Font("SansSerif", Font.PLAIN, labelFs));
                statusLabel.setForeground(d ? new Color(0xFF6B6B) : Color.RED);
                statusLabel.setFont(new Font("SansSerif", Font.PLAIN, labelFs));

                for (java.awt.Component c : mSetupPanel.getComponents()) {
                    if (c instanceof JLabel) {
                        c.setForeground(fg);
                        if (c != mTitle && c != statusLabel)
                            c.setFont(new Font("SansSerif", Font.PLAIN, labelFs));
                    }
                    if (c instanceof JPanel) {
                        c.setBackground(bg);
                        for (java.awt.Component cc : ((JPanel) c).getComponents()) {
                            if (cc instanceof JLabel) { cc.setForeground(fg); cc.setFont(new Font("SansSerif", Font.PLAIN, labelFs)); }
                            cc.setBackground(bg);
                        }
                    }
                    if (c instanceof JButton) {
                        JButton btn = (JButton) c;
                        btn.setFont(new Font("SansSerif", Font.PLAIN, btnFs));
                        if (btn.isContentAreaFilled()) {
                            btn.setBackground(fg);
                            btn.setForeground(btnFg);
                            btn.setBorder(BorderFactory.createCompoundBorder(
                                    BorderFactory.createLineBorder(fg, 1),
                                    BorderFactory.createEmptyBorder(padY, padX, padY, padX)));
                        }
                    }
                }
                portField.setBackground(bg); portField.setForeground(fg);
                portField.setCaretColor(fg);
                portField.setFont(new Font("SansSerif", Font.PLAIN, labelFs));
                portField.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(fg, 1),
                        BorderFactory.createEmptyBorder(padY, padY, padY, padY)));
                joinIpField.setBackground(bg); joinIpField.setForeground(fg);
                joinIpField.setCaretColor(fg);
                joinIpField.setFont(new Font("SansSerif", Font.PLAIN, labelFs));
                joinIpField.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(fg, 1),
                        BorderFactory.createEmptyBorder(padY, padY, padY, padY)));

                mg.insets = new Insets(padY, hMargin, padY, hMargin);
            };
            styleSetup.run();
            mSetupWrapper.addComponentListener(new java.awt.event.ComponentAdapter() {
                public void componentResized(java.awt.event.ComponentEvent e) { styleSetup.run(); }
            });

            // ==================== multiplayer game card ====================

            JPanel mgPanel = new JPanel(new BorderLayout());

            JPanel mgTopBar = new JPanel(new BorderLayout());
            mgTopBar.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 8));

            JButton mgBackBtn = new JButton("←");
            mgBackBtn.setFont(new Font("SansSerif", Font.BOLD, 16));
            mgBackBtn.setFocusable(false);
            mgBackBtn.setContentAreaFilled(false);
            mgBackBtn.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
            mgTopBar.add(mgBackBtn, BorderLayout.WEST);

            JLabel mgOppLabel = new JLabel("Opponent: 0");
            mgOppLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
            JPanel mgRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            mgRight.setOpaque(false);
            mgRight.add(mgOppLabel);

            JToggleButton mgDarkBtn = new JToggleButton();
            mgDarkBtn.setIcon(new MoonIcon(14));
            mgDarkBtn.setFocusable(false);
            mgDarkBtn.setContentAreaFilled(false);
            mgDarkBtn.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            mgDarkBtn.setToolTipText("暗夜模式");
            mgDarkBtn.setSelected(darkMode[0]);
            mgRight.add(mgDarkBtn);

            JToggleButton mgPinBtn = new JToggleButton();
            mgPinBtn.setIcon(new PinIcon(14, false));
            mgPinBtn.setSelectedIcon(new PinIcon(14, true));
            mgPinBtn.setFocusable(false);
            mgPinBtn.setContentAreaFilled(false);
            mgPinBtn.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            mgPinBtn.setToolTipText("窗口置顶");
            mgRight.add(mgPinBtn);
            mgPinBtn.addActionListener(e -> frame.setAlwaysOnTop(mgPinBtn.isSelected()));

            mgTopBar.add(mgRight, BorderLayout.EAST);
            mgPanel.add(mgTopBar, BorderLayout.NORTH);

            Game2048 mgGame = new Game2048(darkMode[0]);
            // disable load/save for multiplayer
            mgPanel.add(mgGame, BorderLayout.CENTER);

            // ---- style multiplayer top bar (dynamic) ----
            Runnable styleMgBar = () -> {
                int w = mgPanel.getWidth();
                if (w < 100) w = 540;
                boolean d = darkMode[0];
                Color bg = d ? new Color(0x1A1A18) : new Color(0xBBADA0);
                Color fg = d ? new Color(0xB0A898) : new Color(0x776E65);

                int arrowFs = Math.max(14, Math.min(22, w / 28));
                int oppFs   = Math.max(11, Math.min(20, w / 30));
                int iconSz  = Math.max(10, Math.min(20, w / 30));
                int gap     = Math.max(4, w / 60);

                mgTopBar.setBackground(bg);
                mgTopBar.setBorder(BorderFactory.createEmptyBorder(gap, gap * 2, gap, gap * 2));

                mgBackBtn.setFont(new Font("SansSerif", Font.BOLD, arrowFs));
                mgBackBtn.setForeground(fg);

                mgOppLabel.setFont(new Font("SansSerif", Font.PLAIN, oppFs));
                mgOppLabel.setForeground(fg);

                mgDarkBtn.setIcon(new MoonIcon(iconSz));
                mgDarkBtn.setBackground(bg);
                mgDarkBtn.setForeground(d ? Color.WHITE : fg);
                mgDarkBtn.setBorder(BorderFactory.createEmptyBorder(gap, gap, gap, gap));

                mgPinBtn.setIcon(new PinIcon(iconSz, false));
                mgPinBtn.setSelectedIcon(new PinIcon(iconSz, true));
                mgPinBtn.setBackground(bg);
                mgPinBtn.setBorder(BorderFactory.createEmptyBorder(gap, gap, gap, gap));
                mgPinBtn.setForeground(mgPinBtn.isSelected() ? Color.RED : fg);

                ((FlowLayout) mgRight.getLayout()).setHgap(gap * 2);
            };
            styleMgBar.run();
            mgPanel.addComponentListener(new java.awt.event.ComponentAdapter() {
                public void componentResized(java.awt.event.ComponentEvent e) { styleMgBar.run(); }
            });

            mgDarkBtn.addActionListener(e -> {
                darkMode[0] = mgDarkBtn.isSelected();
                mgGame.setDarkMode(darkMode[0]);
                styleMgBar.run();
            });

            // ==================== add cards ====================

            mainPanel.add(menuPanel, "menu");
            mainPanel.add(spPanel, "singleplayer");
            mainPanel.add(mSetupWrapper, "multiplayerSetup");
            mainPanel.add(mgPanel, "multiplayerGame");
            cards.show(mainPanel, "menu");

            frame.add(mainPanel);
            frame.pack();
            // ---- frame-level window listener (save single-player state) ----
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) {
                    spGame.saveGame();
                    if (mgGame.isInMultiplayer()) mgGame.leaveMultiplayer();
                    frame.dispose();
                    System.exit(0);
                }
            });
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            // ==================== button wiring ====================

            btnSingle.addActionListener(e -> {
                refreshMenuHs.run();
                if (spHsLabel[0] != null) spHsLabel[0].setText("Best: " + spGame.getHighScore());
                cards.show(mainPanel, "singleplayer");
                spGame.requestFocusInWindow();
            });

            spBackBtn.addActionListener(e -> {
                spGame.saveGame();
                refreshMenuHs.run();
                cards.show(mainPanel, "menu");
            });

            btnMulti.addActionListener(e -> {
                styleSetup.run();
                ipLabel.setText("Your IP: " + NetworkManager.getLocalIP());
                statusLabel.setText("");
                cards.show(mainPanel, "multiplayerSetup");
            });

            // shared ref so back button can cancel
            final NetworkManager[] pendingNet = {null};

            mBackBtn.addActionListener(e -> {
                if (pendingNet[0] != null) { pendingNet[0].close(); pendingNet[0] = null; }
                hostBtn.setEnabled(true);
                joinBtn.setEnabled(true);
                statusLabel.setText("");
                cards.show(mainPanel, "menu");
            });

            // ---- host ----
            hostBtn.addActionListener(e -> {
                int port;
                try { port = Integer.parseInt(portField.getText().trim()); }
                catch (NumberFormatException ex) { statusLabel.setText("Invalid port"); return; }
                statusLabel.setText("Waiting for opponent...");
                hostBtn.setEnabled(false);
                joinBtn.setEnabled(false);

                NetworkManager nm = new NetworkManager();
                pendingNet[0] = nm;
                nm.host(port,
                    () -> {  // onConnected (also fires on reconnection)
                        pendingNet[0] = null;
                        boolean wasMulti = mgGame.isInMultiplayer();
                        if (!wasMulti) mgGame.reset();
                        mgGame.setMultiplayer(nm);
                        nm.sendSync(mgGame.getScore(), mgGame.getBoardSnapshot());
                        styleMgBar.run();
                        cards.show(mainPanel, "multiplayerGame");
                        mgGame.requestFocusInWindow();
                        hostBtn.setEnabled(true);
                        joinBtn.setEnabled(true);
                    },
                    err -> {  // onError
                        pendingNet[0] = null;
                        statusLabel.setText(err);
                        hostBtn.setEnabled(true);
                        joinBtn.setEnabled(true);
                    },
                    () -> { mgGame.opponentDead = true; mgGame.repaint(); },         // onDead
                    () -> { mgGame.opponentScore = nm.getOpponentScore(); mgGame.repaint(); }, // onScore
                    () -> {                                                          // onDisconnect
                        mgGame.disconnected = true;
                        if (mgGame.spectating) mgGame.spectating = false;
                        mgGame.repaint();
                    },
                    b -> {                                                           // onBoard
                        for (int r = 0; r < SIZE; r++)
                            System.arraycopy(b[r], 0, mgGame.opponentBoard[r], 0, SIZE);
                        mgGame.repaint();
                    },
                    () -> { mgGame.beingWatched = nm.isOpponentWatching(); mgGame.repaint(); } // onWatchingChanged
                );
            });

            // ---- join ----
            joinBtn.addActionListener(e -> {
                String host = joinIpField.getText().trim();
                if (host.isEmpty()) { statusLabel.setText("Enter server IP"); return; }
                int port;
                try { port = Integer.parseInt(portField.getText().trim()); }
                catch (NumberFormatException ex) { statusLabel.setText("Invalid port"); return; }
                statusLabel.setText("Connecting...");
                hostBtn.setEnabled(false);
                joinBtn.setEnabled(false);

                NetworkManager nm = new NetworkManager();
                pendingNet[0] = nm;
                nm.join(host, port,
                    () -> {  // onConnected
                        pendingNet[0] = null;
                        boolean wasMulti = mgGame.isInMultiplayer();
                        if (!wasMulti) mgGame.reset();
                        mgGame.setMultiplayer(nm);
                        nm.sendScore(mgGame.getScore());
                        nm.sendBoard(mgGame.getBoardSnapshot());
                        styleMgBar.run();
                        cards.show(mainPanel, "multiplayerGame");
                        mgGame.requestFocusInWindow();
                        hostBtn.setEnabled(true);
                        joinBtn.setEnabled(true);
                    },
                    err -> {  // onError
                        pendingNet[0] = null;
                        statusLabel.setText(err);
                        hostBtn.setEnabled(true);
                        joinBtn.setEnabled(true);
                    },
                    () -> { mgGame.opponentDead = true; mgGame.repaint(); },         // onDead
                    () -> { mgGame.opponentScore = nm.getOpponentScore(); mgGame.repaint(); }, // onScore
                    () -> {                                                          // onDisconnect
                        mgGame.disconnected = true;
                        if (mgGame.spectating) mgGame.spectating = false;
                        mgGame.repaint();
                    },
                    b -> {                                                           // onBoard
                        for (int r = 0; r < SIZE; r++)
                            System.arraycopy(b[r], 0, mgGame.opponentBoard[r], 0, SIZE);
                        mgGame.repaint();
                    },
                    () -> { mgGame.beingWatched = nm.isOpponentWatching(); mgGame.repaint(); } // onWatchingChanged
                );
            });

            // ---- back from multiplayer game ----
            mgBackBtn.addActionListener(e -> {
                mgGame.leaveMultiplayer();
                cards.show(mainPanel, "menu");
            });

            // ---- opponent score update ----
            // We need to periodically check or have the game repaint trigger updates.
            // The NetworkManager listener already calls mgGame.repaint() indirectly via setMultiplayer.
            // But mgOppLabel also needs updating — we'll use a lightweight timer.
            javax.swing.Timer mgTimer = new javax.swing.Timer(500, e -> {
                if (mgGame.isInMultiplayer() && mgGame.network != null) {
                    String t;
                    if (mgGame.disconnected) {
                        t = "Opponent disconnected";
                    } else {
                        t = "Opponent: " + mgGame.network.getOpponentScore();
                        if (mgGame.opponentDead) t += " (dead)";
                        if (mgGame.beingWatched) t += " ●";
                    }
                    mgOppLabel.setText(t);
                    if (mgGame.beingWatched && !mgGame.disconnected)
                        mgOppLabel.setForeground(new Color(0x4CAF50));
                    else if (mgGame.disconnected)
                        mgOppLabel.setForeground(Color.RED);
                    else {
                        boolean d = darkMode[0];
                        mgOppLabel.setForeground(d ? new Color(0xB0A898) : new Color(0x776E65));
                    }
                }
            });
            mgTimer.start();
        });
    }
}
