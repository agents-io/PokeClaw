// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.langchain.http;

import io.agents.pokeclaw.utils.XLog;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.MediaType;

/**
 * Adapts OkHttp's builder to LangChain4j's HttpClientBuilder SPI.
 */
public class OkHttpClientBuilderAdapter implements HttpClientBuilder {

    private static final String TAG = "OkHttp";

    private Duration connectTimeout = Duration.ofSeconds(60);
    private Duration readTimeout = Duration.ofSeconds(300);

    /**
     * 是否将请求/响应原始数据输出到文件（沙盒缓存目录）
     */
    private boolean fileLoggingEnabled = false;
    private File cacheDir;

    /** 是否在 logcat 打印请求 body（默认关闭，LLM 请求体很大且重复） */
    private boolean logRequestBody = false;

    public OkHttpClientBuilderAdapter() {
    }

    public OkHttpClientBuilderAdapter setFileLoggingEnabled(boolean enabled, File cacheDir) {
        this.fileLoggingEnabled = enabled;
        this.cacheDir = cacheDir;
        return this;
    }

    public OkHttpClientBuilderAdapter setLogRequestBody(boolean enabled) {
        this.logRequestBody = enabled;
        return this;
    }

    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    @Override
    public HttpClientBuilder readTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    @Override
    public HttpClient build() {
        final boolean logReqBody = this.logRequestBody;

        // 自定义拦截器：始终打印响应，请求 body 由 logRequestBody 控制
        Interceptor llmLoggingInterceptor = chain -> {
            Request request = chain.request();
            long startMs = System.nanoTime();

            // 请求：只打 URL + method，body 按开关控制
            XLog.d(TAG, "--> " + request.method() + " " + request.url());
            if (logReqBody && request.body() != null) {
                okio.Buffer buf = new okio.Buffer();
                request.body().writeTo(buf);
                String body = buf.readUtf8();
                // 截断超长 body，避免 logcat 溢出
                if (body.length() > 4000) {
                    XLog.d(TAG, "Request body: " + body.substring(0, 4000) + "...[truncated, total=" + body.length() + "]");
                } else {
                    XLog.d(TAG, "Request body: " + body);
                }
            }

            Response response = chain.proceed(request);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startMs);

            // 响应：始终打印 body
            ResponseBody responseBody = response.body();
            String respStr = "";
            if (responseBody != null) {
                MediaType contentType = responseBody.contentType();
                respStr = responseBody.string();
                // 重新包装（string() 只能消费一次）
                response = response.newBuilder()
                        .body(ResponseBody.create(contentType, respStr))
                        .build();
            }

            XLog.d(TAG, "<-- " + response.code() + " " + request.url() + " (" + durationMs + "ms)");
            if (!respStr.isEmpty()) {
                if (respStr.length() > 4000) {
                    XLog.d(TAG, "Response body: " + respStr.substring(0, 4000) + "...[truncated, total=" + respStr.length() + "]");
                } else {
                    XLog.d(TAG, "Response body: " + respStr);
                }
            }

            return response;
        };

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .addInterceptor(llmLoggingInterceptor);

        if (fileLoggingEnabled && cacheDir != null) {
            builder.addInterceptor(new FileLoggingInterceptor(cacheDir));
        }

        OkHttpClient okHttpClient = builder.build();
        return new OkHttpClientAdapter(okHttpClient);
    }
}
