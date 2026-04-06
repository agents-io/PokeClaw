// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.qqbot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import io.agents.pokeclaw.utils.XLog;

import io.agents.pokeclaw.channel.qqbot.model.AccessTokenResponse;
import io.agents.pokeclaw.utils.KVUtils;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class QBotApiClient {
    private static final String TAG = "QBotApiClient";

    private static volatile QBotApiClient instance;
    private OkHttpClient httpClient;
    private Gson gson;
    private Context context;
    private String appId;
    private String clientSecret;
    private volatile String accessToken;
    private volatile long tokenExpireTime;
    private Handler mainHandler;
    private boolean isAuthenticating = false;
    
    private QBotApiClient() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        gson = new Gson();
        try {
            // 回调统一 post 到主线程，避免在任意子线程创建时触发 Looper 相关崩溃
            mainHandler = new Handler(Looper.getMainLooper());
        } catch (RuntimeException e) {
            mainHandler = null;
        }
    }
    
    private void executeCallback(Runnable r) {
        if (mainHandler != null) {
            mainHandler.post(r);
        } else {
            r.run();
        }
    }
    
    public static QBotApiClient getInstance() {
        if (instance == null) {
            synchronized (QBotApiClient.class) {
                if (instance == null) {
                    instance = new QBotApiClient();
                }
            }
        }
        return instance;
    }
    
    public void init(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
        loadCredentials();
    }

    private void loadCredentials() {
        appId = KVUtils.INSTANCE.getQqAppId();
        clientSecret = KVUtils.INSTANCE.getQqAppSecret();
    }
    
    public boolean hasCredentials() {
        return appId != null && !appId.isEmpty() && clientSecret != null && !clientSecret.isEmpty();
    }
    
    public void autoAuth(QBotCallback<AccessTokenResponse> callback) {
        if (!hasCredentials()) {
            if (callback != null) {
                callback.onFailure(new QBotException("未配置鉴权信息"));
            }
            return;
        }
        getAccessToken(callback);
    }
    
    public void getAccessToken(QBotCallback<AccessTokenResponse> callback) {
        loadCredentials();
        if (isTokenValid()) {
            AccessTokenResponse response = new AccessTokenResponse();
            response.setAccess_token(accessToken);
            response.setExpires_in((int) ((tokenExpireTime - System.currentTimeMillis()) / 1000));
            if (callback != null) {
                callback.onSuccess(response);
            }
            return;
        }

        if (appId == null || clientSecret == null || appId.isEmpty() || clientSecret.isEmpty()) {
            if (callback != null) {
                callback.onFailure(new QBotException("未配置 QQ AppId 或 AppSecret"));
            }
            return;
        }
        
        if (isAuthenticating) {
            return;
        }
        isAuthenticating = true;
        
        String json = "{\"appId\":\"" + appId + "\",\"clientSecret\":\"" + clientSecret + "\"}";
        RequestBody requestBody = RequestBody.create(
                MediaType.parse(QBotConstants.CONTENT_TYPE_JSON), json);
        
        Request request = new Request.Builder()
                .url(QBotConstants.GET_ACCESS_TOKEN_URL)
                .post(requestBody)
                .addHeader(QBotConstants.HEADER_CONTENT_TYPE, QBotConstants.CONTENT_TYPE_JSON)
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                isAuthenticating = false;
                XLog.e(TAG, "获取access_token失败: " + e.getMessage());
                executeCallback(() -> {
                    if (callback != null) {
                        callback.onFailure(new QBotException("获取access_token失败: " + e.getMessage(), e));
                    }
                });
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                isAuthenticating = false;
                try {
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        XLog.d(TAG, "access_token 响应: " + responseBody);
                        AccessTokenResponse tokenResponse = gson.fromJson(responseBody, AccessTokenResponse.class);
                        String token = tokenResponse != null ? tokenResponse.getAccess_token() : null;
                        if (token == null || token.isEmpty()) {
                            XLog.e(TAG, "access_token 为空, body=" + responseBody);
                            executeCallback(() -> {
                                if (callback != null) {
                                    callback.onFailure(new QBotException("获取 access_token 失败: 返回的 token 为空, body=" + responseBody));
                                }
                            });
                            return;
                        }
                        accessToken = token;
                        tokenExpireTime = System.currentTimeMillis() + (long) tokenResponse.getExpires_in() * 1000;
                        XLog.d(TAG, "获取access_token成功, expires_in=" + tokenResponse.getExpires_in());
                        executeCallback(() -> {
                            if (callback != null) {
                                callback.onSuccess(tokenResponse);
                            }
                        });
                    } else {
                        String errorBody = response.body() != null ? response.body().string() : "";
                        XLog.e(TAG, "获取access_token失败: HTTP " + response.code());
                        executeCallback(() -> {
                            if (callback != null) {
                                callback.onFailure(new QBotException("获取access_token失败: HTTP " + response.code() + " " + errorBody));
                            }
                        });
                    }
                } catch (Exception e) {
                    XLog.e(TAG, "解析响应失败: " + e.getMessage());
                    executeCallback(() -> {
                        if (callback != null) {
                            callback.onFailure(new QBotException("解析响应失败: " + e.getMessage(), e));
                        }
                    });
                }
            }
        });
    }
    
    private boolean isTokenValid() {
        if (accessToken == null) {
            return false;
        }
        return System.currentTimeMillis() < tokenExpireTime - (QBotConstants.REFRESH_BEFORE_EXPIRE * 1000);
    }
    
    public boolean isAuthenticated() {
        return isTokenValid();
    }

    /** 清除缓存的 token，下次调用 ensureTokenAndExecute 会重新获取 */
    public void clearToken() {
        accessToken = null;
        tokenExpireTime = 0;
    }
    
    public String getAuthorizationHeader() {
        return QBotConstants.AUTH_PREFIX + accessToken;
    }
    
    private void ensureTokenAndExecute(Runnable action, QBotCallback<?> callback) {
        if (isTokenValid()) {
            action.run();
        } else {
            getAccessToken(new QBotCallback<AccessTokenResponse>() {
                @Override
                public void onSuccess(AccessTokenResponse response) {
                    action.run();
                }
                
                @Override
                public void onFailure(QBotException e) {
                    if (callback != null) {
                        callback.onFailure(e);
                    }
                }
            });
        }
    }
    
    public void sendMessage(String channelId, String content, QBotCallback<String> callback) {
        ensureTokenAndExecute(() -> {
            String json = "{\"content\":\"" + content + "\"}";
            RequestBody requestBody = RequestBody.create(
                    MediaType.parse(QBotConstants.CONTENT_TYPE_JSON), json);
            
            Request request = new Request.Builder()
                    .url(QBotConstants.API_BASE_URL + "/v2/channels/" + channelId + "/messages")
                    .post(requestBody)
                    .addHeader(QBotConstants.HEADER_CONTENT_TYPE, QBotConstants.CONTENT_TYPE_JSON)
                    .addHeader(QBotConstants.HEADER_AUTHORIZATION, getAuthorizationHeader())
                    .build();
            
            executeRequest(request, callback);
        }, callback);
    }
    
    public void sendMessageWithImage(String channelId, String content, String imageUrl, QBotCallback<String> callback) {
        ensureTokenAndExecute(() -> {
            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            if (content != null && !content.isEmpty()) {
                json.addProperty("content", content);
            }
            json.addProperty("image", imageUrl);
            
            RequestBody requestBody = RequestBody.create(
                    MediaType.parse(QBotConstants.CONTENT_TYPE_JSON), gson.toJson(json));
            
            Request request = new Request.Builder()
                    .url(QBotConstants.API_BASE_URL + "/v2/channels/" + channelId + "/messages")
                    .post(requestBody)
                    .addHeader(QBotConstants.HEADER_CONTENT_TYPE, QBotConstants.CONTENT_TYPE_JSON)
                    .addHeader(QBotConstants.HEADER_AUTHORIZATION, getAuthorizationHeader())
                    .build();
            
            executeRequest(request, callback);
        }, callback);
    }
    
    public void sendC2CMessage(String openid, String content, QBotCallback<String> callback) {
        sendC2CMessage(openid, content, 0, null, 0, callback);
    }

    public void sendC2CMessage(String openid, String content, int msgType, QBotCallback<String> callback) {
        sendC2CMessage(openid, content, msgType, null, 0, callback);
    }

    /**
     * @param msgId  收到的用户消息 ID，非空时为被动回复（5 分钟内有效，每条最多回复 5 次）
     * @param msgSeq 同一 msgId 下的回复序号，从 1 开始递增，相同 msg_id+msg_seq 会被去重拒绝
     */
    public void sendC2CMessage(String openid, String content, int msgType,
                               String msgId, int msgSeq, QBotCallback<String> callback) {
        ensureTokenAndExecute(() -> {
            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            json.addProperty("content", content);
            json.addProperty("msg_type", msgType);
            if (msgId != null && !msgId.isEmpty()) {
                json.addProperty("msg_id", msgId);
                json.addProperty("msg_seq", msgSeq);
            }
            RequestBody requestBody = RequestBody.create(
                    MediaType.parse(QBotConstants.CONTENT_TYPE_JSON), gson.toJson(json));

            String url = QBotConstants.API_BASE_URL + "/v2/users/" + openid + "/messages";
            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader(QBotConstants.HEADER_CONTENT_TYPE, QBotConstants.CONTENT_TYPE_JSON)
                    .addHeader(QBotConstants.HEADER_AUTHORIZATION, getAuthorizationHeader())
                    .build();

            executeRequest(request, callback);
        }, callback);
    }

    public void sendGroupMessage(String groupOpenid, String content, QBotCallback<String> callback) {
        sendGroupMessage(groupOpenid, content, 0, null, 0, callback);
    }

    public void sendGroupMessage(String groupOpenid, String content, int msgType, QBotCallback<String> callback) {
        sendGroupMessage(groupOpenid, content, msgType, null, 0, callback);
    }

    /**
     * @param msgId  收到的群消息 ID，非空时为被动回复
     * @param msgSeq 同一 msgId 下的回复序号
     */
    public void sendGroupMessage(String groupOpenid, String content, int msgType,
                                 String msgId, int msgSeq, QBotCallback<String> callback) {
        ensureTokenAndExecute(() -> {
            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            json.addProperty("content", content);
            json.addProperty("msg_type", msgType);
            if (msgId != null && !msgId.isEmpty()) {
                json.addProperty("msg_id", msgId);
                json.addProperty("msg_seq", msgSeq);
            }
            RequestBody requestBody = RequestBody.create(
                    MediaType.parse(QBotConstants.CONTENT_TYPE_JSON), gson.toJson(json));

            String url = QBotConstants.API_BASE_URL + "/v2/groups/" + groupOpenid + "/messages";
            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader(QBotConstants.HEADER_CONTENT_TYPE, QBotConstants.CONTENT_TYPE_JSON)
                    .addHeader(QBotConstants.HEADER_AUTHORIZATION, getAuthorizationHeader())
                    .build();

            executeRequest(request, callback);
        }, callback);
    }
    
    private void executeRequest(Request request, QBotCallback<String> callback) {
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                executeCallback(() -> {
                    if (callback != null) {
                        callback.onFailure(new QBotException("请求失败: " + e.getMessage(), e));
                    }
                });
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        String finalBody = responseBody;
                        executeCallback(() -> {
                            if (callback != null) {
                                callback.onSuccess(finalBody);
                            }
                        });
                    } else {
                        executeCallback(() -> {
                            if (callback != null) {
                                callback.onFailure(new QBotException("请求失败: HTTP " + response.code() + " " + responseBody));
                            }
                        });
                    }
                } catch (Exception e) {
                    executeCallback(() -> {
                        if (callback != null) {
                            callback.onFailure(new QBotException("解析响应失败: " + e.getMessage(), e));
                        }
                    });
                }
            }
        });
    }
    
    public void startWebSocket(QBotCallback<String> callback) {
        ensureTokenAndExecute(() -> doStartWebSocket(callback), callback);
    }
    
    private void doStartWebSocket(QBotCallback<String> callback) {
        QBotWebSocketManager.getInstance().setEventCallback(callback);
        QBotWebSocketManager.getInstance().start();
    }
    
    public void stopWebSocket() {
        QBotWebSocketManager.getInstance().stop();
    }
    
    /**
     * 单聊通过公网 URL 发送图片
     */
    public void sendC2CMessageWithImage(String openid, String content, String imageUrl,
                                        String msgId, int msgSeq, QBotCallback<String> callback) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            if (callback != null) callback.onFailure(new QBotException("图片 URL 为空"));
            return;
        }
        uploadFileByUrl(openid, false, FILE_TYPE_IMAGE, imageUrl, false, new QBotCallback<String>() {
            @Override
            public void onSuccess(String result) {
                parseFileInfoAndSend(openid, false, result, msgId, msgSeq, callback);
            }
            @Override
            public void onFailure(QBotException e) {
                if (callback != null) callback.onFailure(e);
            }
        });
    }

    /**
     * 群聊通过公网 URL 发送图片
     */
    public void sendGroupMessageWithImage(String groupOpenid, String content, String imageUrl,
                                          String msgId, int msgSeq, QBotCallback<String> callback) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            if (callback != null) callback.onFailure(new QBotException("图片 URL 为空"));
            return;
        }
        uploadFileByUrl(groupOpenid, true, FILE_TYPE_IMAGE, imageUrl, false, new QBotCallback<String>() {
            @Override
            public void onSuccess(String result) {
                parseFileInfoAndSend(groupOpenid, true, result, msgId, msgSeq, callback);
            }
            @Override
            public void onFailure(QBotException e) {
                if (callback != null) callback.onFailure(e);
            }
        });
    }

    // ==================== 富媒体上传（统一 /files 接口） ====================

    /**
     * file_type 常量：1=图片, 2=视频, 3=语音, 4=文件
     */
    public static final int FILE_TYPE_IMAGE = 1;
    public static final int FILE_TYPE_VIDEO = 2;
    public static final int FILE_TYPE_VOICE = 3;
    public static final int FILE_TYPE_FILE  = 4;

    /**
     * 通用富媒体上传：通过 base64 file_data 上传到 /files
     * @param openid    用户 openid 或群 group_openid
     * @param isGroup   是否群聊
     * @param fileType  FILE_TYPE_IMAGE / FILE_TYPE_VIDEO / FILE_TYPE_VOICE / FILE_TYPE_FILE
     * @param base64Data base64 编码的文件内容（不含 data:xxx;base64, 前缀）
     * @param srvSendMsg true=上传后直接发送（占主动消息频次）；false=仅获取 file_info
     * @param callback  成功返回完整 JSON 响应体
     */
    public void uploadFile(String openid, boolean isGroup, int fileType, String base64Data, boolean srvSendMsg, QBotCallback<String> callback) {
        uploadFile(openid, isGroup, fileType, base64Data, srvSendMsg, null, callback);
    }

    public void uploadFile(String openid, boolean isGroup, int fileType, String base64Data, boolean srvSendMsg, String fileName, QBotCallback<String> callback) {
        ensureTokenAndExecute(() -> {
            String cleanBase64 = base64Data;
            if (cleanBase64.contains(",")) {
                cleanBase64 = cleanBase64.substring(cleanBase64.indexOf(",") + 1);
            }

            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            json.addProperty("file_type", fileType);
            json.addProperty("file_data", cleanBase64);
            json.addProperty("srv_send_msg", srvSendMsg);
            if (fileName != null && !fileName.isEmpty()) {
                json.addProperty("file_name", fileName);
            }

            String url = isGroup
                    ? QBotConstants.API_BASE_URL + "/v2/groups/" + openid + "/files"
                    : QBotConstants.API_BASE_URL + "/v2/users/" + openid + "/files";

            RequestBody requestBody = RequestBody.create(
                    MediaType.parse(QBotConstants.CONTENT_TYPE_JSON), gson.toJson(json));

            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader(QBotConstants.HEADER_CONTENT_TYPE, QBotConstants.CONTENT_TYPE_JSON)
                    .addHeader(QBotConstants.HEADER_AUTHORIZATION, getAuthorizationHeader())
                    .build();

            XLog.d(TAG, "上传富媒体: type=" + fileType + ", url=" + url + ", srvSendMsg=" + srvSendMsg);
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    XLog.e(TAG, "上传富媒体失败: " + e.getMessage());
                    executeCallback(() -> {
                        if (callback != null) callback.onFailure(new QBotException("上传失败: " + e.getMessage()));
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    XLog.d(TAG, "上传富媒体响应: code=" + response.code() + ", body=" + responseBody);
                    if (response.isSuccessful()) {
                        executeCallback(() -> {
                            if (callback != null) callback.onSuccess(responseBody);
                        });
                    } else {
                        executeCallback(() -> {
                            if (callback != null) callback.onFailure(new QBotException("上传失败: HTTP " + response.code() + " " + responseBody));
                        });
                    }
                }
            });
        }, callback);
    }

    /**
     * 通用富媒体上传：通过公网 URL 上传到 /files
     */
    public void uploadFileByUrl(String openid, boolean isGroup, int fileType, String fileUrl, boolean srvSendMsg, QBotCallback<String> callback) {
        ensureTokenAndExecute(() -> {
            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            json.addProperty("file_type", fileType);
            json.addProperty("url", fileUrl);
            json.addProperty("srv_send_msg", srvSendMsg);

            String url = isGroup
                    ? QBotConstants.API_BASE_URL + "/v2/groups/" + openid + "/files"
                    : QBotConstants.API_BASE_URL + "/v2/users/" + openid + "/files";

            RequestBody requestBody = RequestBody.create(
                    MediaType.parse(QBotConstants.CONTENT_TYPE_JSON), gson.toJson(json));

            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader(QBotConstants.HEADER_CONTENT_TYPE, QBotConstants.CONTENT_TYPE_JSON)
                    .addHeader(QBotConstants.HEADER_AUTHORIZATION, getAuthorizationHeader())
                    .build();

            XLog.d(TAG, "上传富媒体(URL): type=" + fileType + ", fileUrl=" + fileUrl);
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    executeCallback(() -> {
                        if (callback != null) callback.onFailure(new QBotException("上传失败: " + e.getMessage()));
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    XLog.d(TAG, "上传富媒体(URL)响应: code=" + response.code() + ", body=" + responseBody);
                    if (response.isSuccessful()) {
                        executeCallback(() -> {
                            if (callback != null) callback.onSuccess(responseBody);
                        });
                    } else {
                        executeCallback(() -> {
                            if (callback != null) callback.onFailure(new QBotException("上传失败: HTTP " + response.code() + " " + responseBody));
                        });
                    }
                }
            });
        }, callback);
    }

    /**
     * 从上传响应中提取 file_info 并发送 msg_type=7 富媒体消息
     */
    private void parseFileInfoAndSend(String openid, boolean isGroup, String uploadResponse,
                                      String msgId, int msgSeq, QBotCallback<String> callback) {
        try {
            com.google.gson.JsonObject resp = gson.fromJson(uploadResponse, com.google.gson.JsonObject.class);
            if (resp.has("file_info")) {
                String fileInfo = resp.get("file_info").getAsString();
                XLog.d(TAG, "获取到 file_info: " + fileInfo);
                if (isGroup) {
                    sendGroupMediaMessage(openid, fileInfo, null, msgId, msgSeq, callback);
                } else {
                    sendC2CMediaMessage(openid, fileInfo, null, msgId, msgSeq, callback);
                }
            } else {
                XLog.d(TAG, "响应无 file_info，可能已通过 srv_send_msg 直接发送");
                if (callback != null) callback.onSuccess(uploadResponse);
            }
        } catch (Exception e) {
            if (callback != null) callback.onFailure(new QBotException("解析 file_info 失败: " + e.getMessage()));
        }
    }

    /**
     * 便捷：base64 图片 → 上传 → 发送富媒体消息（被动回复）
     */
    public void uploadImageAndSend(String openid, boolean isGroup, String base64Image,
                                   String msgId, int msgSeq, QBotCallback<String> callback) {
        uploadFile(openid, isGroup, FILE_TYPE_IMAGE, base64Image, false, new QBotCallback<String>() {
            @Override
            public void onSuccess(String result) {
                parseFileInfoAndSend(openid, isGroup, result, msgId, msgSeq, callback);
            }
            @Override
            public void onFailure(QBotException e) {
                if (callback != null) callback.onFailure(e);
            }
        });
    }

    /**
     * 便捷：本地文件 bytes → base64 → 上传 → 发送富媒体消息（被动回复）
     * @param fileType FILE_TYPE_IMAGE / FILE_TYPE_VIDEO / FILE_TYPE_VOICE / FILE_TYPE_FILE
     */
    public void uploadFileAndSend(String openid, boolean isGroup, int fileType, byte[] fileBytes,
                                  String msgId, int msgSeq, QBotCallback<String> callback) {
        uploadFileAndSend(openid, isGroup, fileType, fileBytes, null, msgId, msgSeq, callback);
    }

    public void uploadFileAndSend(String openid, boolean isGroup, int fileType, byte[] fileBytes,
                                  String fileName, String msgId, int msgSeq, QBotCallback<String> callback) {
        String base64 = android.util.Base64.encodeToString(fileBytes, android.util.Base64.NO_WRAP);
        uploadFile(openid, isGroup, fileType, base64, false, fileName, new QBotCallback<String>() {
            @Override
            public void onSuccess(String result) {
                parseFileInfoAndSend(openid, isGroup, result, msgId, msgSeq, callback);
            }
            @Override
            public void onFailure(QBotException e) {
                if (callback != null) callback.onFailure(e);
            }
        });
    }
    
    public void sendC2CMediaMessage(String openid, String fileId, String content,
                                    String msgId, int msgSeq, QBotCallback<String> callback) {
        ensureTokenAndExecute(() -> {
            com.google.gson.JsonObject media = new com.google.gson.JsonObject();
            media.addProperty("file_info", fileId);

            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            json.addProperty("msg_type", 7);
            json.add("media", media);
            if (content != null && !content.isEmpty()) {
                json.addProperty("content", content);
            }
            if (msgId != null && !msgId.isEmpty()) {
                json.addProperty("msg_id", msgId);
                json.addProperty("msg_seq", msgSeq);
            }

            RequestBody requestBody = RequestBody.create(
                    MediaType.parse(QBotConstants.CONTENT_TYPE_JSON), gson.toJson(json));

            Request request = new Request.Builder()
                    .url(QBotConstants.API_BASE_URL + "/v2/users/" + openid + "/messages")
                    .post(requestBody)
                    .addHeader(QBotConstants.HEADER_CONTENT_TYPE, QBotConstants.CONTENT_TYPE_JSON)
                    .addHeader(QBotConstants.HEADER_AUTHORIZATION, getAuthorizationHeader())
                    .build();

            executeRequest(request, callback);
        }, callback);
    }

    public void sendGroupMediaMessage(String groupOpenid, String fileId, String content,
                                      String msgId, int msgSeq, QBotCallback<String> callback) {
        ensureTokenAndExecute(() -> {
            com.google.gson.JsonObject media = new com.google.gson.JsonObject();
            media.addProperty("file_info", fileId);

            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            json.addProperty("msg_type", 7);
            json.add("media", media);
            if (content != null && !content.isEmpty()) {
                json.addProperty("content", content);
            }
            if (msgId != null && !msgId.isEmpty()) {
                json.addProperty("msg_id", msgId);
                json.addProperty("msg_seq", msgSeq);
            }

            RequestBody requestBody = RequestBody.create(
                    MediaType.parse(QBotConstants.CONTENT_TYPE_JSON), gson.toJson(json));

            Request request = new Request.Builder()
                    .url(QBotConstants.API_BASE_URL + "/v2/groups/" + groupOpenid + "/messages")
                    .post(requestBody)
                    .addHeader(QBotConstants.HEADER_CONTENT_TYPE, QBotConstants.CONTENT_TYPE_JSON)
                    .addHeader(QBotConstants.HEADER_AUTHORIZATION, getAuthorizationHeader())
                    .build();

            executeRequest(request, callback);
        }, callback);
    }


    /**
     * 发送 Markdown 消息（msg_type=2）
     */
    public void sendC2CMarkdown(String openid, String markdownContent, QBotCallback<String> callback) {
        ensureTokenAndExecute(() -> {
            com.google.gson.JsonObject markdown = new com.google.gson.JsonObject();
            markdown.addProperty("content", markdownContent);

            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            json.addProperty("msg_type", 2);
            json.add("markdown", markdown);
            json.addProperty("content", markdownContent);

            RequestBody requestBody = RequestBody.create(
                    MediaType.parse(QBotConstants.CONTENT_TYPE_JSON), gson.toJson(json));

            Request request = new Request.Builder()
                    .url(QBotConstants.API_BASE_URL + "/v2/users/" + openid + "/messages")
                    .post(requestBody)
                    .addHeader(QBotConstants.HEADER_CONTENT_TYPE, QBotConstants.CONTENT_TYPE_JSON)
                    .addHeader(QBotConstants.HEADER_AUTHORIZATION, getAuthorizationHeader())
                    .build();

            executeRequest(request, callback);
        }, callback);
    }

    /**
     * 发送群 Markdown 消息（msg_type=2）
     */
    public void sendGroupMarkdown(String groupOpenid, String markdownContent, QBotCallback<String> callback) {
        ensureTokenAndExecute(() -> {
            com.google.gson.JsonObject markdown = new com.google.gson.JsonObject();
            markdown.addProperty("content", markdownContent);

            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            json.addProperty("msg_type", 2);
            json.add("markdown", markdown);
            json.addProperty("content", markdownContent);

            RequestBody requestBody = RequestBody.create(
                    MediaType.parse(QBotConstants.CONTENT_TYPE_JSON), gson.toJson(json));

            Request request = new Request.Builder()
                    .url(QBotConstants.API_BASE_URL + "/v2/groups/" + groupOpenid + "/messages")
                    .post(requestBody)
                    .addHeader(QBotConstants.HEADER_CONTENT_TYPE, QBotConstants.CONTENT_TYPE_JSON)
                    .addHeader(QBotConstants.HEADER_AUTHORIZATION, getAuthorizationHeader())
                    .build();

            executeRequest(request, callback);
        }, callback);
    }
}
