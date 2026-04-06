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
            // Always post callbacks to the main thread to avoid Looper-related crashes when created on arbitrary worker threads
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
                callback.onFailure(new QBotException("Auth credentials not configured"));
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
                callback.onFailure(new QBotException("QQ AppId or AppSecret not configured"));
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
                XLog.e(TAG, "Failed to get access_token: " + e.getMessage());
                executeCallback(() -> {
                    if (callback != null) {
                        callback.onFailure(new QBotException("Failed to get access_token: " + e.getMessage(), e));
                    }
                });
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                isAuthenticating = false;
                try {
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        XLog.d(TAG, "access_token response: " + responseBody);
                        AccessTokenResponse tokenResponse = gson.fromJson(responseBody, AccessTokenResponse.class);
                        String token = tokenResponse != null ? tokenResponse.getAccess_token() : null;
                        if (token == null || token.isEmpty()) {
                            XLog.e(TAG, "access_token is empty, body=" + responseBody);
                            executeCallback(() -> {
                                if (callback != null) {
                                    callback.onFailure(new QBotException("Failed to get access_token: returned token is empty, body=" + responseBody));
                                }
                            });
                            return;
                        }
                        accessToken = token;
                        tokenExpireTime = System.currentTimeMillis() + (long) tokenResponse.getExpires_in() * 1000;
                        XLog.d(TAG, "access_token obtained, expires_in=" + tokenResponse.getExpires_in());
                        executeCallback(() -> {
                            if (callback != null) {
                                callback.onSuccess(tokenResponse);
                            }
                        });
                    } else {
                        String errorBody = response.body() != null ? response.body().string() : "";
                        XLog.e(TAG, "Failed to get access_token: HTTP " + response.code());
                        executeCallback(() -> {
                            if (callback != null) {
                                callback.onFailure(new QBotException("Failed to get access_token: HTTP " + response.code() + " " + errorBody));
                            }
                        });
                    }
                } catch (Exception e) {
                    XLog.e(TAG, "Failed to parse response: " + e.getMessage());
                    executeCallback(() -> {
                        if (callback != null) {
                            callback.onFailure(new QBotException("Failed to parse response: " + e.getMessage(), e));
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

    /** Clear cached token; the next call to ensureTokenAndExecute will re-fetch it */
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
     * @param msgId  received user message ID; non-null means passive reply (valid within 5 minutes, max 5 replies per message)
     * @param msgSeq reply sequence number under the same msgId, starting from 1; duplicate msg_id+msg_seq pairs are rejected
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
     * @param msgId  received group message ID; non-null means passive reply
     * @param msgSeq reply sequence number under the same msgId
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
                        callback.onFailure(new QBotException("Request failed: " + e.getMessage(), e));
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
                                callback.onFailure(new QBotException("Request failed: HTTP " + response.code() + " " + responseBody));
                            }
                        });
                    }
                } catch (Exception e) {
                    executeCallback(() -> {
                        if (callback != null) {
                            callback.onFailure(new QBotException("Failed to parse response: " + e.getMessage(), e));
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
     * Send image via public URL in a direct message
     */
    public void sendC2CMessageWithImage(String openid, String content, String imageUrl,
                                        String msgId, int msgSeq, QBotCallback<String> callback) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            if (callback != null) callback.onFailure(new QBotException("Image URL is empty"));
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
     * Send image via public URL in a group message
     */
    public void sendGroupMessageWithImage(String groupOpenid, String content, String imageUrl,
                                          String msgId, int msgSeq, QBotCallback<String> callback) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            if (callback != null) callback.onFailure(new QBotException("Image URL is empty"));
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

    // ==================== Rich Media Upload (unified /files endpoint) ====================

    /**
     * file_type constants: 1=image, 2=video, 3=voice, 4=file
     */
    public static final int FILE_TYPE_IMAGE = 1;
    public static final int FILE_TYPE_VIDEO = 2;
    public static final int FILE_TYPE_VOICE = 3;
    public static final int FILE_TYPE_FILE  = 4;

    /**
     * General rich-media upload: upload to /files via base64 file_data
     * @param openid    user openid or group group_openid
     * @param isGroup   whether this is a group message
     * @param fileType  FILE_TYPE_IMAGE / FILE_TYPE_VIDEO / FILE_TYPE_VOICE / FILE_TYPE_FILE
     * @param base64Data base64-encoded file content (without the data:xxx;base64, prefix)
     * @param srvSendMsg true=send immediately after upload (counts against proactive message quota); false=only fetch file_info
     * @param callback  returns complete JSON response body on success
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

            XLog.d(TAG, "Uploading rich media: type=" + fileType + ", url=" + url + ", srvSendMsg=" + srvSendMsg);
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    XLog.e(TAG, "Rich media upload failed: " + e.getMessage());
                    executeCallback(() -> {
                        if (callback != null) callback.onFailure(new QBotException("Upload failed: " + e.getMessage()));
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    XLog.d(TAG, "Rich media upload response: code=" + response.code() + ", body=" + responseBody);
                    if (response.isSuccessful()) {
                        executeCallback(() -> {
                            if (callback != null) callback.onSuccess(responseBody);
                        });
                    } else {
                        executeCallback(() -> {
                            if (callback != null) callback.onFailure(new QBotException("Upload failed: HTTP " + response.code() + " " + responseBody));
                        });
                    }
                }
            });
        }, callback);
    }

    /**
     * General rich-media upload: upload to /files via public URL
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

            XLog.d(TAG, "Rich media upload (URL): type=" + fileType + ", fileUrl=" + fileUrl);
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    executeCallback(() -> {
                        if (callback != null) callback.onFailure(new QBotException("Upload failed: " + e.getMessage()));
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    XLog.d(TAG, "Rich media upload (URL) response: code=" + response.code() + ", body=" + responseBody);
                    if (response.isSuccessful()) {
                        executeCallback(() -> {
                            if (callback != null) callback.onSuccess(responseBody);
                        });
                    } else {
                        executeCallback(() -> {
                            if (callback != null) callback.onFailure(new QBotException("Upload failed: HTTP " + response.code() + " " + responseBody));
                        });
                    }
                }
            });
        }, callback);
    }

    /**
     * Extract file_info from the upload response and send a msg_type=7 rich-media message
     */
    private void parseFileInfoAndSend(String openid, boolean isGroup, String uploadResponse,
                                      String msgId, int msgSeq, QBotCallback<String> callback) {
        try {
            com.google.gson.JsonObject resp = gson.fromJson(uploadResponse, com.google.gson.JsonObject.class);
            if (resp.has("file_info")) {
                String fileInfo = resp.get("file_info").getAsString();
                XLog.d(TAG, "file_info obtained: " + fileInfo);
                if (isGroup) {
                    sendGroupMediaMessage(openid, fileInfo, null, msgId, msgSeq, callback);
                } else {
                    sendC2CMediaMessage(openid, fileInfo, null, msgId, msgSeq, callback);
                }
            } else {
                XLog.d(TAG, "Response has no file_info; may have been sent directly via srv_send_msg");
                if (callback != null) callback.onSuccess(uploadResponse);
            }
        } catch (Exception e) {
            if (callback != null) callback.onFailure(new QBotException("Failed to parse file_info: " + e.getMessage()));
        }
    }

    /**
     * Convenience: base64 image → upload → send rich-media message (passive reply)
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
     * Convenience: local file bytes → base64 → upload → send rich-media message (passive reply)
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
     * Send a Markdown message (msg_type=2)
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
     * Send a group Markdown message (msg_type=2)
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
