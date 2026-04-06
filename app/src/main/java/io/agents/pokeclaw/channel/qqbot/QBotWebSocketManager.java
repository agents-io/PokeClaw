// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.qqbot;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import io.agents.pokeclaw.utils.XLog;

import io.agents.pokeclaw.channel.qqbot.model.AccessTokenResponse;
import io.agents.pokeclaw.channel.qqbot.model.C2CMessage;
import io.agents.pokeclaw.channel.qqbot.model.GatewayResponse;
import io.agents.pokeclaw.channel.qqbot.model.GroupAtMessage;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class QBotWebSocketManager {
    private static final String TAG = "QBotWebSocketManager";
    private static volatile QBotWebSocketManager instance;
    private OkHttpClient httpClient;
    private volatile WebSocket webSocket;
    private Gson gson;
    private Handler mainHandler;
    private Handler heartbeatHandler;
    private volatile long heartbeatInterval;
    private volatile String sessionId;
    private volatile Integer lastSeq;
    private volatile String gatewayUrl;
    private volatile QBotCallback<String> eventCallback;
    private volatile boolean isConnected;
    private volatile boolean heartbeatAckReceived = true;
    private volatile boolean stopped = false;
    private volatile boolean isReconnecting = false;
    private int shardCount = 1;
    private int currentShard = 0;

    /** 收到 QQ 消息时回调（单聊/群聊），由 ChannelManager 设置 */
    private volatile OnQQMessageListener qqMessageListener;

    /** 消息 ID 去重缓存，防止 WebSocket 重连后重复处理同一消息（最多保留 100 条） */
    private final java.util.Set<String> recentMessageIds = java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<String, Boolean>(100, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                    return size() > 100;
                }
            });

    private List<ConnectionStateListener> connectionStateListeners = new CopyOnWriteArrayList<>();

    public interface OnQQMessageListener {
        void onQQMessage(boolean isGroup, String openId, String messageId, String content);
    }
    
    /**
     * 连接状态监听器接口
     */
    public interface ConnectionStateListener {
        void onConnectionStateChanged(boolean connected);
    }

    private QBotWebSocketManager() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        gson = new Gson();
        mainHandler = new Handler(Looper.getMainLooper());
        heartbeatHandler = new Handler(Looper.getMainLooper());
    }

    public static QBotWebSocketManager getInstance() {
        if (instance == null) {
            synchronized (QBotWebSocketManager.class) {
                if (instance == null) {
                    instance = new QBotWebSocketManager();
                }
            }
        }
        return instance;
    }

    public void setOnQQMessageListener(OnQQMessageListener listener) {
        this.qqMessageListener = listener;
    }

    /**
     * 添加连接状态监听器
     * @param listener 监听器
     */
    public void addConnectionStateListener(ConnectionStateListener listener) {
        if (!connectionStateListeners.contains(listener)) {
            connectionStateListeners.add(listener);
        }
    }
    
    /**
     * 移除连接状态监听器
     * @param listener 监听器
     */
    public void removeConnectionStateListener(ConnectionStateListener listener) {
        connectionStateListeners.remove(listener);
    }
    
    /**
     * 通知所有监听器连接状态改变
     * @param connected 是否连接
     */
    private void notifyConnectionStateChanged(boolean connected) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (ConnectionStateListener listener : connectionStateListeners) {
                    listener.onConnectionStateChanged(connected);
                }
            }
        });
    }

    /**
     * 设置事件回调
     * @param callback 事件回调
     */
    public void setEventCallback(QBotCallback<String> callback) {
        this.eventCallback = callback;
    }
    
    /**
     * 开始WebSocket连接
     */
    public void start() {
        stopped = false;
        getGatewayUrl(new QBotCallback<GatewayResponse>() {
            @Override
            public void onSuccess(GatewayResponse gatewayResponse) {
                gatewayUrl = gatewayResponse.getUrl();
                shardCount = gatewayResponse.getShards();
                XLog.d(TAG, "获取到网关地址: " + gatewayUrl + ", 建议分片数: " + shardCount);
                connectWebSocket(gatewayUrl);
            }
            
            @Override
            public void onFailure(QBotException e) {
                XLog.e(TAG, "获取网关地址失败: " + e.getMessage());
                if (eventCallback != null) {
                    eventCallback.onFailure(e);
                }
            }
        });
    }
    
    /**
     * 关闭WebSocket连接
     */
    public void stop() {
        stopped = true;
        isReconnecting = false;
        mainHandler.removeCallbacksAndMessages(null);
        heartbeatHandler.removeCallbacksAndMessages(null);
        if (webSocket != null) {
            webSocket.close(1000, "关闭连接");
            webSocket = null;
        }
        isConnected = false;
        sessionId = null;
        lastSeq = null;
        notifyConnectionStateChanged(false);
    }
    
    /**
     * 获取网关地址
     */
    private void getGatewayUrl(QBotCallback<GatewayResponse> callback) {
        QBotApiClient apiClient = QBotApiClient.getInstance();
        apiClient.getAccessToken(new QBotCallback<AccessTokenResponse>() {
            @Override
            public void onSuccess(AccessTokenResponse response) {
                String authHeader = apiClient.getAuthorizationHeader();
                XLog.d(TAG, "使用 Authorization: " + (authHeader != null ? authHeader.substring(0, Math.min(authHeader.length(), 20)) + "..." : "null"));

                String gatewayUrl = QBotConstants.API_BASE_URL + QBotConstants.GET_GATEWAY_URL;
                Request request = new Request.Builder()
                        .url(gatewayUrl)
                        .get()
                        .addHeader(QBotConstants.HEADER_AUTHORIZATION, authHeader)
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        callback.onFailure(new QBotException("获取网关地址失败: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        String responseBody = response.body() != null ? response.body().string() : "";
                        if (response.isSuccessful()) {
                            GatewayResponse gatewayResponse = gson.fromJson(responseBody, GatewayResponse.class);
                            if (gatewayResponse.getUrl() == null || gatewayResponse.getUrl().isEmpty()) {
                                XLog.e(TAG, "网关返回无 url, body=" + responseBody);
                                callback.onFailure(new QBotException("网关返回数据异常: 无 url"));
                            } else {
                                callback.onSuccess(gatewayResponse);
                            }
                        } else {
                            XLog.e(TAG, "获取网关失败 code=" + response.code() + ", body=" + responseBody);
                            callback.onFailure(new QBotException("获取网关地址失败: " + response.code() + " " + responseBody));
                        }
                    }
                });
            }
            
            @Override
            public void onFailure(QBotException e) {
                callback.onFailure(e);
            }
        });
    }
    
    /**
     * 连接WebSocket
     */
    private void connectWebSocket(String url) {
        // 关闭旧连接，避免多个 WebSocket 并存
        WebSocket oldSocket = webSocket;
        if (oldSocket != null) {
            try {
                oldSocket.close(1000, "新连接替换");
            } catch (Exception ignored) {}
            webSocket = null;
        }

        Request request = new Request.Builder()
                .url(url)
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                XLog.d(TAG, "WebSocket连接成功, response=" + response);
                isConnected = true;
                notifyConnectionStateChanged(true);
            }
            
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                XLog.d(TAG, "收到原始消息, 长度=" + text.length());
                handleWebSocketMessage(text);
            }
            
            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                XLog.d(TAG, "收到二进制消息, 长度=" + bytes.size());
            }
            
            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                XLog.w(TAG, "WebSocket关闭中: code=" + code + ", reason=" + reason);
                // onClosed 会紧接着触发，这里只标记状态，不处理重连逻辑
                isConnected = false;
                notifyConnectionStateChanged(false);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                // 如果当前 webSocket 已经被新连接替换，忽略旧连接的回调
                if (webSocket != QBotWebSocketManager.this.webSocket) return;
                XLog.w(TAG, "WebSocket已关闭: code=" + code + ", reason=" + reason);
                isConnected = false;
                notifyConnectionStateChanged(false);
                handleWebSocketClose(code, reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                // 如果当前 webSocket 已经被新连接替换，忽略旧连接的回调
                if (webSocket != QBotWebSocketManager.this.webSocket) return;
                XLog.e(TAG, "WebSocket连接失败: " + (t != null ? t.getMessage() : "null") + ", response=" + response);
                isConnected = false;
                notifyConnectionStateChanged(false);
                if (!stopped) {
                    reconnect();
                }
            }
        });
    }
    
    /**
     * 处理WebSocket消息
     */
    private void handleWebSocketMessage(String message) {
        try {
            WebSocketMessage wsMessage = gson.fromJson(message, WebSocketMessage.class);
            int op = wsMessage.getOp();
            String t = wsMessage.getT();
            XLog.d(TAG, "收到消息: op=" + op + ", t=" + t + ", s=" + wsMessage.getS());
            
            switch (op) {
                case QBotConstants.OP_HELLO:
                    handleHello(wsMessage);
                    break;
                case QBotConstants.OP_DISPATCH:
                    handleDispatch(wsMessage);
                    break;
                case QBotConstants.OP_HEARTBEAT_ACK:
                    handleHeartbeatAck();
                    break;
                case 7:
                    XLog.w(TAG, "收到重连请求(OP=7)，准备重连");
                    handleReconnectRequest();
                    break;
                case 9:
                    handleInvalidSession(wsMessage);
                    break;
                default:
                    XLog.w(TAG, "未知OpCode: " + op);
            }
        } catch (Exception e) {
            XLog.e(TAG, "解析WebSocket消息失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理Hello消息
     */
    private void handleHello(WebSocketMessage message) {
        try {
            JsonObject helloData = gson.fromJson(gson.toJson(message.getD()), JsonObject.class);
            heartbeatInterval = helloData.get("heartbeat_interval").getAsLong();
            XLog.d(TAG, "心跳间隔: " + heartbeatInterval + "ms");
            
            // 开始发送心跳
            startHeartbeat();
            
            // 发送Identify消息
            sendIdentify();
        } catch (Exception e) {
            XLog.e(TAG, "处理Hello消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理Dispatch消息
     */
    private void handleDispatch(WebSocketMessage message) {
        // 更新seq
        if (message.getS() != null) {
            lastSeq = message.getS();
        }
        
        // 处理不同类型的事件
        String eventType = message.getT();
        if (eventType != null) {
            switch (eventType) {
                case QBotConstants.EVENT_READY:
                    handleReady(message);
                    break;
                case QBotConstants.EVENT_RESUMED:
                    handleResumed(message);
                    break;
                case QBotConstants.EVENT_C2C_MESSAGE_CREATE:
                    handleC2CMessage(message);
                    break;
                case QBotConstants.EVENT_GROUP_AT_MESSAGE_CREATE:
                    handleGroupAtMessage(message);
                    break;
                default:
                    // 其他事件
                    if (eventCallback != null) {
                        eventCallback.onSuccess(gson.toJson(message));
                    }
            }
        }
    }
    
    /**
     * 处理Ready事件
     */
    private void handleReady(WebSocketMessage message) {
        try {
            JsonObject readyData = gson.fromJson(gson.toJson(message.getD()), JsonObject.class);
            sessionId = readyData.get("session_id").getAsString();
            XLog.d(TAG, "获取到SessionId: " + sessionId);
            XLog.d(TAG, "WebSocket连接已就绪，开始接收事件");
            
            if (eventCallback != null) {
                eventCallback.onSuccess(gson.toJson(message));
            }
        } catch (Exception e) {
            XLog.e(TAG, "处理Ready事件失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理Resumed事件
     */
    private void handleResumed(WebSocketMessage message) {
        XLog.d(TAG, "连接已恢复");
        if (eventCallback != null) {
            eventCallback.onSuccess(gson.toJson(message));
        }
    }
    
    /**
     * 处理单聊消息事件
     * 用户在单聊发送消息给机器人时触发
     */
    private void handleC2CMessage(WebSocketMessage message) {
        try {
            String messageData = gson.toJson(message.getD());
            C2CMessage c2cMessage = gson.fromJson(messageData, C2CMessage.class);
            
            XLog.d(TAG, "收到单聊消息:");
            XLog.d(TAG, "  消息ID: " + c2cMessage.getId());
            XLog.d(TAG, "  用户OpenID: " + (c2cMessage.getAuthor() != null ? c2cMessage.getAuthor().getUserOpenid() : "null"));
            XLog.d(TAG, "  内容: " + c2cMessage.getContent());
            XLog.d(TAG, "  时间: " + c2cMessage.getTimestamp());

            if (c2cMessage.getAttachments() != null && !c2cMessage.getAttachments().isEmpty()) {
                XLog.d(TAG, "  附件数量: " + c2cMessage.getAttachments().size());
            }

            // 消息 ID 去重
            String msgId = c2cMessage.getId();
            if (msgId != null && !recentMessageIds.add(msgId)) {
                XLog.d(TAG, "单聊消息去重，跳过: " + msgId);
                return;
            }

            String userOpenId = c2cMessage.getAuthor() != null ? c2cMessage.getAuthor().getUserOpenid() : null;
            if (userOpenId != null) {
                OnQQMessageListener listener = qqMessageListener;
                if (listener != null) {
                    listener.onQQMessage(false, userOpenId, c2cMessage.getId(), c2cMessage.getContent() != null ? c2cMessage.getContent() : "");
                }
            }

            if (eventCallback != null) {
                eventCallback.onSuccess(gson.toJson(message));
            }
        } catch (Exception e) {
            XLog.e(TAG, "处理单聊消息失败: " + e.getMessage());
        }
    }

    /**
     * 处理群聊@机器人消息事件
     * 用户在群里@机器人时收到的消息
     */
    private void handleGroupAtMessage(WebSocketMessage message) {
        try {
            String messageData = gson.toJson(message.getD());
            GroupAtMessage groupMessage = gson.fromJson(messageData, GroupAtMessage.class);
            
            XLog.d(TAG, "收到群聊@机器人消息:");
            XLog.d(TAG, "  消息ID: " + groupMessage.getId());
            XLog.d(TAG, "  群OpenID: " + groupMessage.getGroupOpenid());
            XLog.d(TAG, "  发送者OpenID: " + (groupMessage.getAuthor() != null ? groupMessage.getAuthor().getMemberOpenid() : "null"));
            XLog.d(TAG, "  内容: " + groupMessage.getContent());

            // 消息 ID 去重
            String msgId = groupMessage.getId();
            if (msgId != null && !recentMessageIds.add(msgId)) {
                XLog.d(TAG, "群聊消息去重，跳过: " + msgId);
                return;
            }

            String groupOpenId = groupMessage.getGroupOpenid();
            if (groupOpenId != null) {
                OnQQMessageListener listener = qqMessageListener;
                if (listener != null) {
                    String content = groupMessage.getContent() != null ? groupMessage.getContent() : "";
                    listener.onQQMessage(true, groupOpenId, groupMessage.getId(), content);
                }
            }

            if (eventCallback != null) {
                eventCallback.onSuccess(gson.toJson(message));
            }
        } catch (Exception e) {
            XLog.e(TAG, "处理群聊@机器人消息失败: " + e.getMessage());
        }
    }

    /**
     * 处理无效会话
     */
    private void handleInvalidSession(WebSocketMessage message) {
        try {
            // OP 9 的 d 字段是一个布尔值（true=可恢复，false=不可恢复），不是 JsonObject
            Object d = message.getD();
            boolean canResume = false;
            if (d instanceof Boolean) {
                canResume = (Boolean) d;
            } else if (d != null) {
                canResume = Boolean.parseBoolean(d.toString());
            }

            if (canResume) {
                XLog.d(TAG, "会话可以恢复，尝试Resume");
                sendResume();
            } else {
                XLog.d(TAG, "会话无效，需要重新Identify");
                sessionId = null;
                lastSeq = null;
                sendIdentify();
            }
        } catch (Exception e) {
            XLog.e(TAG, "处理无效会话失败: " + e.getMessage());
            sessionId = null;
            lastSeq = null;
        }
    }
    
    /**
     * 处理服务器要求重连
     */
    private void handleReconnectRequest() {
        XLog.d(TAG, "服务器要求重连，断开当前连接并重新连接");
        if (webSocket != null) {
            webSocket.close(1000, "服务器要求重连");
            webSocket = null;
        }
        heartbeatHandler.removeCallbacksAndMessages(null);
        isConnected = false;
        notifyConnectionStateChanged(false);
        
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                reconnect();
            }
        }, 1000);
    }
    
    /**
     * 处理心跳响应
     */
    private void handleHeartbeatAck() {
        heartbeatAckReceived = true;
        XLog.d(TAG, "收到心跳响应");
    }
    
    /**
     * 处理WebSocket关闭错误码
     * @param code 关闭码
     * @param reason 关闭原因
     */
    private void handleWebSocketClose(int code, String reason) {
        XLog.d(TAG, "处理WebSocket关闭: code=" + code + ", reason=" + reason);
        
        switch (code) {
            case 4004:
                // 4004: Authentication fail — token 可能过期，刷新后重连
                Log.w(TAG, "鉴权失败(4004)，清除旧token，刷新后重连");
                // 清除旧 token 强制刷新
                QBotApiClient.getInstance().clearToken();
                heartbeatHandler.postDelayed(() -> {
                    Log.d(TAG, "4004 延迟重连，重新获取token");
                    start(); // start() 会重新获取 token → gateway → connect
                }, 3000);
                break;

            case QBotConstants.WS_ERROR_SESSION_TIMEOUT:
                // 4009: 连接过期，可以尝试 Resume
                XLog.d(TAG, "连接过期，尝试Resume重连");
                reconnect();
                break;

            case QBotConstants.WS_ERROR_BOT_OFFLINE:
                // 4914: 机器人已下架，只允许连接沙箱环境
                XLog.e(TAG, "机器人已下架，只允许连接沙箱环境，请断开连接并检验当前连接环境");
                if (eventCallback != null) {
                    eventCallback.onFailure(new QBotException("机器人已下架，只允许连接沙箱环境"));
                }
                break;
                
            case QBotConstants.WS_ERROR_BOT_BANNED:
                // 4915: 机器人已封禁，不允许连接
                XLog.e(TAG, "机器人已封禁，不允许连接，请申请解封后再连接");
                if (eventCallback != null) {
                    eventCallback.onFailure(new QBotException("机器人已封禁，不允许连接"));
                }
                break;
                
            case QBotConstants.WS_ERROR_RATE_LIMITED:
                // 4008: 发送 payload 过快，可以重连
                XLog.w(TAG, "发送payload过快，稍后重连");
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        reconnect();
                    }
                }, 5000); // 延迟5秒重连
                break;
                
            case QBotConstants.WS_ERROR_INVALID_OPCODE:
            case QBotConstants.WS_ERROR_INVALID_PAYLOAD:
            case QBotConstants.WS_ERROR_INVALID_SHARD:
            case QBotConstants.WS_ERROR_INVALID_VERSION:
            case QBotConstants.WS_ERROR_INVALID_INTENT:
            case QBotConstants.WS_ERROR_INTENT_NO_PERMISSION:
                // 这些错误需要重新 Identify
                XLog.e(TAG, "连接错误(code=" + code + ")，清除会话信息并重新Identify");
                sessionId = null;
                lastSeq = null;
                reconnect();
                break;
                
            case QBotConstants.WS_ERROR_SEQ_ERROR:
            case QBotConstants.WS_ERROR_INVALID_SESSION_ID:
                // seq错误或session id无效，需要重新 Identify
                XLog.w(TAG, "Session无效，清除会话信息并重新Identify");
                sessionId = null;
                lastSeq = null;
                reconnect();
                break;
                
            case QBotConstants.WS_ERROR_SHARD_REQUIRED:
                // 需要分片
                XLog.w(TAG, "需要分片处理，建议使用多个连接");
                reconnect();
                break;
                
            default:
                // 内部错误 (4900-4913) 或其他未知错误
                if (code >= QBotConstants.WS_ERROR_INTERNAL_MIN && code <= QBotConstants.WS_ERROR_INTERNAL_MAX) {
                    XLog.w(TAG, "服务器内部错误(" + code + ")，稍后重连");
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            reconnect();
                        }
                    }, 3000); // 延迟3秒重连
                } else if (code == 1000) {
                    // 正常关闭
                    XLog.d(TAG, "WebSocket正常关闭");
                } else {
                    // 其他未知错误，尝试重连
                    XLog.w(TAG, "未知错误码: " + code + "，尝试重连");
                    reconnect();
                }
                break;
        }
    }
    
    /**
     * 开始发送心跳
     */
    private void startHeartbeat() {
        XLog.d(TAG, "开始心跳, 间隔=" + heartbeatInterval + "ms");
        heartbeatAckReceived = true;
        heartbeatHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isConnected) {
                    XLog.w(TAG, "心跳检测: 连接已断开");
                    return;
                }
                if (!heartbeatAckReceived) {
                    XLog.w(TAG, "心跳超时：未收到上次心跳ACK，断开重连");
                    if (webSocket != null) {
                        webSocket.close(1000, "心跳超时");
                        webSocket = null;
                    }
                    isConnected = false;
                    notifyConnectionStateChanged(false);
                    reconnect();
                    return;
                }
                heartbeatAckReceived = false;
                sendHeartbeat();
                heartbeatHandler.postDelayed(this, heartbeatInterval);
            }
        }, heartbeatInterval);
    }
    
    /**
     * 发送心跳
     */
    private void sendHeartbeat() {
        JsonObject heartbeatMessage = new JsonObject();
        heartbeatMessage.addProperty("op", QBotConstants.OP_HEARTBEAT);
        heartbeatMessage.add("d", lastSeq != null ? gson.toJsonTree(lastSeq) : null);
        
        String message = gson.toJson(heartbeatMessage);
        XLog.d(TAG, "发送心跳: " + message);
        if (webSocket != null) {
            webSocket.send(message);
        }
    }
    
    /**
     * 发送Identify消息
     */
    private void sendIdentify() {
        QBotApiClient apiClient = QBotApiClient.getInstance();
        String token = apiClient.getAuthorizationHeader();
        
        JsonObject identifyMessage = new JsonObject();
        identifyMessage.addProperty("op", QBotConstants.OP_IDENTIFY);
        
        JsonObject data = new JsonObject();
        data.addProperty("token", token);
        // 只订阅必要的事件，避免因缺少权限被 4004 拒绝连接：
        // - GROUP_AND_C2C_EVENT (1<<25): 群聊 @机器人消息 + 单聊消息（核心功能）
        // 如需频道消息，可按需加上 INTENT_PUBLIC_GUILD_MESSAGES (1<<30)，但需要在 QQ 开放平台申请权限
        int intents = QBotConstants.INTENT_GROUP_AND_C2C_EVENT;
        data.addProperty("intents", intents);
        XLog.d(TAG, "设置Intents: " + intents + " (GROUP_AND_C2C_EVENT=" + QBotConstants.INTENT_GROUP_AND_C2C_EVENT + ")");
        
        com.google.gson.JsonArray shard = new com.google.gson.JsonArray();
        shard.add(currentShard);
        shard.add(shardCount);
        data.add("shard", shard);
        
        JsonObject properties = new JsonObject();
        properties.addProperty("$os", "android");
        properties.addProperty("$browser", "qbot");
        properties.addProperty("$device", "qbot");
        data.add("properties", properties);
        
        identifyMessage.add("d", data);
        
        String message = gson.toJson(identifyMessage);
        XLog.d(TAG, "发送Identify: " + message);
        if (webSocket != null) {
            webSocket.send(message);
        }
    }
    
    /**
     * 发送Resume消息
     */
    private void sendResume() {
        if (sessionId == null || lastSeq == null) {
            XLog.e(TAG, "无法Resume: sessionId或lastSeq为空");
            return;
        }
        
        QBotApiClient apiClient = QBotApiClient.getInstance();
        String token = apiClient.getAuthorizationHeader();
        
        JsonObject resumeMessage = new JsonObject();
        resumeMessage.addProperty("op", QBotConstants.OP_RESUME);
        
        JsonObject data = new JsonObject();
        data.addProperty("token", token);
        data.addProperty("session_id", sessionId);
        data.addProperty("seq", lastSeq);
        
        resumeMessage.add("d", data);
        
        String message = gson.toJson(resumeMessage);
        XLog.d(TAG, "发送Resume: " + message);
        if (webSocket != null) {
            webSocket.send(message);
        }
    }
    
    /**
     * 重连（带防并发保护）
     */
    private synchronized void reconnect() {
        if (stopped) return;
        if (isReconnecting) {
            XLog.d(TAG, "已在重连中，跳过重复请求");
            return;
        }
        isReconnecting = true;
        XLog.w(TAG, "尝试重连WebSocket, 当前状态=" + isConnected + ", gatewayUrl=" + (gatewayUrl != null));
        heartbeatHandler.removeCallbacksAndMessages(null);
        if (gatewayUrl != null) {
            connectWebSocket(gatewayUrl);
        } else {
            start();
        }
        isReconnecting = false;
    }
    
    public boolean isConnected() {
        return isConnected;
    }
    
    public String getConnectionState() {
        return "connected=" + isConnected + 
               ", sessionId=" + sessionId + 
               ", lastSeq=" + lastSeq +
               ", heartbeatInterval=" + heartbeatInterval;
    }
}