// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.wechat

/**
 * 微信 iLink Bot 协议常量和数据类。
 * 严格对应官方 @tencent-weixin/openclaw-weixin@1.0.2 的 src/api/types.ts
 */

// ==================== 协议常量 ====================

object MessageType {
    const val NONE = 0
    const val USER = 1
    const val BOT = 2
}

object MessageItemType {
    const val NONE = 0
    const val TEXT = 1
    const val IMAGE = 2
    const val VOICE = 3
    const val FILE = 4
    const val VIDEO = 5
}

object MessageState {
    const val NEW = 0
    const val GENERATING = 1
    const val FINISH = 2
}

object UploadMediaType {
    const val IMAGE = 1
    const val VIDEO = 2
    const val FILE = 3
    const val VOICE = 4
}

object TypingStatus {
    const val TYPING = 1
    const val CANCEL = 2
}

/** session 过期错误码 */
const val SESSION_EXPIRED_ERRCODE = -14

/** 扫码登录 bot_type（login-qr.ts:23） */
const val DEFAULT_ILINK_BOT_TYPE = "3"

const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
const val CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c"
const val CHANNEL_VERSION = "2.0.1"

// ==================== 数据类 ====================

/** CDN 媒体引用 */
data class CdnMedia(
    val encryptQueryParam: String? = null,
    val aesKey: String? = null,
    val encryptType: Int? = null
)

data class WeChatTextItem(val text: String? = null)

data class WeChatImageItem(
    val media: CdnMedia? = null,
    val thumbMedia: CdnMedia? = null,
    val aeskey: String? = null,
    val url: String? = null,
    val midSize: Int? = null,
    val thumbSize: Int? = null,
    val thumbHeight: Int? = null,
    val thumbWidth: Int? = null,
    val hdSize: Int? = null
)

data class WeChatVoiceItem(
    val media: CdnMedia? = null,
    val encodeType: Int? = null,
    val bitsPerSample: Int? = null,
    val sampleRate: Int? = null,
    val playtime: Int? = null,
    val text: String? = null
)

data class WeChatFileItem(
    val media: CdnMedia? = null,
    val fileName: String? = null,
    val md5: String? = null,
    val len: String? = null
)

data class WeChatVideoItem(
    val media: CdnMedia? = null,
    val videoSize: Int? = null,
    val playLength: Int? = null,
    val thumbMedia: CdnMedia? = null
)

data class RefMessage(
    val messageItem: WeChatMessageItem? = null,
    val title: String? = null
)

data class WeChatMessageItem(
    val type: Int = 0,
    val createTimeMs: Long? = null,
    val updateTimeMs: Long? = null,
    val isCompleted: Boolean? = null,
    val msgId: String? = null,
    val textItem: WeChatTextItem? = null,
    val imageItem: WeChatImageItem? = null,
    val voiceItem: WeChatVoiceItem? = null,
    val fileItem: WeChatFileItem? = null,
    val videoItem: WeChatVideoItem? = null,
    val refMsg: RefMessage? = null
)

/** 完整消息（对应 WeixinMessage） */
data class WeChatMessage(
    val seq: Int? = null,
    val messageId: Long? = null,
    val fromUserId: String = "",
    val toUserId: String = "",
    val clientId: String? = null,
    val createTimeMs: Long? = null,
    val updateTimeMs: Long? = null,
    val deleteTimeMs: Long? = null,
    val sessionId: String? = null,
    val groupId: String? = null,
    val messageType: Int? = null,
    val messageState: Int? = null,
    val itemList: List<WeChatMessageItem>? = null,
    val contextToken: String? = null
)

/** getUpdates 响应 */
data class GetUpdatesResp(
    val ret: Int? = null,
    val errcode: Int? = null,
    val errmsg: String? = null,
    val msgs: List<WeChatMessage>? = null,
    val getUpdatesBuf: String? = null,
    val longpollingTimeoutMs: Long? = null
)

/** CDN 上传完成后的信息 */
data class UploadedFileInfo(
    val filekey: String,
    val downloadEncryptedQueryParam: String,
    val aeskeyHex: String,
    val fileSize: Int,
    val fileSizeCiphertext: Int
)

/** 扫码结果 */
data class QrCodeResult(val qrcode: String, val qrcodeImgContent: String)
data class AuthResult(val botToken: String, val baseUrl: String, val botId: String?, val userId: String?)

/** sendMessage 请求体 */
data class SendMessageReq(
    val fromUserId: String = "",
    val toUserId: String,
    val clientId: String,
    val messageType: Int = MessageType.BOT,
    val messageState: Int = MessageState.FINISH,
    val contextToken: String?,
    val itemList: List<Map<String, Any?>>
)
