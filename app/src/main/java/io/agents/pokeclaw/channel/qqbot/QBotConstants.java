// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.qqbot;

public class QBotConstants {
    // API base URL
    public static final String API_BASE_URL = "https://api.sgroup.qq.com";
    // URL to fetch access_token
    public static final String GET_ACCESS_TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    // Get WebSocket gateway URL
    public static final String GET_GATEWAY_URL = "/gateway";
    public static final String GET_GATEWAY_BOT_URL = "/gateway/bot";
    
    // Request headers
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    
    // Content type
    public static final String CONTENT_TYPE_JSON = "application/json";
    
    // Auth prefix
    public static final String AUTH_PREFIX = "QQBot ";
    
    // Token refresh buffer time (seconds)
    public static final int REFRESH_BEFORE_EXPIRE = 60;
    
    // Message-related API paths
    public static final String API_SEND_MESSAGE = "/v2/groups/{group_id}/messages";
    public static final String API_DELETE_MESSAGE = "/v2/groups/{group_id}/messages/{message_id}";
    public static final String API_GET_BOT_INFO = "/v2/me";
    public static final String API_SEND_C2C_MESSAGE = "/v2/users/{openid}/messages";
    
    // WebSocket OpCodes
    public static final int OP_HELLO = 10;
    public static final int OP_IDENTIFY = 2;
    public static final int OP_HEARTBEAT = 1;
    public static final int OP_HEARTBEAT_ACK = 11;
    public static final int OP_RESUME = 6;
    public static final int OP_DISPATCH = 0;
    
    // Event types
    public static final String EVENT_READY = "READY";
    public static final String EVENT_RESUMED = "RESUMED";
    public static final String EVENT_MESSAGE_CREATE = "MESSAGE_CREATE";
    public static final String EVENT_AT_MESSAGE_CREATE = "AT_MESSAGE_CREATE";
    public static final String EVENT_DIRECT_MESSAGE_CREATE = "DIRECT_MESSAGE_CREATE";
    public static final String EVENT_C2C_MESSAGE_CREATE = "C2C_MESSAGE_CREATE"; // Direct message event
    public static final String EVENT_GROUP_AT_MESSAGE_CREATE = "GROUP_AT_MESSAGE_CREATE"; // Group @bot message event
    
    /**
     * WebSocket error codes
     * 
     * Error codes defined in the official docs for handling WebSocket connection errors
     * 
     * Handling logic:
     * - 4009: can re-send resume
     * - 4914, 4915: cannot connect, contact official support to unban
     * - Other errors: re-send identify
     */
    
    // Invalid opcode
    public static final int WS_ERROR_INVALID_OPCODE = 4001;
    // Invalid payload
    public static final int WS_ERROR_INVALID_PAYLOAD = 4002;
    // seq error
    public static final int WS_ERROR_SEQ_ERROR = 4007;
    // Invalid session id, cannot resume, must identify
    public static final int WS_ERROR_INVALID_SESSION_ID = 4006;
    // Sending payload too fast; reconnect and respect the rate-limit info returned after connecting
    public static final int WS_ERROR_RATE_LIMITED = 4008;
    // Connection expired; reconnect and execute resume
    public static final int WS_ERROR_SESSION_TIMEOUT = 4009;
    // Invalid shard
    public static final int WS_ERROR_INVALID_SHARD = 4010;
    // Too many guilds to handle for this connection; use proper sharding
    public static final int WS_ERROR_SHARD_REQUIRED = 4011;
    // Invalid version
    public static final int WS_ERROR_INVALID_VERSION = 4012;
    // Invalid intent
    public static final int WS_ERROR_INVALID_INTENT = 4013;
    // Intent lacks permission
    public static final int WS_ERROR_INTENT_NO_PERMISSION = 4014;
    // Internal error range
    public static final int WS_ERROR_INTERNAL_MIN = 4900;
    public static final int WS_ERROR_INTERNAL_MAX = 4913;
    // Bot is offline; only sandbox connection allowed
    public static final int WS_ERROR_BOT_OFFLINE = 4914;
    // Bot is banned; connection not allowed
    public static final int WS_ERROR_BOT_BANNED = 4915;
    
    /**
     * Intents event subscriptions
     * 
     * Intents is a bitmask; each bit represents a different event type.
     * Set a bit to 1 to receive that category of events.
     * 
     * Usage:
     * 1. Single event type: use the constant directly, e.g. INTENT_GUILDS
     * 2. Multiple event types: use bitwise OR, e.g. INTENT_GUILDS | INTENT_PUBLIC_GUILD_MESSAGES
     * 
     * Examples:
     * - Subscribe to GUILDS: intents = INTENT_GUILDS (value 1)
     * - Subscribe to GUILDS and PUBLIC_GUILD_MESSAGES: intents = INTENT_GUILDS | INTENT_PUBLIC_GUILD_MESSAGES (value 1073741825)
     * - Multiple events: intents = 0 | (1 << 0) | (1 << 30) is equivalent to INTENT_GUILDS | INTENT_PUBLIC_GUILD_MESSAGES
     * 
     * Notes:
     * - PUBLIC_GUILD_MESSAGES (1 << 30) is for receiving channel messages, including @bot messages
     * - GUILD_MEMBERS (1 << 1) requires special permission to subscribe
     */
    
    // Intents constant definitions
    public static final int INTENT_GUILDS = 1 << 0;                      // 1 - Guild-related events
    public static final int INTENT_GUILD_MEMBERS = 1 << 1;               // 2 - Guild member events (requires special permission)
    public static final int INTENT_GUILD_MESSAGES = 1 << 9;              // 512 - Guild message events
    public static final int INTENT_GUILD_MESSAGE_REACTIONS = 1 << 10;    // 1024 - Guild message emoji reaction events
    public static final int INTENT_DIRECT_MESSAGE = 1 << 12;             // 4096 - Direct message events
    public static final int INTENT_GROUP_AND_C2C_EVENT = 1 << 25;        // 33554432 - Group and C2C events
    public static final int INTENT_INTERACTION = 1 << 26;                // 67108864 - Interaction events
    public static final int INTENT_MESSAGE_AUDIT = 1 << 27;              // 134217728 - Message audit events
    public static final int INTENT_FORUMS_EVENT = 1 << 28;               // 268435456 - Forum events
    public static final int INTENT_AUDIO_ACTION = 1 << 29;               // 536870912 - Audio events
    public static final int INTENT_PUBLIC_GUILD_MESSAGES = 1 << 30;      // 1073741824 - Public guild message events (for receiving @bot messages)
}

