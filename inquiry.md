# Inquiry Report: Build / Tool Coupling / Audio Infrastructure Audit

Date: 2026-04-20  
Workspace: `C:\Users\phane\StudioProjects\saarthi`

This report compiles findings for the 3 requested audits, with source-backed snippets.

---

## Prompt 1: Build System & Native Support Audit

### KSP Status
**Result:** KSP is **NOT** configured in the current Gradle setup.
- No `com.google.devtools.ksp` plugin in app or project `plugins {}` blocks.
- No KSP plugin alias in `gradle/libs.versions.toml`.
- No `ksp(...)` dependency usage found.

Evidence (`app/build.gradle.kts`):
```kotlin
plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}
```

Evidence (`build.gradle.kts`):
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
}
```

Evidence (`gradle/libs.versions.toml` plugins section):
```toml
[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
```

Additional search result:
- `appfunctions|androidx.appfunctions|ksp\(|kapt\(` -> **no results**

---

### SDK Versions
**Result:** SDK levels already meet Android 16 baseline requirement.
- `compileSdk` = 36 (release 36 with minor API 1)
- `targetSdk` = 36

Evidence (`app/build.gradle.kts`):
```kotlin
android {
    namespace = "io.agents.pokeclaw"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
```

Evidence (`app/build.gradle.kts`):
```kotlin
defaultConfig {
    applicationId = "io.agents.pokeclaw"
    minSdk = 28
    targetSdk = 36
```

---

### Native Build Status
**Result:** `externalNativeBuild` is not configured.
- No `externalNativeBuild {}` block in Gradle.
- No `ndkVersion` set.
- No `CMakeLists.txt` file found.
- No `app/src/main/cpp/` directory found.

There is **limited native packaging/runtime linkage**, but not a C/C++ build pipeline:
- `ndk { abiFilters += "arm64-v8a" }`
- `packaging { jniLibs { useLegacyPackaging = true } }`
- Manifest declares optional native libraries for LiteRT GPU backend.

Evidence (`app/build.gradle.kts`):
```kotlin
defaultConfig {
    ...
    ndk {
        abiFilters += "arm64-v8a"
    }
}
```

Evidence (`app/build.gradle.kts`):
```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = true
    }
}
```

Evidence (`app/src/main/AndroidManifest.xml`):
```xml
<uses-native-library android:name="libvndksupport.so" android:required="false" />
<uses-native-library android:name="libOpenCL.so" android:required="false" />
```

**Required explicit statement:** **NO NATIVE BUILD CONFIGURED**

---

## Prompt 2: Tool Registry Coupling Audit

### BaseTool Signature
**Result:** Tool abstraction is a project-local base class (`BaseTool`), not a LangChain4j interface.
- Core execution contract: `execute(params: Map<String, Any>): ToolResult`
- Tool params are custom `ToolParameter` objects.

Evidence (`app/src/main/java/io/agents/pokeclaw/tool/BaseTool.kt`):
```kotlin
abstract class BaseTool {
    abstract fun getName(): String
    abstract fun getParameters(): List<ToolParameter>
    abstract fun execute(params: @JvmSuppressWildcards Map<String, Any>): ToolResult

    abstract fun getDescriptionEN(): String
    abstract fun getDescriptionCN(): String
}
```

---

### Registry Storage
**Result:** `ToolRegistry` stores raw tool instances (`BaseTool`) in a Kotlin `LinkedHashMap`.
- It does **not** store `ToolSpecification` objects as canonical state.
- `ToolSpecification` conversion happens later in `LangChain4jToolBridge`.

Evidence (`app/src/main/java/io/agents/pokeclaw/tool/ToolRegistry.kt`):
```kotlin
private val tools = LinkedHashMap<String, BaseTool>()

fun register(tool: BaseTool) {
    tools[tool.getName()] = tool
}

fun getAllTools(): List<BaseTool> = tools.values.toList()
```

Evidence (`app/src/main/java/io/agents/pokeclaw/agent/langchain/LangChain4jToolBridge.java`):
```java
public static List<ToolSpecification> buildToolSpecifications() {
    List<ToolSpecification> specs = new ArrayList<>();
    for (BaseTool tool : ToolRegistry.getInstance().getAllTools()) {
        specs.add(toSpecification(tool));
    }
    return specs;
}
```

---

### Execution Coupling
**Result:** Tool execution is Map-based and project-local, with a bridge layer for LangChain4j requests.
- Tool input type: `Map<String, Object>` (Java tool) / `Map<String, Any>` (Kotlin base)
- Tool output type: `ToolResult`
- LangChain coupling point: bridge parses LangChain JSON args into `Map` then calls registry.

Evidence (`app/src/main/java/io/agents/pokeclaw/tool/impl/mobile/TapTool.java`):
```java
@Override
public ToolResult execute(Map<String, Object> params) {
    ClawAccessibilityService service = requireAccessibilityService();
    int x = requireInt(params, "x");
    int y = requireInt(params, "y");
    boolean success = service.performTap(x, y);
    return success ? ToolResult.success("Tapped at (" + x + ", " + y + ")")
            : ToolResult.error("Failed to tap at (" + x + ", " + y + ")");
}
```

Evidence (`app/src/main/java/io/agents/pokeclaw/agent/langchain/LangChain4jToolBridge.java`):
```java
String argsJson = request.arguments();
Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
Map<String, Object> params = GSON.fromJson(argsJson, mapType);
ToolResult result = ToolRegistry.getInstance().executeTool(toolName, params);
```

Coupling conclusion:
- Tools themselves are **not tightly bound** to LangChain4j classes.
- LangChain4j-specific dependency is concentrated in the bridge/specification layer.

---

## Prompt 3: Audio Infrastructure Audit

### Audio Capture Logic
**Result:** **NO AUDIO RECORDING CLASSES FOUND**
- No `AudioRecord` usage.
- No `MediaRecorder` usage.
- No custom microphone wrapper class found.

Search evidence:
- `\bAudioRecord\b|\bMediaRecorder\b|android\.media\.AudioRecord|android\.media\.MediaRecorder` -> **no results** in `app/src/main/java`.

Implication:
- Mic capture pipeline (record, encode, save audio file) is currently absent in this codebase.

---

### Manifest Permissions
**Result:** `android.permission.RECORD_AUDIO` is **not** present.

Evidence (`app/src/main/AndroidManifest.xml` permission block):
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

Search evidence:
- `RECORD_AUDIO` in `AndroidManifest.xml` -> **no results**.

---

### File Storage Utilities
**Result:** Temporary/local file storage is already used in several subsystems (cache + external files).
- Cache-based file output exists (screenshots, debug reports, logs)
- External files directory used for chat/model artifacts
- Multipart upload support exists for channel integrations (Telegram/Discord), but not connected to microphone capture.

Evidence (`app/src/main/java/io/agents/pokeclaw/tool/impl/TakeScreenshotTool.java`):
```java
File dir = new File(ClawApplication.Companion.getInstance().getCacheDir(), "screenshots");
if (!dir.exists()) dir.mkdirs();
String filename = System.currentTimeMillis() + ".png";
File file = new File(dir, filename);
try (FileOutputStream fos = new FileOutputStream(file)) {
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
}
```

Evidence (`app/src/main/java/io/agents/pokeclaw/support/DebugReportManager.kt`):
```kotlin
val reportDir = File(context.cacheDir, REPORT_DIR).apply { mkdirs() }
val output = File(reportDir, "pokeclaw-debug-$timestamp.zip")
ZipOutputStream(FileOutputStream(output)).use { zip ->
    addText(zip, "summary.txt", buildSummary(context))
    addRecentHttpLogs(zip, context.cacheDir)
}
```

Evidence (`app/src/main/java/io/agents/pokeclaw/channel/telegram/TelegramChannelHandler.kt`):
```kotlin
val body = MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .addFormDataPart("chat_id", chatId.toString())
    .addFormDataPart("photo", "screenshot.png",
        imageBytes.toRequestBody("image/png".toMediaTypeOrNull()))
    .build()
```

---

## Consolidated Readiness Summary

- **Android 16/AppFunctions preconditions:**
  - `compileSdk`/`targetSdk` are already 36 ✅
  - KSP configuration is missing ❌
  - No AppFunctions dependency/compiler wiring detected ❌
- **sqlite-vec preconditions:**
  - No native build pipeline (`externalNativeBuild`/CMake/ndkVersion) ❌
  - Only native packaging/runtime flags exist (not compilation pipeline) ⚠️
- **Tool migration risk (LangChain4j -> native LiteRT tool calling):**
  - Core tools are Map-based and mostly framework-agnostic ✅
  - LangChain coupling mostly in bridge/spec translation layer ⚠️
- **Sarvam STT preconditions:**
  - No mic capture classes ❌
  - No `RECORD_AUDIO` permission ❌
  - File and multipart utilities already exist for binary payload handling ✅

