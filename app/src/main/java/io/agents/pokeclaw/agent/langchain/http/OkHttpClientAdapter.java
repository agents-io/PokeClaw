package io.agents.pokeclaw.agent.langchain.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Adapts OkHttp to LangChain4j's HttpClient SPI, enabling langchain4j to work on Android.
 */
public class OkHttpClientAdapter implements HttpClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;

    public OkHttpClientAdapter(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException, RuntimeException {
        Request okRequest = toOkHttpRequest(request);
        try (Response response = okHttpClient.newCall(okRequest).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new HttpException(response.code(), body);
            }
            return toSuccessfulResponse(response);
        } catch (HttpException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        Request okRequest = toOkHttpRequest(request);
        okHttpClient.newCall(okRequest).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                listener.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    String body;
                    try {
                        body = response.body() != null ? response.body().string() : "";
                    } catch (IOException e) {
                        body = "";
                    }
                    listener.onError(new HttpException(response.code(), body));
                    response.close();
                    return;
                }

                SuccessfulHttpResponse successResponse = SuccessfulHttpResponse.builder()
                        .statusCode(response.code())
                        .headers(toHeaderMap(response))
                        .build();
                listener.onOpen(successResponse);

                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    listener.onError(new RuntimeException("Response body is null"));
                    response.close();
                    return;
                }

                try (InputStream inputStream = responseBody.byteStream()) {
                    parser.parse(inputStream, listener);
                } catch (Exception e) {
                    listener.onError(e);
                } finally {
                    listener.onClose();
                    response.close();
                }
            }
        });
    }

    private Request toOkHttpRequest(HttpRequest request) {
        Request.Builder builder = new Request.Builder().url(request.url());

        // Add headers
        if (request.headers() != null) {
            for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
                for (String value : entry.getValue()) {
                    builder.addHeader(entry.getKey(), value);
                }
            }
        }

        // Build request body
        RequestBody body = null;
        if (request.body() != null) {
            body = RequestBody.create(request.body(), JSON);
        }

        switch (request.method()) {
            case GET:
                builder.get();
                break;
            case POST:
                builder.post(body != null ? body : RequestBody.create("", null));
                break;
            case DELETE:
                if (body != null) {
                    builder.delete(body);
                } else {
                    builder.delete();
                }
                break;
        }

        return builder.build();
    }

    private SuccessfulHttpResponse toSuccessfulResponse(Response response) throws IOException {
        String body = response.body() != null ? response.body().string() : "";
        return SuccessfulHttpResponse.builder()
                .statusCode(response.code())
                .headers(toHeaderMap(response))
                .body(body)
                .build();
    }

    private Map<String, List<String>> toHeaderMap(Response response) {
        Map<String, List<String>> headers = new HashMap<>();
        for (String name : response.headers().names()) {
            headers.put(name, new ArrayList<>(response.headers().values(name)));
        }
        return headers;
    }
}
