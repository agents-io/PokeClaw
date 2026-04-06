package io.agents.pokeclaw.channel.qqbot.model;

import com.google.gson.annotations.SerializedName;

public class GatewayResponse {
    @SerializedName("url")
    private String url;
    @SerializedName("shards")
    private int shards = 1;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getShards() {
        return shards;
    }

    public void setShards(int shards) {
        this.shards = shards;
    }
}
