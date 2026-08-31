# Codebase Discovery Report (As-Is)

Date: 2026-04-20  
Workspace: `C:\Users\phane\StudioProjects\saarthi`

This report documents the current architecture and runtime behavior of the forked PokeClaw codebase. It is strictly an as-is discovery map (no redesign guidance).

---

## 1. Project Structure & Dependency Baseline

### 1.1 Dependencies

#### Build files inspected
- `build.gradle.kts`
- `settings.gradle.kts`
- `app/build.gradle.kts`
- `gradle/libs.versions.toml`

#### Core runtime stack observed
- **LLM runtimes**
  - `com.google.ai.edge.litertlm:litertlm-android`
  - `com.qualcomm.qti:qnn-runtime`
  - `com.qualcomm.qti:qnn-litert-delegate`
  - `dev.langchain4j:*` for cloud-model integration
- **Networking**
  - OkHttp, Retrofit, Gson
- **UI frameworks**
  - Jetpack Compose enabled in app module
  - XML layouts still present (`app/src/main/res/layout/*.xml`)
  - Result: hybrid UI stack (Compose + View/XML)
- **Persistence**
  - MMKV key-value storage (`KVUtils`)
  - SQLite via `SQLiteOpenHelper` (`ChatDatabase`)
  - Room: **NOT PRESENT IN CURRENT BASE**

Evidence (`app/build.gradle.kts`):
```kotlin
buildFeatures {
    buildConfig = true
    compose = true
}
implementation(libs.litertlm.android)
implementation(libs.qualcomm.qnn.runtime)
implementation(libs.qualcomm.qnn.delegate)
```

Evidence (`gradle/libs.versions.toml`):
```toml
litertlm = "0.10.0"
qnn = "2.45.0"
langchain4j = "1.12.2"
mmkv = "2.3.0"
litertlm-android = { module = "com.google.ai.edge.litertlm:litertlm-android", version.ref = "litertlm" }
qualcomm-qnn-runtime = { module = "com.qualcomm.qti:qnn-runtime", version.ref = "qnn" }
qualcomm-qnn-delegate = { module = "com.qualcomm.qti:qnn-litert-delegate", version.ref = "qnn" }
```

Evidence (`app/src/main/java/io/agents/pokeclaw/ui/chat/ChatDatabase.kt`):
```kotlin
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ChatDatabase(context: Context) : SQLiteOpenHelper(context, "pokeclaw.db", null, 1) {
```

---

### 1.2 Entry Points (Manifest)

#### Main activity
- `io.agents.pokeclaw.ui.splash.SplashActivity` is launcher entry point.

#### Application class
- `io.agents.pokeclaw.ClawApplication`

#### Services declared
- `io.agents.pokeclaw.service.ClawAccessibilityService`
- `io.agents.pokeclaw.service.ClawNotificationListener`
- `io.agents.pokeclaw.service.ForegroundService`
- `io.agents.pokeclaw.service.KeepAliveJobService`

#### Broadcast receivers declared
- `io.agents.pokeclaw.service.BootReceiver`
- `io.agents.pokeclaw.debug.DebugTaskReceiver`
- `io.agents.pokeclaw.debug.TaskTriggerReceiver`

#### Permissions requested (representative)
- Internet/network: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`
- Foreground/background: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`
- UX/overlay/notification: `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`
- Storage/package visibility: `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`
- Power/alarm: `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`

Evidence (`app/src/main/AndroidManifest.xml`):
```xml
<activity
    android:name=".ui.splash.SplashActivity"
    android:exported="true"
    android:theme="@style/Theme.PokeClaw.Splash">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

Evidence (`app/src/main/AndroidManifest.xml`):
```xml
<service
    android:name=".service.ClawAccessibilityService"
    android:exported="false"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
</service>
```

---

### 1.3 Architecture Pattern & DI

#### Pattern assessment (as implemented)
- Primary architecture is **orchestrator/service-driven** with ViewModel integration.
- `AppViewModel` coordinates high-level flows but core task execution sits in `TaskOrchestrator` + `DefaultAgentService` + tool stack.
- Routing layer exists (`PipelineRouter`) before full agent loop.

#### DI assessment
- Hilt/Dagger/Koin: **NOT PRESENT IN CURRENT BASE**
- Wiring is manual via factory objects and singletons (`AgentServiceFactory`, `ToolRegistry`, `EngineHolder`, `KVUtils`, global `appViewModel`).

Evidence (`app/src/main/java/io/agents/pokeclaw/agent/AgentServiceFactory.kt`):
```kotlin
object AgentServiceFactory {
    @JvmStatic
    fun create(): AgentService = DefaultAgentService()
}
```

Evidence (`app/src/main/java/io/agents/pokeclaw/ClawApplication.kt`):
```kotlin
val appViewModel: AppViewModel by lazy { ClawApplication.appViewModelInstance }
class ClawApplication : BaseApp() {
    companion object {
        lateinit var appViewModelInstance: AppViewModel
    }
}
```

---

## 2. The Current "Claw" (Accessibility & UI Interaction)

### 2.1 Service Definition

- Accessibility service class: `app/src/main/java/io/agents/pokeclaw/service/ClawAccessibilityService.java`
- Class name: `ClawAccessibilityService`
- Inherits from `AccessibilityService`

Evidence:
```java
public class ClawAccessibilityService extends AccessibilityService {
    private static volatile ClawAccessibilityService instance;

    public static ClawAccessibilityService getInstance() {
        return instance;
    }
}
```

---

### 2.2 Event Handling (`onAccessibilityEvent`)

Current behavior in `onAccessibilityEvent`:
- Updates accessibility heartbeat in `KVUtils`
- Logs notification state changed events
- Delegates fallback notification handling to `AutoReplyManager`

Observation:
- It is **not** a generic event-driven UI planner in this callback; autonomous action logic is executed via tools/agent loop, not directly inside event callback.

Evidence:
```java
@Override
public void onAccessibilityEvent(AccessibilityEvent event) {
    KVUtils.INSTANCE.noteAccessibilityHeartbeat();
    if (event != null && event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
        XLog.d(TAG, "Notification event from: " + event.getPackageName());
    }
    AutoReplyManager.getInstance().onAccessibilityEvent(event);
}
```

---

### 2.3 UI Parsing Model

#### Screen read approach
- Uses `AccessibilityNodeInfo` traversal from `getRootInActiveWindow()`.
- Produces a **string-serialized screen tree** (compact format) via `getScreenTree()`.
- Also supports `getScreenTreeFull()` debug mode with full node properties.

#### Node identity mapping
- Generates per-snapshot node IDs (e.g., `n1`, `n2`) and maps to center coordinates in `nodeIdMap`.
- `tap_node` tool relies on this mapping.

Evidence (`ClawAccessibilityService.java`):
```java
public String getScreenTree() {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) return null;
    nodeIdMap.clear();
    nodeCounter.set(0);
    StringBuilder sb = new StringBuilder();
    buildNodeTree(root, sb, 0);
    return sb.toString();
}
```

Evidence (`ClawAccessibilityService.java`):
```java
String nodeId = "n" + nodeCounter.incrementAndGet();
nodeIdMap.put(nodeId, new int[]{cx, cy});
line.append("[").append(nodeId).append("] ");
```

---

### 2.4 Action Execution Path

#### Click / gesture strategy
- Node action first: `node.performAction(ACTION_CLICK)`
- Parent fallback click traversal
- Coordinate fallback tap (`performTap`) with `dispatchGesture`

#### Gesture APIs used
- `dispatchGesture(...)` for tap/long-press/swipe via path strokes

Evidence (`ClawAccessibilityService.java`):
```java
if (node.isClickable()) {
    boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    if (clicked) return true;
}
...
Rect bounds = new Rect();
node.getBoundsInScreen(bounds);
return performTap(bounds.centerX(), bounds.centerY());
```

Evidence (`ClawAccessibilityService.java`):
```java
public boolean performTap(int x, int y, long durationMs) {
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription.StrokeDescription stroke =
        new GestureDescription.StrokeDescription(path, 0, durationMs);
    GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
    return dispatchGestureSync(gesture);
}
```

---

## 3. The Existing LLM Engine & Prompting

### 3.1 Model Loading & Execution Mode

#### Multi-provider runtime
- Local: LiteRT-LM (`LocalLlmClient`, `LocalModelRuntime`, `EngineHolder`)
- Cloud: OpenAI/Anthropic through LangChain4j clients
- Provider selected via `ModelConfigRepository` -> `AgentConfig` -> `LlmClientFactory`

#### Local inference location
- Runs on-device using LiteRT-LM engine.
- Engine currently initialized with `Backend.GPU()` in `EngineHolder`.

#### Cloud path
- Uses network calls through LangChain4j chat models.

Evidence (`app/src/main/java/io/agents/pokeclaw/agent/llm/LlmClientFactory.kt`):
```kotlin
return when (config.provider) {
    LlmProvider.OPENAI -> OpenAiLlmClient(config, httpClientBuilder)
    LlmProvider.ANTHROPIC -> AnthropicLlmClient(config, httpClientBuilder)
    LlmProvider.LOCAL -> LocalLlmClient(config)
}
```

Evidence (`app/src/main/java/io/agents/pokeclaw/agent/llm/EngineHolder.kt`):
```kotlin
val engineConfig = EngineConfig(
    modelPath = modelPath,
    backend = Backend.GPU(),
    maxNumTokens = 8172,
    cacheDir = cacheDir
)
```

Evidence (`app/src/main/java/io/agents/pokeclaw/agent/llm/OpenAiLlmClient.kt`):
```kotlin
val response = chatModel.chat(request)
return response.toLlmResponse()
```

---

### 3.2 Prompt Construction & Screen Injection

#### Primary prompt builder
- `DefaultAgentService.runAgentLoop(...)` builds full system prompt using:
  - base prompt (local/cloud variant)
  - optional matched playbook
  - task guards
  - device context

#### Screen-state injection
- Pre-warm for task-like requests appends current screen text into user prompt.
- After action-tools, auto-attaches fresh `get_screen_info` output to tool result payload.

#### Compression/filtering
- History compression replaces older large observation payloads with placeholders.
- Tool results summarized/truncated for token control.

Evidence (`DefaultAgentService.kt`):
```kotlin
val fullSystemPrompt = buildString {
    append(basePrompt)
    append(playbookSection)
    append(inAppSearchGuard.buildPromptSection())
    append(buildDeviceContext())
}
```

Evidence (`DefaultAgentService.kt`):
```kotlin
if (screenResult.isSuccess && !screenResult.data.isNullOrBlank()) {
    "$promptForModel\n\nCurrent screen:\n${screenResult.data}"
}
```

Evidence (`DefaultAgentService.kt`):
```kotlin
private val OBSERVATION_PLACEHOLDERS = mapOf(
    "get_screen_info" to "[screen info omitted]",
    "take_screenshot" to "[screenshot result omitted]"
)
```

---

### 3.3 Output Parsing

#### Tool-call parsing strategy
- Cloud models: structured tool calls from LangChain4j response object.
- Local models (`LocalLlmClient.parseResponse`):
  1. native LiteRT message tool calls
  2. regex extraction (`<tool_call>...</tool_call>`, fenced blocks, Gemma-native token pattern)
  3. JSON repair/fallback parsing

#### Constrained grammar decoding
- Built-in grammar/constrained decoding engine: **NOT PRESENT IN CURRENT BASE**
- Current implementation is parser-driven (structured + regex + JSON fixups).

Evidence (`LocalLlmClient.kt`):
```kotlin
val toolCalls = extractToolCalls(responseText)
if (toolCalls.isNotEmpty()) {
    return LlmResponse(
        text = thinkingText,
        toolExecutionRequests = toolCalls
    )
}
```

Evidence (`LocalLlmClient.kt`):
```kotlin
private val TOOL_CALL_PATTERN = Regex("""<tool_call>(.*?)</tool_call>""", RegexOption.DOT_MATCHES_ALL)
private val GEMMA4_NATIVE_PATTERN = Regex("""<\|tool_call>(.*?)<tool_call\|>""", RegexOption.DOT_MATCHES_ALL)
private val TOOL_CALL_BLOCK_PATTERN = Regex("""```tool_call\s*\n(.*?)\n\s*```""", RegexOption.DOT_MATCHES_ALL)
```

---

## 4. The Agent Loop & State Management

### 4.1 The Core ReAct Loop

#### Main loop location
- `app/src/main/java/io/agents/pokeclaw/agent/DefaultAgentService.kt`
- Method: `runAgentLoop(userPrompt: String, callback: AgentCallback)`

#### Effective flow
1. Pre-check service readiness
2. Build prompt + message history
3. Invoke LLM
4. Parse tool calls
5. Execute tools via registry
6. Append tool results
7. Repeat until `finish`, text-only completion, budget/guard limits, cancel/error

Evidence:
```kotlin
while (iterations < maxIterations && !cancelled.get()) {
    val llmResponse = chatWithRetry(messages, callback, iterations)
    ...
    for (toolRequest in llmResponse.toolExecutionRequests) {
        val result = ToolRegistry.getInstance().executeTool(toolName, params)
        messages.add(ToolExecutionResultMessage.from(toolRequest, combinedResultData))
    }
}
```

---

### 4.2 Tool Definitions

#### Tool declaration model
- Each tool extends `BaseTool` and defines:
  - `getName()`
  - `getParameters()` (`ToolParameter` schema)
  - `execute(params)`
- Tool specs converted for LLM via `LangChain4jToolBridge.buildToolSpecifications()`.

#### Registered tool families
- Common tools: screen info, text input, system keys, app open, screenshot, device info, notifications, finish, KB tools
- Mobile tools: `tap`, `tap_node`, `long_press`, `swipe`, `scroll_to_find`, `find_and_tap`, `send_message`, `auto_reply`
- TV tools: DPAD/volume/menu/power

Evidence (`app/src/main/java/io/agents/pokeclaw/tool/ToolRegistry.kt`):
```kotlin
private fun registerMobileTools() {
    register(TapTool())
    register(TapNodeTool())
    register(LongPressTool())
    register(SwipeTool())
    register(ScrollToFindTool())
    register(FindAndTapTool())
    register(SendMessageTool())
}
```

Evidence (`app/src/main/java/io/agents/pokeclaw/agent/langchain/LangChain4jToolBridge.java`):
```java
for (BaseTool tool : ToolRegistry.getInstance().getAllTools()) {
    specs.add(toSpecification(tool));
}
```

---

### 4.3 State / Memory

#### Task state authority
- `TaskSessionStore` is explicit single-state holder for running task session state.
- Phases: `IDLE`, `RUNNING`, `STOPPING`

#### Session memory
- In-loop working memory = `messages` list in `runAgentLoop`.
- Chat-context handoff format = `TaskPromptEnvelope` markers for history/background/current request.

#### Persistence observed
- MMKV for config/status flags
- SQLite for chat index/search (`ChatDatabase`)

#### Missing systems
- Dedicated long-term autonomous planner memory graph/vector store: **NOT PRESENT IN CURRENT BASE**

Evidence (`app/src/main/java/io/agents/pokeclaw/TaskSessionStore.kt`):
```kotlin
enum class TaskSessionPhase { IDLE, RUNNING, STOPPING }

class TaskSessionStore {
    private val _state = MutableStateFlow(TaskSessionState())
    fun tryAcquire(...): Boolean { ... }
    fun markStopping(): Boolean { ... }
    fun release(): TaskSessionState { ... }
}
```

Evidence (`app/src/main/java/io/agents/pokeclaw/agent/TaskPromptEnvelope.kt`):
```kotlin
private const val HISTORY_START = "<<<POKECLAW_CHAT_HISTORY>>>"
private const val BACKGROUND_START = "<<<POKECLAW_BACKGROUND_STATE>>>"
private const val REQUEST_START = "<<<POKECLAW_CURRENT_REQUEST>>>"
```

---

## Appendix: Key Classes by Responsibility

- App bootstrap: `app/src/main/java/io/agents/pokeclaw/ClawApplication.kt`
- App-level VM + orchestration entry: `app/src/main/java/io/agents/pokeclaw/AppViewModel.kt`
- Task orchestration layer: `app/src/main/java/io/agents/pokeclaw/TaskOrchestrator.kt`
- Agent runtime loop: `app/src/main/java/io/agents/pokeclaw/agent/DefaultAgentService.kt`
- Pipeline routing (deterministic -> skill -> agent): `app/src/main/java/io/agents/pokeclaw/agent/PipelineRouter.kt`
- Accessibility control surface: `app/src/main/java/io/agents/pokeclaw/service/ClawAccessibilityService.java`
- Notification ingestion: `app/src/main/java/io/agents/pokeclaw/service/ClawNotificationListener.java`
- Auto-reply subsystem: `app/src/main/java/io/agents/pokeclaw/service/AutoReplyManager.java`
- Tool registry/spec bridge: `app/src/main/java/io/agents/pokeclaw/tool/ToolRegistry.kt`, `app/src/main/java/io/agents/pokeclaw/agent/langchain/LangChain4jToolBridge.java`
- Local LLM runtime: `app/src/main/java/io/agents/pokeclaw/agent/llm/EngineHolder.kt`, `app/src/main/java/io/agents/pokeclaw/agent/llm/LocalModelRuntime.kt`, `app/src/main/java/io/agents/pokeclaw/agent/llm/LocalLlmClient.kt`
- Model config source of truth: `app/src/main/java/io/agents/pokeclaw/agent/llm/ModelConfigRepository.kt`

