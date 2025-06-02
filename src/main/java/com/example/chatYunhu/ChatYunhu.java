package com.liyang.chatyunhu;

import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class ChatYunhu extends JavaPlugin implements Listener {

    private HttpClient httpClient;
    private String botToken;
    private String groupId;
    private String botUserId;
    private boolean enableJoinMessage;
    private boolean enableQuitMessage;
    private Set<String> blockedWords = new HashSet<>();
    private boolean blockCommands;
    private ScheduledExecutorService scheduler;
    private String lastMessageId = "";
    private final ConcurrentMap<String, Boolean> processedMessages = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    @Override
    public void onEnable() {
        // 保存默认配置
        saveDefaultConfig();
        // 加载配置
        reloadConfig();
        
        // 注册事件监听器
        getServer().getPluginManager().registerEvents(this, this);
        
        // 启动群消息轮询
        startGroupMessagePoller();
        
        getLogger().info("§aChatYunhu 插件已启用！");
    }

    @Override
    public void onDisable() {
        // 关闭定时任务
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }
        getLogger().info("§cChatYunhu 插件已禁用！");
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        FileConfiguration config = getConfig();
        
        // 从配置加载设置
        botToken = config.getString("bot-token", "");
        groupId = config.getString("group-id", "");
        botUserId = config.getString("bot-user-id", "");
        enableJoinMessage = config.getBoolean("enable-join-message", true);
        enableQuitMessage = config.getBoolean("enable-quit-message", true);
        blockCommands = config.getBoolean("block-commands", true);
        lastMessageId = config.getString("last-message-id", "");
        
        // 加载屏蔽词列表
        blockedWords.clear();
        List<String> words = config.getStringList("blocked-words");
        blockedWords.addAll(words);
        
        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        
        getLogger().info("§7配置已重新加载！");
        getLogger().info("§7已加载 " + blockedWords.size() + " 个屏蔽词");
    }

    private void startGroupMessagePoller() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        // 每3秒获取一次群消息
        scheduler.scheduleAtFixedRate(this::pollGroupMessages, 0, 3, TimeUnit.SECONDS);
    }

    private void pollGroupMessages() {
        if (botToken.isEmpty() || groupId.isEmpty() || botUserId.isEmpty()) {
            getLogger().warning("§c机器人配置不完整，无法获取群消息！");
            return;
        }

        try {
            String url = "https://chat-go.jwzhd.com/open-apis/v1/bot/messages?token=" + botToken +
                    "&chat-id=" + groupId +
                    "&chat-type=group";
            
            if (!lastMessageId.isEmpty()) {
                url += "&message-id=" + lastMessageId + "&after=10";
            } else {
                url += "&before=1"; // 第一次只获取最新的一条消息
            }
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                if (jsonResponse.get("code").getAsInt() == 1) {
                    JsonObject data = jsonResponse.getAsJsonObject("data");
                    JsonArray messageList = data.getAsJsonArray("list");
                    
                    // 处理新消息
                    List<JsonObject> newMessages = new ArrayList<>();
                    for (JsonElement element : messageList) {
                        JsonObject msg = element.getAsJsonObject();
                        String msgId = msg.get("msgId").getAsString();
                        
                        // 跳过已处理的消息
                        if (processedMessages.containsKey(msgId)) continue;
                        
                        // 跳过机器人自己发的消息
                        if (msg.get("senderId").getAsString().equals(botUserId)) continue;
                        
                        newMessages.add(msg);
                        processedMessages.put(msgId, true);
                        
                        // 更新最后一条消息ID
                        lastMessageId = msgId;
                        getConfig().set("last-message-id", lastMessageId);
                        saveConfig();
                    }
                    
                    // 按时间顺序处理消息（从旧到新）
                    Collections.reverse(newMessages);
                    
                    // 在主线程广播消息
                    if (!newMessages.isEmpty()) {
                        Bukkit.getScheduler().runTask(this, () -> {
                            for (JsonObject msg : newMessages) {
                                broadcastGroupMessage(msg);
                            }
                        });
                    }
                } else {
                    getLogger().warning("§c获取群消息失败: " + jsonResponse.get("msg").getAsString());
                }
            } else {
                getLogger().warning("§c获取群消息失败，HTTP状态码: " + response.statusCode());
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "§c获取群消息异常: " + e.getMessage(), e);
        }
    }

    private void broadcastGroupMessage(JsonObject msg) {
        String senderNickname = msg.get("senderNickname").getAsString();
        String contentType = msg.get("contentType").getAsString();
        JsonObject content = msg.getAsJsonObject("content");
        String messageText = "";
        
        if (contentType.equals("text") || contentType.equals("markdown")) {
            messageText = content.get("text").getAsString();
        } else {
            messageText = "[" + contentType + "] 类型消息";
        }
        
        // 检查是否包含屏蔽词
        if (containsBlockedWord(messageText)) {
            getLogger().info("§7群消息包含屏蔽词: " + messageText);
            return;
        }
        
        String formattedMessage = String.format("§9[群聊] §b%s§f: §7%s", senderNickname, messageText);
        Bukkit.broadcastMessage(formattedMessage);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage();
        Player player = event.getPlayer();
        
        // 检查是否为命令消息
        if (blockCommands && message.startsWith("/")) {
            getLogger().info("§7忽略命令消息: " + message);
            return;
        }
        
        // 检查是否包含屏蔽词
        if (containsBlockedWord(message)) {
            getLogger().info("§7消息包含屏蔽词: " + message);
            event.setCancelled(true);
            player.sendMessage("§c你的消息包含不当内容，已被阻止发送！");
            return;
        }
        
        String formattedMessage = String.format("§a<%s> §f%s", player.getName(), message);
        sendMessageToBot(formattedMessage);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enableJoinMessage) return;
        String message = String.format("§a%s §7加入了服务器", event.getPlayer().getName());
        sendMessageToBot(message);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!enableQuitMessage) return;
        String message = String.format("§c%s §7退出了服务器", event.getPlayer().getName());
        sendMessageToBot(message);
    }
    
    private boolean containsBlockedWord(String message) {
        String lowerMessage = message.toLowerCase();
        for (String word : blockedWords) {
            if (lowerMessage.contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    private void sendMessageToBot(String message) {
        if (botToken.isEmpty() || groupId.isEmpty()) {
            getLogger().warning("§c机器人配置不完整，无法发送消息！");
            return;
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("recvId", groupId);
        requestBody.addProperty("recvType", "group");
        requestBody.addProperty("contentType", "text");
        
        JsonObject content = new JsonObject();
        content.addProperty("text", message);
        requestBody.add("content", content);
        
        // 异步发送请求避免阻塞主线程
        CompletableFuture.runAsync(() -> {
            try {
                // 带重试机制的发送
                sendWithRetry(requestBody.toString(), 3, 2000);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "§c消息转发失败: " + e.getMessage());
            }
        });
    }
    
    private void sendWithRetry(String jsonBody, int maxRetries, long delayMillis) {
        int retryCount = 0;
        boolean success = false;
        Exception lastException = null;
        
        while (retryCount < maxRetries && !success) {
            try {
                HttpResponse<String> response = sendToBot(jsonBody);
                if (response.statusCode() == 200) {
                    JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (jsonResponse.get("code").getAsInt() == 1) {
                        success = true;
                        getLogger().info("§a消息转发成功!");
                    } else {
                        getLogger().warning("§c机器人接口返回错误: " + jsonResponse.get("msg").getAsString());
                        retryCount++;
                        if (retryCount < maxRetries) {
                            Thread.sleep(delayMillis);
                        }
                    }
                } else {
                    getLogger().warning("§cHTTP错误: " + response.statusCode());
                    retryCount++;
                    if (retryCount < maxRetries) {
                        Thread.sleep(delayMillis);
                    }
                }
            } catch (IOException | InterruptedException e) {
                lastException = e;
                retryCount++;
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } catch (JsonSyntaxException e) {
                lastException = e;
                getLogger().warning("§c解析机器人响应失败: " + e.getMessage());
                break;
            }
        }
        
        if (!success) {
            String errorMsg = lastException != null ? lastException.getMessage() : "未知错误";
            getLogger().log(Level.WARNING, "§c消息转发失败，重试后仍然失败: " + errorMsg);
        }
    }

    private HttpResponse<String> sendToBot(String jsonBody) throws IOException, InterruptedException {
        String url = "https://chat-go.jwzhd.com/open-apis/v1/bot/send?token=" + botToken;
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}