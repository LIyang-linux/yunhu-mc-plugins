# ChatYunhu

## 插件概述

**ChatYunhu** 是一个专为 Minecraft 服务器设计的**双向聊天转发插件**，它无缝连接游戏内聊天与外部群聊平台，实现服务器玩家与群成员之间的实时交流。由 **LiYang** 开发，当前版本 **v0.0.1**。

```mermaid
graph LR
A[Minecraft服务器] -->|玩家聊天/事件| B(ChatYunhu)
B -->|转发| C[群聊平台]
C -->|群成员消息| B
B -->|广播| A
```

### 服务器效果

[![](https://github.com/LIyang-linux/yunhu-mc-plugins/blob/main/pictures/1.png?raw=true)](https://github.com/LIyang-linux/yunhu-mc-plugins/blob/main/pictures/1.png?raw=true)

## 核心功能

### 1. 双向消息转发
- **服务器 → 群聊**
  - 玩家聊天消息
  - 玩家加入/退出通知
  - 游戏事件提醒（可扩展）
  
- **群聊 → 服务器**
  - 实时获取群消息（每3秒轮询）
  - 自动格式化为游戏内消息
  - 智能过滤机器人自身消息

### 2. 智能消息过滤系统
```yaml
# 配置示例
blocked-words:
  - "脏话1"
  - "脏话2"
block-commands: true # 过滤命令消息
```

- **屏蔽词过滤**：自定义敏感词库（不区分大小写）
- **命令过滤**：自动忽略以 `/` 开头的命令消息
- **消息类型过滤**：仅处理文本类消息（text/markdown）

### 3. 高可靠性设计
- **自动重试机制**：HTTP请求失败时自动重试（最多3次）
- **消息ID追踪**：记录最后处理的消息ID，避免重复
- **错误隔离**：单次请求失败不影响整体运行

### 4. 美观的消息格式
- **游戏内群聊消息**  
  `§9[群聊] §b用户名§f: §7消息内容`
- **服务器转发到群聊的消息**  
  `<玩家名> 聊天内容`  
  `玩家名 加入了服务器`

## 安装指南

### 环境要求
- **服务端**：Paper 1.20.4 或兼容衍生版
- **Java**：JDK 17+
- **网络**：可访问 `chat-go.jwzhd.com`

### 安装步骤
1. 下载插件：[chatyunhu-0.0.1.jar](https://github.com/LIyang-linux/yunhu-mc-plugins/releases/download/Bata/chatyunhu-0.0.1.jar)
2. 将 JAR 文件放入服务器的 `plugins` 目录
3. 重启服务器
4. 编辑生成的配置文件：
   ```bash
   plugins/ChatYunhu/config.yml
   ```

## ⚙️ 配置说明

### 配置文件示例
```yaml
# 机器人token（必填）
bot-token: "your_bot_token_here"

# 接收消息的群组ID（必填）
group-id: "your_group_id"

# 机器人用户ID（必填，避免消息循环）
bot-user-id: "your_bot_user_id"

# 功能开关
enable-join-message: true  # 玩家加入通知
enable-quit-message: true  # 玩家退出通知
block-commands: true       # 屏蔽命令消息

# 屏蔽词设置
blocked-words:
  - "fuck"
  - "shit"
  - "攻击性词汇"

# 自动记录（无需手动修改）
last-message-id: ""
```

### 重要配置项说明
| 配置项 | 必填 | 说明 |
|--------|------|------|
| **bot-token** | 是 | 机器人API令牌 |
| **group-id** | 是 | 接收消息的群组ID |
| **bot-user-id** | 是 | 机器人自身用户ID（防消息循环） |
| **blocked-words** | 否 | 自定义屏蔽词列表 |
| **last-message-id** | 否 | 自动记录最后处理的消息ID |

## 技术支持

### 常见问题解决
1. **消息未转发**  
    检查 `bot-token` 和 `group-id` 是否正确  
    确认服务器能访问 `chat-go.jwzhd.com`

2. **消息循环**  
    确保正确设置 `bot-user-id`

3. **屏蔽词不生效**  
    检查配置文件格式（使用空格缩进）  
    确认重启服务器使配置生效

### 编译教
```bash
# 使用Maven编译
mvn clean package -DskipTests

# 构建成功后在target目录获取：
chatyunhu-0.0.1.jar
```

## 开源许可
本项目采用 **MIT 许可证**，允许自由使用、修改和分发，需保留原作者署名。

```license
MIT License
```
