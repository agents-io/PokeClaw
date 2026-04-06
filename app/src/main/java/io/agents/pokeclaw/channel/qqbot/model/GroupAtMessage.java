package io.agents.pokeclaw.channel.qqbot.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * QQ 群聊 @ 机器人消息事件 GROUP_AT_MESSAGE_CREATE 的 payload
 */
public class GroupAtMessage {
    @SerializedName("id")
    private String id;
    @SerializedName("content")
    private String content;
    @SerializedName("group_openid")
    private String group_openid;
    @SerializedName("author")
    private Author author;
    @SerializedName("attachments")
    private List<C2CMessage.Attachment> attachments;

    public static class Author {
        @SerializedName("member_openid")
        private String member_openid;

        public String getMemberOpenid() {
            return member_openid;
        }
    }

    public String getId()                         { return id; }
    public String getContent()                    { return content; }
    public String getGroupOpenid()                { return group_openid; }
    public Author getAuthor()                     { return author; }
    public List<C2CMessage.Attachment> getAttachments() { return attachments; }
}
