// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.qqbot.model;

import com.google.gson.annotations.SerializedName;

public class AccessTokenResponse {
    @SerializedName("access_token")
    private String access_token;

    // The QQ API returns expires_in as the string "7200"; use String to avoid Gson parse failure
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
