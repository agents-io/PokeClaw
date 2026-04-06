// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.discord

object DiscordConstants {
    // Discord API
    const val API_BASE_URL = "https://discord.com/api/v10"
    const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

    // Headers
    const val HEADER_AUTHORIZATION = "Authorization"
    const val HEADER_CONTENT_TYPE = "Content-Type"
    const val CONTENT_TYPE_JSON = "application/json"

    // Auth prefix
    const val AUTH_PREFIX = "Bot "

    // Gateway OpCodes
    const val OP_DISPATCH = 0
    const val OP_HEARTBEAT = 1
    const val OP_IDENTIFY = 2
    const val OP_RESUME = 6
    const val OP_RECONNECT = 7
    const val OP_INVALID_SESSION = 9
    const val OP_HELLO = 10
    const val OP_HEARTBEAT_ACK = 11

    // Gateway Events
    const val EVENT_READY = "READY"
    const val EVENT_RESUMED = "RESUMED"
    const val EVENT_MESSAGE_CREATE = "MESSAGE_CREATE"

    // Gateway Intents
    const val INTENT_GUILDS = 1 shl 0               // 1
    const val INTENT_GUILD_MESSAGES = 1 shl 9        // 512
    const val INTENT_MESSAGE_CONTENT = 1 shl 15      // 32768 (Privileged)
    const val INTENT_DIRECT_MESSAGES = 1 shl 12      // 4096

    // Gateway Close Codes
    const val CLOSE_UNKNOWN_ERROR = 4000
    const val CLOSE_UNKNOWN_OPCODE = 4001
    const val CLOSE_DECODE_ERROR = 4002
    const val CLOSE_NOT_AUTHENTICATED = 4003
    const val CLOSE_AUTHENTICATION_FAILED = 4004
    const val CLOSE_ALREADY_AUTHENTICATED = 4005
    const val CLOSE_INVALID_SEQ = 4007
    const val CLOSE_RATE_LIMITED = 4008
    const val CLOSE_SESSION_TIMED_OUT = 4009
    const val CLOSE_INVALID_SHARD = 4010
    const val CLOSE_SHARDING_REQUIRED = 4011
    const val CLOSE_INVALID_API_VERSION = 4012
    const val CLOSE_INVALID_INTENTS = 4013
    const val CLOSE_DISALLOWED_INTENTS = 4014
}
