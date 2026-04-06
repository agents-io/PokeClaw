package io.agents.pokeclaw.channel.qqbot.model;

import com.google.gson.annotations.SerializedName;

public class AccessTokenResponse {
    @SerializedName("access_token")
    private String access_token;

    // QQ API 返回的 expires_in 是字符串类型 "7200"，用 String 接收避免 Gson 解析失败
    @SerializedName("expires_in")
    private String expires_in;

    public String getAccess_token() {
        return access_token;
    }

    public void setAccess_token(String access_token) {
        this.access_token = access_token;
    }

    public int getExpires_in() {
        try {
            return Integer.parseInt(expires_in);
        } catch (Exception e) {
            return 7200;
        }
    }

    public void setExpires_in(int expires_in) {
        this.expires_in = String.valueOf(expires_in);
    }
}
