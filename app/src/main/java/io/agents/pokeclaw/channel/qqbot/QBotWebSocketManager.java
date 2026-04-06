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

    /** Callback for received QQ messages (direct/group), set by ChannelManager */
    private volatile OnQQMessageListener qqMessageListener;

    /** Message ID dedup cache to avoid reprocessing the same message after WebSocket reconnect (max 100 entries) */
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
     * Connection state listener interface
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
     * Add a connection state listener
     * @param listener the listener
     */
    public void addConnectionStateListener(ConnectionStateListener listener) {
        if (!connectionStateListeners.contains(listener)) {
            connectionStateListeners.add(listener);
        }
    }
    
    /**
     * Remove a connection state listener
     * @param listener the listener
     */
    public void removeConnectionStateListener(ConnectionStateListener listener) {
        connectionStateListeners.remove(listener);
    }
    
    /**
     * Notify all listeners of a connection state change
     * @param connected whether connected
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
     * Set the event callback
     * @param callback the event callback
     */
    public void setEventCallback(QBotCallback<String> callback) {
        this.eventCallback = callback;
    }
    
    /**
     * Start WebSocket connection
     */
    public void start() {
        stopped = false;
        getGatewayUrl(new QBotCallback<GatewayResponse>() {
            @Override
            public void onSuccess(GatewayResponse gatewayResponse) {
                gatewayUrl = gatewayResponse.getUrl();
                shardCount = gatewayResponse.getShards();
                XLog.d(TAG, "Gateway URL obtained: " + gatewayUrl + ", recommended shard count: " + shardCount);
                connectWebSocket(gatewayUrl);
            }
            
            @Override
            public void onFailure(QBotException e) {
                XLog.e(TAG, "Failed to get gateway URL: " + e.getMessage());
                if (eventCallback != null) {
                    eventCallback.onFailure(e);
                }
            }
        });
    }
    
    /**
     * Close WebSocket connection
     */
    public void stop() {
        stopped = true;
        isReconnecting = false;
        mainHandler.removeCallbacksAndMessages(null);
        heartbeatHandler.removeCallbacksAndMessages(null);
        if (webSocket != null) {
            webSocket.close(1000, "close connection");
            webSocket = null;
        }
        isConnected = false;
        sessionId = null;
        lastSeq = null;
        notifyConnectionStateChanged(false);
    }
    
    /**
     * Get gateway address
     */
    private void getGatewayUrl(QBotCallback<GatewayResponse> callback) {
        QBotApiClient apiClient = QBotApiClient.getInstance();
        apiClient.getAccessToken(new QBotCallback<AccessTokenResponse>() {
            @Override
            public void onSuccess(AccessTokenResponse response) {
                String authHeader = apiClient.getAuthorizationHeader();
                XLog.d(TAG, "Using Authorization: " + (authHeader != null ? authHeader.substring(0, Math.min(authHeader.length(), 20)) + "..." : "null"));

                String gatewayUrl = QBotConstants.API_BASE_URL + QBotConstants.GET_GATEWAY_URL;
                Request request = new Request.Builder()
                        .url(gatewayUrl)
                        .get()
                        .addHeader(QBotConstants.HEADER_AUTHORIZATION, authHeader)
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        callback.onFailure(new QBotException("Failed to get gateway URL: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        String responseBody = response.body() != null ? response.body().string() : "";
                        if (response.isSuccessful()) {
                            GatewayResponse gatewayResponse = gson.fromJson(responseBody, GatewayResponse.class);
                            if (gatewayResponse.getUrl() == null || gatewayResponse.getUrl().isEmpty()) {
                                XLog.e(TAG, "Gateway returned no url, body=" + responseBody);
                                callback.onFailure(new QBotException("Gateway response data error: no url"));
                            } else {
                                callback.onSuccess(gatewayResponse);
                            }
                        } else {
                            XLog.e(TAG, "Failed to get gateway: code=" + response.code() + ", body=" + responseBody);
                            callback.onFailure(new QBotException("Failed to get gateway URL: " + response.code() + " " + responseBody));
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
     * Connect WebSocket
     */
    private void connectWebSocket(String url) {
        // Close old connection to avoid multiple concurrent WebSockets
        WebSocket oldSocket = webSocket;
        if (oldSocket != null) {
            try {
                oldSocket.close(1000, "replaced by new connection");
            } catch (Exception ignored) {}
            webSocket = null;
        }

        Request request = new Request.Builder()
                .url(url)
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                XLog.d(TAG, "WebSocket connected, response=" + response);
                isConnected = true;
                notifyConnectionStateChanged(true);
            }
            
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                XLog.d(TAG, "Received raw message, length=" + text.length());
                handleWebSocketMessage(text);
            }
            
            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                XLog.d(TAG, "Received binary message, length=" + bytes.size());
            }
            
            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                XLog.w(TAG, "WebSocket closing: code=" + code + ", reason=" + reason);
                // onClosed will fire immediately after; only mark state here, no reconnect logic
                isConnected = false;
                notifyConnectionStateChanged(false);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                // If the current webSocket has been replaced by a new connection, ignore callbacks from the old one
                if (webSocket != QBotWebSocketManager.this.webSocket) return;
                XLog.w(TAG, "WebSocket closed: code=" + code + ", reason=" + reason);
                isConnected = false;
                notifyConnectionStateChanged(false);
                handleWebSocketClose(code, reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                // If the current webSocket has been replaced by a new connection, ignore callbacks from the old one
                if (webSocket != QBotWebSocketManager.this.webSocket) return;
                XLog.e(TAG, "WebSocket connection failed: " + (t != null ? t.getMessage() : "null") + ", response=" + response);
                isConnected = false;
                notifyConnectionStateChanged(false);
                if (!stopped) {
                    reconnect();
                }
            }
        });
    }
    
    /**
     * Handle WebSocket message
     */
    private void handleWebSocketMessage(String message) {
        try {
            WebSocketMessage wsMessage = gson.fromJson(message, WebSocketMessage.class);
            int op = wsMessage.getOp();
            String t = wsMessage.getT();
            XLog.d(TAG, "Message received: op=" + op + ", t=" + t + ", s=" + wsMessage.getS());
            
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
                    XLog.w(TAG, "Reconnect request received (OP=7), preparing to reconnect");
                    handleReconnectRequest();
                    break;
                case 9:
                    handleInvalidSession(wsMessage);
                    break;
                default:
                    XLog.w(TAG, "Unknown OpCode: " + op);
            }
        } catch (Exception e) {
            XLog.e(TAG, "Failed to parse WebSocket message: " + e.getMessage(), e);
        }
    }
    
    /**
     * Handle Hello message
     */
    private void handleHello(WebSocketMessage message) {
        try {
            JsonObject helloData = gson.fromJson(gson.toJson(message.getD()), JsonObject.class);
            heartbeatInterval = helloData.get("heartbeat_interval").getAsLong();
            XLog.d(TAG, "Heartbeat interval: " + heartbeatInterval + "ms");
            
            // Start sending heartbeats
            startHeartbeat();
            
            // Send Identify message
            sendIdentify();
        } catch (Exception e) {
            XLog.e(TAG, "Failed to process Hello message: " + e.getMessage());
        }
    }
    
    /**
     * Handle Dispatch message
     */
    private void handleDispatch(WebSocketMessage message) {
        // Update seq
        if (message.getS() != null) {
            lastSeq = message.getS();
        }
        
        // Handle different event types
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
                    // Other events
                    if (eventCallback != null) {
                        eventCallback.onSuccess(gson.toJson(message));
                    }
            }
        }
    }
    
    /**
     * Handle Ready event
     */
    private void handleReady(WebSocketMessage message) {
        try {
            JsonObject readyData = gson.fromJson(gson.toJson(message.getD()), JsonObject.class);
            sessionId = readyData.get("session_id").getAsString();
            XLog.d(TAG, "SessionId obtained: " + sessionId);
            XLog.d(TAG, "WebSocket connection ready, starting to receive events");
            
            if (eventCallback != null) {
                eventCallback.onSuccess(gson.toJson(message));
            }
        } catch (Exception e) {
            XLog.e(TAG, "Failed to process Ready event: " + e.getMessage());
        }
    }
    
    /**
     * Handle Resumed event
     */
    private void handleResumed(WebSocketMessage message) {
        XLog.d(TAG, "Connection resumed");
        if (eventCallback != null) {
            eventCallback.onSuccess(gson.toJson(message));
        }
    }
    
    /**
     * Handle direct message event
     * Triggered when a user sends a direct message to the bot
     */
    private void handleC2CMessage(WebSocketMessage message) {
        try {
            String messageData = gson.toJson(message.getD());
            C2CMessage c2cMessage = gson.fromJson(messageData, C2CMessage.class);
            
            XLog.d(TAG, "Direct message received:");
            XLog.d(TAG, "  Message ID: " + c2cMessage.getId());
            XLog.d(TAG, "  User OpenID: " + (c2cMessage.getAuthor() != null ? c2cMessage.getAuthor().getUserOpenid() : "null"));
            XLog.d(TAG, "  Content: " + c2cMessage.getContent());
            XLog.d(TAG, "  Timestamp: " + c2cMessage.getTimestamp());

            if (c2cMessage.getAttachments() != null && !c2cMessage.getAttachments().isEmpty()) {
                XLog.d(TAG, "  Attachment count: " + c2cMessage.getAttachments().size());
            }

            // Message ID dedup
            String msgId = c2cMessage.getId();
            if (msgId != null && !recentMessageIds.add(msgId)) {
                XLog.d(TAG, "Direct message duplicate, skipping: " + msgId);
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
            XLog.e(TAG, "Failed to process direct message: " + e.getMessage());
        }
    }

    /**
     * Handle group @bot message event
     * Triggered when a user @mentions the bot in a group
     */
    private void handleGroupAtMessage(WebSocketMessage message) {
        try {
            String messageData = gson.toJson(message.getD());
            GroupAtMessage groupMessage = gson.fromJson(messageData, GroupAtMessage.class);
            
            XLog.d(TAG, "Group @bot message received:");
            XLog.d(TAG, "  Message ID: " + groupMessage.getId());
            XLog.d(TAG, "  Group OpenID: " + groupMessage.getGroupOpenid());
            XLog.d(TAG, "  Sender OpenID: " + (groupMessage.getAuthor() != null ? groupMessage.getAuthor().getMemberOpenid() : "null"));
            XLog.d(TAG, "  Content: " + groupMessage.getContent());

            // Message ID dedup
            String msgId = groupMessage.getId();
            if (msgId != null && !recentMessageIds.add(msgId)) {
                XLog.d(TAG, "Group message duplicate, skipping: " + msgId);
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
            XLog.e(TAG, "Failed to process group @bot message: " + e.getMessage());
        }
    }

    /**
     * Handle invalid session
     */
    private void handleInvalidSession(WebSocketMessage message) {
        try {
            // The d field of OP 9 is a boolean (true=resumable, false=not resumable), not a JsonObject
            Object d = message.getD();
            boolean canResume = false;
            if (d instanceof Boolean) {
                canResume = (Boolean) d;
            } else if (d != null) {
                canResume = Boolean.parseBoolean(d.toString());
            }

            if (canResume) {
                XLog.d(TAG, "Session can be resumed, attempting Resume");
                sendResume();
            } else {
                XLog.d(TAG, "Session invalid, need to re-Identify");
                sessionId = null;
                lastSeq = null;
                sendIdentify();
            }
        } catch (Exception e) {
            XLog.e(TAG, "Failed to handle invalid session: " + e.getMessage());
            sessionId = null;
            lastSeq = null;
        }
    }
    
    /**
     * Handle server-requested reconnect
     */
    private void handleReconnectRequest() {
        XLog.d(TAG, "Server requested reconnect, disconnecting and reconnecting");
        if (webSocket != null) {
            webSocket.close(1000, "server requested reconnect");
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
     * Handle heartbeat ACK
     */
    private void handleHeartbeatAck() {
        heartbeatAckReceived = true;
        XLog.d(TAG, "Heartbeat ACK received");
    }
    
    /**
     * Handle WebSocket close error code
     * @param code close code
     * @param reason close reason
     */
    private void handleWebSocketClose(int code, String reason) {
        XLog.d(TAG, "Handling WebSocket close: code=" + code + ", reason=" + reason);
        
        switch (code) {
            case 4004:
                // 4004: Authentication fail — token may have expired, refresh and reconnect
                Log.w(TAG, "Auth failed (4004), clearing old token, reconnecting after refresh");
                // Clear old token to force refresh
                QBotApiClient.getInstance().clearToken();
                heartbeatHandler.postDelayed(() -> {
                    Log.d(TAG, "4004 delayed reconnect, re-fetching token");
                    start(); // start() re-fetches token → gateway → connect
                }, 3000);
                break;

            case QBotConstants.WS_ERROR_SESSION_TIMEOUT:
                // 4009: Connection expired, can attempt Resume
                XLog.d(TAG, "Connection expired, attempting Resume reconnect");
                reconnect();
                break;

            case QBotConstants.WS_ERROR_BOT_OFFLINE:
                // 4914: Bot is offline, only sandbox connection allowed
                XLog.e(TAG, "Bot is offline, only sandbox connection allowed; please disconnect and verify the connection environment");
                if (eventCallback != null) {
                    eventCallback.onFailure(new QBotException("Bot is offline, only sandbox connection allowed"));
                }
                break;
                
            case QBotConstants.WS_ERROR_BOT_BANNED:
                // 4915: Bot is banned, connection not allowed
                XLog.e(TAG, "Bot is banned, connection not allowed; please request unban before connecting");
                if (eventCallback != null) {
                    eventCallback.onFailure(new QBotException("Bot is banned, connection not allowed"));
                }
                break;
                
            case QBotConstants.WS_ERROR_RATE_LIMITED:
                // 4008: Sending payload too fast, can reconnect
                XLog.w(TAG, "Sending payload too fast, reconnecting later");
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        reconnect();
                    }
                }, 5000); // Delay 5 seconds before reconnect
                break;
                
            case QBotConstants.WS_ERROR_INVALID_OPCODE:
            case QBotConstants.WS_ERROR_INVALID_PAYLOAD:
            case QBotConstants.WS_ERROR_INVALID_SHARD:
            case QBotConstants.WS_ERROR_INVALID_VERSION:
            case QBotConstants.WS_ERROR_INVALID_INTENT:
            case QBotConstants.WS_ERROR_INTENT_NO_PERMISSION:
                // These errors require re-Identify
                XLog.e(TAG, "Connection error (code=" + code + "), clearing session info and re-Identifying");
                sessionId = null;
                lastSeq = null;
                reconnect();
                break;
                
            case QBotConstants.WS_ERROR_SEQ_ERROR:
            case QBotConstants.WS_ERROR_INVALID_SESSION_ID:
                // seq error or invalid session id, need to re-Identify
                XLog.w(TAG, "Session invalid, clearing session info and re-Identifying");
                sessionId = null;
                lastSeq = null;
                reconnect();
                break;
                
            case QBotConstants.WS_ERROR_SHARD_REQUIRED:
                // Sharding required
                XLog.w(TAG, "Sharding required, recommend using multiple connections");
                reconnect();
                break;
                
            default:
                // Internal error (4900-4913) or other unknown error
                if (code >= QBotConstants.WS_ERROR_INTERNAL_MIN && code <= QBotConstants.WS_ERROR_INTERNAL_MAX) {
                    XLog.w(TAG, "Server internal error (" + code + "), reconnecting later");
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            reconnect();
                        }
                    }, 3000); // Delay 3 seconds before reconnect
                } else if (code == 1000) {
                    // Normal close
                    XLog.d(TAG, "WebSocket closed normally");
                } else {
                    // Other unknown error, attempt reconnect
                    XLog.w(TAG, "Unknown error code: " + code + ", attempting reconnect");
                    reconnect();
                }
                break;
        }
    }
    
    /**
     * Start sending heartbeat
     */
    private void startHeartbeat() {
        XLog.d(TAG, "Starting heartbeat, interval=" + heartbeatInterval + "ms");
        heartbeatAckReceived = true;
        heartbeatHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isConnected) {
                    XLog.w(TAG, "Heartbeat check: connection is down");
                    return;
                }
                if (!heartbeatAckReceived) {
                    XLog.w(TAG, "Heartbeat timeout: no ACK received for last heartbeat, disconnecting and reconnecting");
                    if (webSocket != null) {
                        webSocket.close(1000, "heartbeat timeout");
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
     * Send heartbeat
     */
    private void sendHeartbeat() {
        JsonObject heartbeatMessage = new JsonObject();
        heartbeatMessage.addProperty("op", QBotConstants.OP_HEARTBEAT);
        heartbeatMessage.add("d", lastSeq != null ? gson.toJsonTree(lastSeq) : null);
        
        String message = gson.toJson(heartbeatMessage);
        XLog.d(TAG, "Sending heartbeat: " + message);
        if (webSocket != null) {
            webSocket.send(message);
        }
    }
    
    /**
     * Send Identify message
     */
    private void sendIdentify() {
        QBotApiClient apiClient = QBotApiClient.getInstance();
        String token = apiClient.getAuthorizationHeader();
        
        JsonObject identifyMessage = new JsonObject();
        identifyMessage.addProperty("op", QBotConstants.OP_IDENTIFY);
        
        JsonObject data = new JsonObject();
        data.addProperty("token", token);
        // Subscribe only to necessary events to avoid 4004 rejection from missing permissions:
        // - GROUP_AND_C2C_EVENT (1<<25): group @bot messages + direct messages (core feature)
        // For channel messages, add INTENT_PUBLIC_GUILD_MESSAGES (1<<30), but requires applying for permission on the QQ Open Platform
        int intents = QBotConstants.INTENT_GROUP_AND_C2C_EVENT;
        data.addProperty("intents", intents);
        XLog.d(TAG, "Setting Intents: " + intents + " (GROUP_AND_C2C_EVENT=" + QBotConstants.INTENT_GROUP_AND_C2C_EVENT + ")");
        
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
        XLog.d(TAG, "Sending Identify: " + message);
        if (webSocket != null) {
            webSocket.send(message);
        }
    }
    
    /**
     * Send Resume message
     */
    private void sendResume() {
        if (sessionId == null || lastSeq == null) {
            XLog.e(TAG, "Cannot Resume: sessionId or lastSeq is null");
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
        XLog.d(TAG, "Sending Resume: " + message);
        if (webSocket != null) {
            webSocket.send(message);
        }
    }
    
    /**
     * Reconnect (with concurrency protection)
     */
    private synchronized void reconnect() {
        if (stopped) return;
        if (isReconnecting) {
            XLog.d(TAG, "Already reconnecting, skipping duplicate request");
            return;
        }
        isReconnecting = true;
        XLog.w(TAG, "Attempting WebSocket reconnect, current state=" + isConnected + ", gatewayUrl=" + (gatewayUrl != null));
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