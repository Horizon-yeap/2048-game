# 2048 Game

Swing 桌面 2048 游戏，带局域网联机对战功能。零外部依赖，JDK 8+。

## 构建 & 运行

```bash
cd ~/projects/2048-game
mvn clean package                              # 编译打包
java -jar target/game2048-1.0.jar             # 启动
```

## 项目结构

```
src/main/java/com/example/game/
├── Game2048.java        # 游戏主体：棋盘逻辑、UI渲染、主入口main()
└── NetworkManager.java  # TCP网络层：host/join、协议收发
```

## 架构

- **UI**：Swing JPanel + CardLayout（4个面板：菜单、单人、联机设置、联机对战）
- **联网**：对等 TCP，一方 Host（ServerSocket accept 循环），一方 Join（Socket 连接）。Host 在客户端断开后自动回到 accept 等待重连
- **线程**：网络操作在后台线程，UI 回调通过 `SwingUtilities.invokeLater` 切回 EDT

## 联机协议

纯文本行，`\n` 分割：

| 消息 | 含义 |
|------|------|
| `SCORE <int>` | 当前得分 |
| `DEAD` | 我已死 |
| `READY` | 重新开始 |
| `BOARD <16 csv>` | 棋盘快照（观战用） |
| `WATCHING` / `UNWATCHING` | 观战切换 |
| `SYNC <score>,<16 csv>` | 重连状态同步 |
| `DISCONNECT` | 断开连接 |

## 联机功能特性

- G 键随时切换观战对手棋盘
- 绿色圆点表示对手正在观战你
- 双方同时观战显示 "不要再视奸对方了"
- 死亡后可选择观战或重新开始
- 一方断开不影响另一方，可重连恢复
- Host 端口需允许局域网访问（macOS 防火墙）

## 用户偏好

- 用户是 Java 初学者，解释时用语简单直接
- 偏好中文交流
- 代码改动后需要 `mvn clean package` 验证编译通过
- macOS + Windows 跨平台
