// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.qqbot.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * QQ 单聊消息事件 C2C_MESSAGE_CREATE 的 payload
 */
public class C2CMessage {
    @SerializedName("id")
    private String id;
    @SerializedName("content")
    private String content;
    @SerializedName("timestamp")
    private String timestamp;
    @SerializedName("author")
    private Author author;
    @SerializedName("attachments")
    private List<Attachment> attachments;

    public static class Author {
        @SerializedName("user_openid")
        private String user_openid;

        public String getUserOpenid() {
            return user_openid;
        }

        public void setUser_openid(String user_openid) {
            this.user_openid = user_openid;
        }
    }

    public static class Attachment {
        @SerializedName("content_type")
        private String contentType;
        @SerializedName("filename")
        private String filename;
        @SerializedName("height")
        private int height;
        @SerializedName("width")
        private int width;
        @SerializedName("size")
        private int size;
        @SerializedName("url")
        private String url;

        public String getContentType() { return contentType; }
        public String getFilename()    { return filename; }
        public int getHeight()         { return height; }
        public int getWidth()          { return width; }
        public int getSize()           { return size; }
        public String getUrl()         { return url; }
    }

    public String getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public Author getAuthor() {
        return author;
    }

    public List<Attachment> getAttachments() {
        return attachments;
    }
}
