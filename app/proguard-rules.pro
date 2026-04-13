# ============================================================
# 通用配置
# ============================================================
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 保留枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}


# Agent 相关（反射/SPI）
-keep class io.agents.pokeclaw.agent.langchain.http.** { *; }
-keep class io.agents.pokeclaw.agent.** { *; }

# Tool 注册（反射）
-keep class io.agents.pokeclaw.tool.** { *; }

# Channel（钉钉/飞书回调，保留泛型签名）
-keep class io.agents.pokeclaw.channel.** { *; }

# ============================================================
# Gson
# ============================================================
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Gson 使用 TypeToken 泛型
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ============================================================
# OkHttp
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ============================================================
# Retrofit
# ============================================================
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# ============================================================
# LangChain4j
# ============================================================
-dontwarn dev.langchain4j.**
-keep class dev.langchain4j.** { *; }
-keep interface dev.langchain4j.** { *; }

# ============================================================
# Jackson (LangChain4j 内部依赖，序列化需要保留构造器和字段)
# ============================================================
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }
-keep interface com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <init>(...);
}

# ============================================================
# Jackson（LangChain4j OpenAI 内部 JSON 序列化依赖）
# 缺少此规则会导致 R8 混淆 Jackson 内部类，运行时报
# "Class xxx has no default (no arg) constructor"
# ============================================================
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }
-keep interface com.fasterxml.jackson.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }
# 保留带 Jackson 注解的类成员（字段/方法）
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
    @com.fasterxml.jackson.databind.annotation.* *;
}
# 保留 Jackson 需要通过反射创建的类的无参构造函数
-keepclassmembers,allowobfuscation class * {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
}

# ============================================================
# MMKV
# ============================================================
-keep class com.tencent.mmkv.** { *; }

# ============================================================
# LiteRT-LM
# JNI bridge reflects back into the Java/Kotlin wrapper classes.
# If R8 obfuscates Engine / Conversation / Message / Contents, native method
# lookups like nativeCreateConversation can fail with "mid == null".
# Keep the whole wrapper package stable in release builds.
# ============================================================
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }
-keepnames class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================================
# Glide
# ============================================================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}
-dontwarn com.bumptech.glide.**


# ============================================================
# 飞书 Lark OAPI SDK
# ============================================================
-dontwarn com.lark.oapi.**
-keep class com.lark.oapi.** { *; }

# ============================================================
# 钉钉 DingTalk Stream SDK
# ============================================================
-dontwarn com.dingtalk.**
-keep class com.dingtalk.** { *; }
-keep interface com.dingtalk.** { *; }
# 保留 callback 泛型签名（SDK 通过反射检查泛型参数）
-keep,allowobfuscation,allowshrinking class * implements com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener
-keepattributes Signature

# ============================================================
# 飞书/钉钉 SDK 依赖的服务端类（Android 不存在，忽略即可）
# ============================================================
# javax.naming (LDAP/JNDI - Apache HttpClient HostnameVerifier)
-dontwarn javax.naming.**

# Apache HttpClient
-dontwarn org.apache.http.**
-dontwarn org.apache.commons.**

# Log4j / Log4j2
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**

# Netty (shade 包 + 原始包)
-dontwarn shade.io.netty.**
-dontwarn io.netty.**
-keep class shade.io.netty.** { *; }
-keep class io.netty.** { *; }

# Netty tcnative (OpenSSL 绑定)
-dontwarn shade.io.netty.internal.tcnative.**
-dontwarn io.netty.internal.tcnative.**

# Jetty ALPN / NPN
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**

# JetBrains Annotations
-dontwarn org.jetbrains.annotations.**

# ============================================================
# ZXing
# ============================================================
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }

# ============================================================
# MultiType (drakeet)
# ============================================================
-dontwarn com.drakeet.multitype.**
-keep class com.drakeet.multitype.** { *; }

# ============================================================
# BlankJ UtilCode
# ============================================================
-dontwarn com.blankj.**
-keep class com.blankj.utilcode.** { *; }
-keep public class com.blankj.utilcode.util.** { *; }

# ============================================================
# EasyFloat
# ============================================================
-dontwarn com.lzf.easyfloat.**
-keep class com.lzf.easyfloat.** { *; }

# ============================================================
# ok2curl
# ============================================================
-dontwarn com.moczul.ok2curl.**
-keep class com.moczul.ok2curl.** { *; }

# ============================================================
# Kotlin / Coroutines
# ============================================================
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**

# ============================================================
# AndroidX
# ============================================================
-dontwarn androidx.**
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# ============================================================
# glide-transformations (wasabeef)
# ============================================================
-dontwarn jp.wasabeef.glide.**
-keep class jp.wasabeef.glide.** { *; }
