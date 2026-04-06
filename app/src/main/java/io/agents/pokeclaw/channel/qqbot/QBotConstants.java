package io.agents.pokeclaw.channel.qqbot;

public class QBotConstants {
    // API 基础 URL
    public static final String API_BASE_URL = "https://api.sgroup.qq.com";
    // 获取 access_token 的 URL
    public static final String GET_ACCESS_TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    // 获取 WebSocket 网关地址
    public static final String GET_GATEWAY_URL = "/gateway";
    public static final String GET_GATEWAY_BOT_URL = "/gateway/bot";
    
    // 请求头
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    
    // 内容类型
    public static final String CONTENT_TYPE_JSON = "application/json";
    
    // 认证前缀
    public static final String AUTH_PREFIX = "QQBot ";
    
    // Token 刷新时间（秒）
    public static final int REFRESH_BEFORE_EXPIRE = 60;
    
    // 消息相关接口
    public static final String API_SEND_MESSAGE = "/v2/groups/{group_id}/messages";
    public static final String API_DELETE_MESSAGE = "/v2/groups/{group_id}/messages/{message_id}";
    public static final String API_GET_BOT_INFO = "/v2/me";
    public static final String API_SEND_C2C_MESSAGE = "/v2/users/{openid}/messages";
    
    // WebSocket OpCode
    public static final int OP_HELLO = 10;
    public static final int OP_IDENTIFY = 2;
    public static final int OP_HEARTBEAT = 1;
    public static final int OP_HEARTBEAT_ACK = 11;
    public static final int OP_RESUME = 6;
    public static final int OP_DISPATCH = 0;
    
    // 事件类型
    public static final String EVENT_READY = "READY";
    public static final String EVENT_RESUMED = "RESUMED";
    public static final String EVENT_MESSAGE_CREATE = "MESSAGE_CREATE";
    public static final String EVENT_AT_MESSAGE_CREATE = "AT_MESSAGE_CREATE";
    public static final String EVENT_DIRECT_MESSAGE_CREATE = "DIRECT_MESSAGE_CREATE";
    public static final String EVENT_C2C_MESSAGE_CREATE = "C2C_MESSAGE_CREATE"; // 单聊消息事件
    public static final String EVENT_GROUP_AT_MESSAGE_CREATE = "GROUP_AT_MESSAGE_CREATE"; // 群聊@机器人消息事件
    
    /**
     * WebSocket 错误码
     * 
     * 根据官方文档定义的错误码，用于处理WebSocket连接异常
     * 
     * 处理逻辑：
     * - 4009: 可以重新发起 resume
     * - 4914, 4915: 不可以连接，需要联系官方解封
     * - 其他错误: 请重新发起 identify
     */
    
    // 无效的 opcode
    public static final int WS_ERROR_INVALID_OPCODE = 4001;
    // 无效的 payload
    public static final int WS_ERROR_INVALID_PAYLOAD = 4002;
    // seq 错误
    public static final int WS_ERROR_SEQ_ERROR = 4007;
    // 无效的 session id，无法继续 resume，请 identify
    public static final int WS_ERROR_INVALID_SESSION_ID = 4006;
    // 发送 payload 过快，请重新连接，并遵守连接后返回的频控信息
    public static final int WS_ERROR_RATE_LIMITED = 4008;
    // 连接过期，请重连并执行 resume 进行重新连接
    public static final int WS_ERROR_SESSION_TIMEOUT = 4009;
    // 无效的 shard
    public static final int WS_ERROR_INVALID_SHARD = 4010;
    // 连接需要处理的 guild 过多，请进行合理的分片
    public static final int WS_ERROR_SHARD_REQUIRED = 4011;
    // 无效的 version
    public static final int WS_ERROR_INVALID_VERSION = 4012;
    // 无效的 intent
    public static final int WS_ERROR_INVALID_INTENT = 4013;
    // intent 无权限
    public static final int WS_ERROR_INTENT_NO_PERMISSION = 4014;
    // 内部错误范围
    public static final int WS_ERROR_INTERNAL_MIN = 4900;
    public static final int WS_ERROR_INTERNAL_MAX = 4913;
    // 机器人已下架,只允许连接沙箱环境
    public static final int WS_ERROR_BOT_OFFLINE = 4914;
    // 机器人已封禁,不允许连接
    public static final int WS_ERROR_BOT_BANNED = 4915;
    
    /**
     * Intents 事件订阅
     * 
     * Intents是一个标记位，每一位代表不同的事件类型。
     * 如果需要接收某类事件，就将该位置为1。
     * 
     * 使用方法：
     * 1. 单个事件类型：直接使用常量，如 INTENT_GUILDS
     * 2. 多个事件类型：使用位或操作，如 INTENT_GUILDS | INTENT_PUBLIC_GUILD_MESSAGES
     * 
     * 示例：
     * - 订阅GUILDS事件：intents = INTENT_GUILDS (值为1)
     * - 订阅GUILDS和PUBLIC_GUILD_MESSAGES：intents = INTENT_GUILDS | INTENT_PUBLIC_GUILD_MESSAGES (值为1073741825)
     * - 订阅多个事件：intents = 0 | (1 << 0) | (1 << 30) 等同于 INTENT_GUILDS | INTENT_PUBLIC_GUILD_MESSAGES
     * 
     * 注意：
     * - PUBLIC_GUILD_MESSAGES (1 << 30) 用于接收频道消息，包括@机器人消息
     * - GUILD_MEMBERS (1 << 1) 需要特殊权限才能订阅
     */
    
    // Intents 常量定义
    public static final int INTENT_GUILDS = 1 << 0;                      // 1 - 频道相关事件
    public static final int INTENT_GUILD_MEMBERS = 1 << 1;               // 2 - 频道成员相关事件（需要特殊权限）
    public static final int INTENT_GUILD_MESSAGES = 1 << 9;              // 512 - 频道消息相关事件
    public static final int INTENT_GUILD_MESSAGE_REACTIONS = 1 << 10;    // 1024 - 频道消息表情表态事件
    public static final int INTENT_DIRECT_MESSAGE = 1 << 12;             // 4096 - 私信相关事件
    public static final int INTENT_GROUP_AND_C2C_EVENT = 1 << 25;        // 33554432 - 群和C2C事件
    public static final int INTENT_INTERACTION = 1 << 26;                // 67108864 - 交互事件
    public static final int INTENT_MESSAGE_AUDIT = 1 << 27;              // 134217728 - 消息审核事件
    public static final int INTENT_FORUMS_EVENT = 1 << 28;               // 268435456 - 论坛事件
    public static final int INTENT_AUDIO_ACTION = 1 << 29;               // 536870912 - 音频事件
    public static final int INTENT_PUBLIC_GUILD_MESSAGES = 1 << 30;      // 1073741824 - 公域频道消息事件（用于接收@机器人消息）
}

