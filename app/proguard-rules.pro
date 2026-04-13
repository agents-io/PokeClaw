# ============================================================
# General Configuration
# ============================================================
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep Enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object writeResolve();
}


# Agent Related (Reflection/SPI)
-keep class io.agents.pokeclaw.agent.langchain.http.** { *; }
-keep class io.agents.pokeclaw.agent.** { *; }

# Tool Registration (Reflection)
-keep class io.agents.pokeclaw.tool.** { *; }

# Channels (DingTalk/Lark callbacks, keep Signature)
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
# Gson TypeToken Generic
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
# Jackson (LangChain4j dependency)
# ============================================================
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }
-keep interface com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <init>(...);
}

-keepnames class com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
    @com.fasterxml.jackson.databind.annotation.* *;
}
-keepclassmembers,allowobfuscation class * {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
}

# ============================================================
# MMKV
# ============================================================
-keep class com.tencent.mmkv.** { *; }

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
# Lark OAPI SDK
# ============================================================
-dontwarn com.lark.oapi.**
-keep class com.lark.oapi.** { *; }

# ============================================================
# DingTalk Stream SDK
# ============================================================
-dontwarn com.dingtalk.**
-keep class com.dingtalk.** { *; }
-keep interface com.dingtalk.** { *; }
-keep,allowobfuscation,allowshrinking class * implements com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener

# ============================================================
# Third-party Dependencies
# ============================================================
-dontwarn javax.naming.**
-dontwarn org.apache.http.**
-dontwarn org.apache.commons.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn shade.io.netty.**
-dontwarn io.netty.**
-keep class shade.io.netty.** { *; }
-keep class io.netty.** { *; }
-dontwarn shade.io.netty.internal.tcnative.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn org.jetbrains.annotations.**

# ============================================================
# Miscellaneous Libraries
# ============================================================
-keep class com.google.zxing.** { *; }
-keep class com.drakeet.multitype.** { *; }
-keep class com.blankj.utilcode.** { *; }
-keep public class com.blankj.utilcode.util.** { *; }
-keep class com.lzf.easyfloat.** { *; }
-keep class com.moczul.ok2curl.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-keep class jp.wasabeef.glide.** { *; }

# ============================================================
# CRITICAL: Google LiteRT & JNI Fix
# Fixes "mid == null" crash in JNI layer
# ============================================================
# LiteRT-LM (Local LLM / Gemma integration)
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Keep all native methods and JNI bindings
-keepclasseswithmembernames class * {
    native <methods>;
}
