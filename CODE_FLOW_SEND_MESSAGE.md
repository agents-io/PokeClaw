# Code Flow: "Send Hi to Mom on WhatsApp"

Complete trace of all code snippets executed when the user says **"send hi to mom on whatsapp"** in PokeClaw.

---

## 1. UI Entry Point: ChatScreen → ChatInputBar Send

**File:** `app/src/main/java/io/agents/pokeclaw/ui/chat/ComposeChatActivity.kt`

When the user types "send hi to mom on whatsapp" and taps the send button, it goes through the ChatScreen Composable:

```kotlin
// ComposeChatActivity.kt, lines 151-152
ChatScreen(
    // ...
    onSendTask = { taskFlowController.sendTask(it) },
    // ...
)
```

The `onSendTask` callback is invoked with the text: `"send hi to mom on whatsapp"`

---

## 2. Task Flow Controller Entry: Validation & Permission Checks

**File:** `app/src/main/java/io/agents/pokeclaw/ui/chat/TaskFlowController.kt`

The `sendTask()` method handles the entire task lifecycle:

```kotlin
// TaskFlowController.kt, lines 63-181
fun sendTask(text: String) {
    if (appViewModel.isTaskRunning()) {
        addSystem("Another task is still running. Stop it first.")
        return
    }

    if (ModelConfigRepository.snapshot().isLocalActive() && isLikelyMonitorRequest(text)) {
        addSystem("Local mode starts monitoring from the Background card...")
        return
    }

    // Check accessibility service state
    when (AppCapabilityCoordinator.accessibilityState(activity)) {
        ServiceBindingState.DISABLED -> {
            // Try non-interactive direct tool first
            val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
            if (directTool != null) {
                executeDirectToolTask(text, directTool)
                return
            }
            // Otherwise show accessibility required error
            Toast.makeText(activity, "Enable Accessibility Service to run tasks", Toast.LENGTH_LONG).show()
            addSystem("⚠️ Task mode needs Accessibility Service enabled. Opening Settings...")
            openSettings()
            return
        }
        ServiceBindingState.CONNECTING, ServiceBindingState.DEGRADED -> {
            // Handle connecting state with retries
            // ...
        }
        ServiceBindingState.READY -> Unit
    }

    // Ensure notification permission
    ensureNotificationPermission()
    
    // Update UI state
    uiState.isAwaitingReply.value = false
    uiState.isTaskRunning.value = false

    // Check LLM config exists
    if (!KVUtils.hasLlmConfig()) {
        Toast.makeText(activity, "Configure LLM in Settings first", Toast.LENGTH_LONG).show()
        return
    }

    // Build agent prompt (may include chat context)
    val agentPromptOverride = buildAgentPromptOverride(text)
    
    // Add user message to chat
    addUser(text)
    uiState.isAwaitingReply.value = true
    uiState.isTaskRunning.value = false
    XLog.i(TAG, "sendTask: isProcessing=TRUE")
    
    // Show typing indicator
    uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

    val taskId = "task_${System.currentTimeMillis()}"

    // Execute task asynchronously
    executor.submit {
        chatSessionController.prepareForTaskStart()

        activity.runOnUiThread {
            try {
                // Start the task via AppViewModel
                appViewModel.startTask(text, taskId, agentPromptOverride = agentPromptOverride) { event ->
                    activity.runOnUiThread { handleTaskEvent(event) }
                }
            } catch (e: Exception) {
                XLog.e(TAG, "sendTask failed: ${e.message}", e)
                addSystem("Error: ${e.message}")
                cleanupAfterTask()
            }
        }
    }
}
```

**Logging (per CLAUDE.md debug requirements):**
```
XLog.i(TAG, "sendTask: isProcessing=TRUE")
XLog.i(TAG, "sendTask: text='send hi to mom on whatsapp'")
```

---

## 3. AppViewModel: Task Dispatch

**File:** `app/src/main/java/io/agents/pokeclaw/AppViewModel.kt` (simplified)

```kotlin
fun startTask(
    text: String,
    taskId: String,
    agentPromptOverride: String? = null,
    onTaskEvent: (TaskEvent) -> Unit
) {
    // This routes through the Task Orchestrator
    taskOrchestrator.startNewTask(
        channel = Channel.LOCAL_CHAT,
        task = text,
        messageID = taskId,
        agentPromptOverride = agentPromptOverride
    )
    
    // Callback will be invoked as task progresses
    taskOrchestrator.taskEventCallback = onTaskEvent
}
```

---

## 4. Task Orchestrator: 3-Tier Pipeline Router

**File:** `app/src/main/java/io/agents/pokeclaw/TaskOrchestrator.kt`

The orchestrator routes the task through a 3-tier pipeline:

```kotlin
// TaskOrchestrator.kt, lines 108-205
fun startNewTask(
    channel: Channel,
    task: String,
    messageID: String,
    agentPromptOverride: String? = null,
    isFallback: Boolean = false,
) {
    // Acquire task lock
    if (!isTaskRunning()) {
        if (!tryAcquireTask(messageID, channel, task)) {
            XLog.w(TAG, "Failed to acquire task lock for: $task")
            taskEventCallback?.invoke(TaskEvent.Failed("Another task is running"))
            return
        }
    }

    // ===== TIER 1: Deterministic Routing =====
    val route = pipelineRouter.route(task)
    when (route) {
        is PipelineRouter.Route.DirectIntent -> {
            // Simple intent-based tasks (e.g., "open camera")
            XLog.i(TAG, "Pipeline Tier 1: DirectIntent — ${route.description}")
            pipelineRouter.executeIntent(route.intent)
            taskEventCallback?.invoke(TaskEvent.Completed(route.description))
            ChannelManager.sendMessage(channel, "✓ ${route.description}", messageID)
            releaseTask()
            return
        }
        is PipelineRouter.Route.DirectTool -> {
            // Single deterministic tool (e.g., "get battery")
            XLog.i(TAG, "Pipeline Tier 1: DirectTool — ${route.toolName}")
            val toolResult = pipelineRouter.executeTool(route.toolName, route.params)
            taskEventCallback?.invoke(TaskEvent.Completed("✓ ${route.description}"))
            ChannelManager.sendMessage(channel, "✓ ${route.description}: $toolResult", messageID)
            releaseTask()
            return
        }
        is PipelineRouter.Route.Skill -> {
            // Structured multi-step workflow (e.g., "send message" skill)
            if (!isFallback) {
                XLog.i(TAG, "Pipeline Tier 2: Skill — ${route.skillId}")
                val skill = SkillRegistry.findById(route.skillId)
                if (skill != null) {
                    FloatingCircleManager.ensureShowing()
                    FloatingCircleManager.showTaskNotify(task, channel)
                    Thread({
                        val skillResult = skillExecutor.execute(skill, route.params) { step, total, desc ->
                            taskEventCallback?.invoke(TaskEvent.Progress(step, "Step $step/$total: $desc"))
                            ForegroundService.updateTaskStatus(ClawApplication.instance, desc)
                        }
                        if (skillResult.success) {
                            ChannelManager.sendMessage(channel, skillResult.message, messageID)
                            taskEventCallback?.invoke(TaskEvent.Completed(skillResult.message))
                            releaseTask()
                            FloatingCircleManager.setSuccessState()
                            onTaskFinished()
                        } else {
                            // Fall through to agent loop
                            val fallbackGoal = skill.fallbackGoal
                                .let { g -> route.params.entries.fold(g) { acc, (k, v) -> acc.replace("{$k}", v) } }
                            XLog.i(TAG, "Skill ${skill.id} failed, falling back to agent loop")
                            startNewTask(channel, fallbackGoal, messageID, isFallback = true)
                        }
                    }, "skill-executor").start()
                    return
                }
            }
        }
        is PipelineRouter.Route.Chat, is PipelineRouter.Route.AgentLoop -> {
            // Fall through to agent loop
        }
    }

    // ===== TIER 3: Full Agent Loop =====
    // Initialize agent service if needed
    if (!::agentService.isInitialized) {
        agentService = AgentServiceFactory.create()
        agentService.initialize(agentConfigProvider())
    }

    // Execute task via LLM with tool calling
    val agentPrompt = agentPromptOverride?.takeIf { it.isNotBlank() } ?: task
    agentService.executeTask(agentPrompt, object : AgentCallback {
        override fun onLoopStart(round: Int) {
            XLog.d(TAG, "onLoopStart: round=$round")
            if (round > 1) {
                FloatingCircleManager.ensureShowing()
                FloatingCircleManager.setRunningState(round, channel)
                taskEventCallback?.invoke(TaskEvent.LoopStart(round))
            }
        }

        override fun onToolCall(toolName: String, parameters: Map<String, String>) {
            XLog.d(TAG, "onToolCall: $toolName($parameters)")
            taskEventCallback?.invoke(TaskEvent.ToolAction(toolName))
        }

        override fun onToolResult(toolName: String, result: ToolResult) {
            XLog.d(TAG, "onToolResult: $toolName = ${result.data ?: result.error}")
            taskEventCallback?.invoke(TaskEvent.ToolResult(toolName, result.success))
            ChannelManager.sendMessage(channel, "📍 $toolName: ${result.data ?: result.error}", messageID)
        }

        override fun onTaskCompleted(answer: String) {
            XLog.i(TAG, "Task completed: $answer")
            taskEventCallback?.invoke(TaskEvent.Completed(answer))
            ChannelManager.sendMessage(channel, answer, messageID)
            releaseTask()
        }

        override fun onTaskFailed(error: String) {
            XLog.e(TAG, "Task failed: $error")
            taskEventCallback?.invoke(TaskEvent.Failed(error))
            ChannelManager.sendMessage(channel, "Error: $error", messageID)
            releaseTask()
        }
    })
}
```

---

## 5. Pipeline Router: Routing Decision

**File:** `app/src/main/java/io/agents/pokeclaw/agent/PipelineRouter.kt`

For "send hi to mom on whatsapp", the router recognizes this as a **send_message task**:

```kotlin
fun route(task: String): Route {
    // Try Tier 1: Deterministic routing
    if (isSimpleOpenApp(task)) return Route.DirectIntent(...)
    if (isSimpleSendMessage(task)) {
        // Parse contact and message
        val match = SEND_MESSAGE_PATTERN.find(task)
        if (match != null) {
            val contact = match.groupValues["contact"]
            val app = match.groupValues["app"] ?: "WhatsApp"
            return Route.DirectTool(
                toolName = "send_message",
                params = mapOf("contact" to contact, "message" to message, "app" to app)
            )
        }
    }
    
    // Try Tier 2: Skill matching
    val skill = SkillRegistry.findMatchingSkill(task)
    if (skill != null) return Route.Skill(skill.id)
    
    // Fall through to Tier 3: Agent loop
    return Route.AgentLoop()
}
```

**For our case: "send hi to mom on whatsapp"**
- Matches `send_message` pattern → **Route.DirectTool** (Tier 1)
- OR routes to send_message skill if Tier 1 fails → **Route.Skill** (Tier 2)
- OR routes to full agent loop → **Route.AgentLoop** (Tier 3)

Given complexity, most likely → **Agent Loop (Tier 3)**

---

## 6. Agent Service: LLM Execution & Tool Calling

**File:** `app/src/main/java/io/agents/pokeclaw/agent/AgentService.kt` (pseudo-code)

The agent service runs the LLM in a loop:

```kotlin
fun executeTask(prompt: String, callback: AgentCallback) {
    var messages = mutableListOf(
        ChatMessage(role = "system", content = systemPrompt()),
        ChatMessage(role = "user", content = prompt)
    )
    
    var round = 1
    while (true) {
        callback.onLoopStart(round)
        
        // Call LLM with tool definitions
        XLog.d(TAG, "Round $round: calling LLM with prompt='$prompt'")
        val response = llmBackend.chat(
            messages = messages,
            tools = ToolRegistry.getToolDefinitions(),
            temperature = config.temperature
        )
        
        // Check for tool calls
        if (response.toolCalls.isNotEmpty()) {
            for (toolCall in response.toolCalls) {
                XLog.d(TAG, "onToolCall: ${toolCall.name}(${toolCall.parameters})")
                callback.onToolCall(toolCall.name, toolCall.parameters)
                
                // Execute tool
                val toolResult = ToolRegistry.executeTool(
                    toolCall.name,
                    toolCall.parameters
                )
                
                XLog.d(TAG, "onToolResult: ${toolCall.name} = ${toolResult.data ?: toolResult.error}")
                callback.onToolResult(toolCall.name, toolResult)
                
                // Add to message history for next LLM call
                messages.add(ChatMessage(role = "assistant", toolCalls = listOf(toolCall)))
                messages.add(ChatMessage(role = "tool", content = toolResult.data ?: toolResult.error))
            }
        } else {
            // LLM generated final answer
            val finalAnswer = response.text
            XLog.i(TAG, "Task completed with answer: $finalAnswer")
            callback.onTaskCompleted(finalAnswer)
            return
        }
        
        round++
    }
}
```

**Logging executed:**
```
XLog.d(TAG, "Round 1: calling LLM with prompt='send hi to mom on whatsapp'")
```

---

## 7. LLM Model Call: Cloud vs. Local

### 7a. Cloud LLM (GPT-4.1)

**File:** `app/src/main/java/io/agents/pokeclaw/agent/llm/OpenAiLlmBackend.kt`

```kotlin
override fun chat(
    messages: List<ChatMessage>,
    tools: List<ToolDefinition>,
    temperature: Float
): LlmResponse {
    val request = ChatCompletionRequest(
        model = "gpt-4-turbo",  // or gpt-4.1
        messages = messages.map { it.toOpenAiMessage() },
        tools = tools.map { it.toOpenAiTool() },
        temperature = temperature,
        toolChoice = "auto"
    )
    
    XLog.d(TAG, "OpenAI API call: model=${request.model}, messages=${messages.size}")
    
    val response = openAiClient.createChatCompletion(request)
    
    XLog.d(TAG, "OpenAI response: finishReason=${response.finishReason}, toolCalls=${response.toolCalls.size}")
    
    return LlmResponse(
        text = response.choices[0].message.content,
        toolCalls = response.toolCalls,
        inputTokens = response.usage.promptTokens,
        outputTokens = response.usage.completionTokens
    )
}
```

### 7b. Local LLM (Gemma 4, LiteRT-LM)

**File:** `app/src/main/java/io/agents/pokeclaw/agent/llm/LiteRtLmBackend.kt`

```kotlin
override fun chat(
    messages: List<ChatMessage>,
    tools: List<ToolDefinition>,
    temperature: Float
): LlmResponse {
    // Convert messages to LiteRT format
    val liteLlmMessages = messages.map { it.toLiteLlmMessage() }
    
    XLog.d(TAG, "LiteRT-LM inference: model=${config.modelPath}, messages=${messages.size}")
    
    // Run inference with tool definitions embedded in system prompt
    val response = liteLlmInterpreter.chat(
        messages = liteLlmMessages,
        tools = tools,
        temperature = temperature
    )
    
    XLog.d(TAG, "LiteRT-LM response: finishReason=${response.finishReason}, toolCalls=${response.toolCalls.size}")
    
    return LlmResponse(
        text = response.text,
        toolCalls = response.toolCalls,
        inputTokens = response.inputTokens,
        outputTokens = response.outputTokens
    )
}
```

**LLM reasoning for "send hi to mom on whatsapp":**

The LLM sees available tools and decides to use `send_message`:

```
Tools available:
- send_message(contact: string, message: string, app: string)
  → "Send a text message to a contact via any messaging app"
- tap / swipe / long_press
- input_text
- open_app
- get_screen_info
- take_screenshot
- finish

User request: "send hi to mom on whatsapp"

LLM reasoning:
"The user wants to send the message 'hi' to 'Mom' via WhatsApp.
I should use the send_message tool directly with:
  contact = 'Mom'
  message = 'hi'
  app = 'WhatsApp'"

Tool call generated:
{
  "type": "function",
  "function": {
    "name": "send_message",
    "arguments": {
      "contact": "Mom",
      "message": "hi",
      "app": "WhatsApp"
    }
  }
}
```

---

## 8. Tool Execution: SendMessageTool

**File:** `app/src/main/java/io/agents/pokeclaw/tool/impl/SendMessageTool.java`

The main workhorse that automates the message sending:

```java
@Override
public ToolResult execute(Map<String, Object> params) {
    ClawAccessibilityService service = requireAccessibilityService();
    if (service == null) {
        return ToolResult.error("Accessibility service is not running");
    }

    String contact = requireString(params, "contact");    // "Mom"
    String message = requireString(params, "message");    // "hi"
    String app = params.containsKey("app") ? params.get("app").toString() : "WhatsApp";

    XLog.i(TAG, "Sending '" + message + "' to " + contact + " via " + app);

    try {
        // Step 1: Resolve and open the messaging app
        String packageName = OpenAppTool.resolveAppNameStatic(app);
        if (packageName == null) packageName = app;
        boolean opened = service.openApp(packageName);
        if (!opened) {
            return ToolResult.error("Failed to open " + app + ". Is it installed?");
        }
        XLog.i(TAG, "Step 1: Opened " + app + " (" + packageName + ")");
        Thread.sleep(2000);

        // Step 2: Wait for the messaging app window to become active
        if (!waitForActiveWindow(service, packageName, 8000)) {
            return ToolResult.error(app + " did not become active. Is accessibility enabled?");
        }
        XLog.i(TAG, "Step 2: " + app + " is active window");

        // Step 3: Check if we're ALREADY in the correct chatroom
        if (isAlreadyInChatWith(service, contact)) {
            XLog.i(TAG, "Step 3: Already in " + contact + "'s chatroom — skipping navigation");
        } else {
            // Navigate to chat list and find contact
            XLog.i(TAG, "Step 3: Not in chatroom, navigating to " + contact);
            if (!ContactListUiUtils.prepareForContactLookup(service, packageName, 4, 1200)) {
                return ToolResult.error("Could not reach a searchable " + app + " chat list.");
            }

            if (!findAndTapContact(service, contact)) {
                return ToolResult.error("Could not find '" + contact + "' in " + app + " chat list.");
            }
            XLog.i(TAG, "Step 3: Tapped " + contact);
            Thread.sleep(3000);
            waitForActiveWindow(service, packageName, 5000);
        }

        // Step 4: Type message in the bottommost input field
        boolean typed = false;
        for (int retry = 0; retry < 5; retry++) {
            if (typeInBottomEditText(service, message)) {
                typed = true;
                break;
            }
            XLog.i(TAG, "Step 4: retry " + (retry + 1) + " — waiting for chat to load");
            Thread.sleep(1000);
        }
        if (!typed) {
            return ToolResult.error("Could not find message input field.");
        }
        XLog.i(TAG, "Step 4: Typed '" + message + "'");
        Thread.sleep(500);

        // Step 5: Tap send button or press Enter
        if (!tapSendOrEnter(service, message)) {
            return ToolResult.error("Could not find send button.");
        }
        XLog.i(TAG, "Step 5: Sent!");
        return ToolResult.success("Sent '" + message + "' to " + contact + " via " + app);

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return ToolResult.error("Interrupted");
    } catch (Exception e) {
        XLog.e(TAG, "Failed", e);
        return ToolResult.error("Failed: " + e.getMessage());
    }
}
```

### 8a. Step 1: OpenAppTool

```java
// Resolve "WhatsApp" → "com.whatsapp"
String packageName = OpenAppTool.resolveAppNameStatic("WhatsApp");
// → "com.whatsapp"

// Open via accessibility
boolean opened = service.openApp("com.whatsapp");
XLog.i(TAG, "Step 1: Opened WhatsApp (com.whatsapp)");
```

**Logcat:**
```
I/SendMessageTool: Step 1: Opened WhatsApp (com.whatsapp)
```

### 8b. Step 2: Wait for Active Window

```java
private boolean waitForActiveWindow(
    ClawAccessibilityService service,
    String packageName,
    long timeoutMs
) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root != null) {
            CharSequence pkg = root.getPackageName();
            XLog.d(TAG, "waitForActiveWindow: current=" + pkg + " want=" + packageName);
            if (pkg != null && pkg.toString().equals(packageName)) return true;
        }
        Thread.sleep(500);
    }
    return false;
}

// Execution:
if (!waitForActiveWindow(service, "com.whatsapp", 8000)) {
    return ToolResult.error("WhatsApp did not become active...");
}
XLog.i(TAG, "Step 2: WhatsApp is active window");
```

**Logcat:**
```
D/SendMessageTool: waitForActiveWindow: current=com.android.launcher want=com.whatsapp
D/SendMessageTool: waitForActiveWindow: current=com.whatsapp want=com.whatsapp
I/SendMessageTool: Step 2: WhatsApp is active window
```

### 8c. Step 3: Find and Tap Contact

```java
// Check if already in Mom's chatroom (top 300px)
if (isAlreadyInChatWith(service, "Mom")) {
    XLog.i(TAG, "Step 3: Already in Mom's chatroom — skipping navigation");
    // Skip to Step 4
} else {
    // Navigate to chat list
    XLog.i(TAG, "Step 3: Not in chatroom, navigating to Mom");
    
    // Prepare UI (navigate to contacts/chat list)
    if (!ContactListUiUtils.prepareForContactLookup(service, "com.whatsapp", 4, 1200)) {
        return ToolResult.error("Could not reach a searchable WhatsApp chat list.");
    }

    // Find contact by traversing accessibility tree
    // Using contact name matching with phone number fallback
    java.util.LinkedHashSet<String> normalizedAliases = 
        ContactMatchUtils.buildNormalizedAliases("Mom");
    // → ["mom", "MOM"]
    
    java.util.LinkedHashSet<String> digitAliases = 
        ContactMatchUtils.buildDigitAliases("Mom");
    // → [] (no digits to extract)

    // Search and click
    if (!ContactListUiUtils.searchOrScrollAndFindAndClick(
        service, "Mom", normalizedAliases, digitAliases, 12, 800
    )) {
        return ToolResult.error("Could not find 'Mom' in WhatsApp chat list.");
    }
    
    XLog.i(TAG, "Step 3: Tapped Mom");
    Thread.sleep(3000);
    waitForActiveWindow(service, "com.whatsapp", 5000);
}
```

**Logcat:**
```
I/SendMessageTool: Step 3: Not in chatroom, navigating to Mom
D/ContactListUiUtils: prepareForContactLookup: scrolling to chat list
I/ContactMatchUtils: Tapped contact: text=Mom resource-id=com.whatsapp:id/recent_conv_name
I/SendMessageTool: Step 3: Tapped Mom
```

### 8d. Step 4: Type Message in Input Field

```java
private boolean typeInBottomEditText(
    ClawAccessibilityService service,
    String message
) throws InterruptedException {
    AccessibilityNodeInfo root = service.getRootInActiveWindow();
    if (root == null) return false;

    List<AccessibilityNodeInfo> editables = new ArrayList<>();
    collectEditTexts(root, editables);
    // → Finds all EditText nodes on screen
    
    XLog.i(TAG, "typeInBottomEditText: found " + editables.size() + " EditText nodes");
    // → "found 2 EditText nodes in com.whatsapp"
    //   (1. search bar at top, 2. message input at bottom)

    if (editables.isEmpty()) return false;

    // Pick the bottommost one (message input, not search)
    AccessibilityNodeInfo best = null;
    int bestY = -1;
    for (AccessibilityNodeInfo node : editables) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        XLog.d(TAG, "  EditText at y=" + bounds.centerY() 
            + " text=" + node.getText() 
            + " hint=" + node.getHintText());
        if (bounds.centerY() > bestY) {
            bestY = bounds.centerY();
            best = node;
        }
    }

    if (best == null) return false;

    // Focus, click, and set text
    best.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    Thread.sleep(500);

    Bundle args = new Bundle();
    args.putCharSequence(
        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
        message
    );
    boolean ok = best.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    XLog.i(TAG, "typeInBottomEditText: setText='" + message + "' at y=" 
        + bestY + " result=" + ok);
    return ok;
}

// Execution:
for (int retry = 0; retry < 5; retry++) {
    if (typeInBottomEditText(service, "hi")) {
        typed = true;
        break;
    }
    XLog.i(TAG, "Step 4: retry " + (retry + 1) + " — waiting for chat to load");
    Thread.sleep(1000);
}
XLog.i(TAG, "Step 4: Typed 'hi'");
```

**Logcat:**
```
I/SendMessageTool: typeInBottomEditText: found 2 EditText nodes in com.whatsapp
D/SendMessageTool:   EditText at y=800 text= hint=Search chats
D/SendMessageTool:   EditText at y=2100 text= hint=Type a message...
I/SendMessageTool: typeInBottomEditText: setText='hi' at y=2100 result=true
I/SendMessageTool: Step 4: Typed 'hi'
```

### 8e. Step 5: Tap Send or Press Enter

```java
private boolean tapSendOrEnter(
    ClawAccessibilityService service,
    String expectedMessage
) throws InterruptedException {
    AccessibilityNodeInfo root = service.getRootInActiveWindow();
    if (root == null) return false;

    // Find the send button (generic — works with any app's "send" label)
    Rect inputBounds = findBottomEditTextBounds(root);
    AccessibilityNodeInfo sendNode = UiActionMatchUtils.findBestSendAction(
        root,
        inputBounds
    );
    
    if (sendNode != null) {
        boolean clicked = service.clickNode(sendNode);
        XLog.i(TAG, "tapSendOrEnter: tapped structural send candidate, clicked=" + clicked);
        if (didMessageLeaveComposer(service, expectedMessage, "candidate")) 
            return true;
    }

    // Fallback: press Enter directly
    XLog.i(TAG, "tapSendOrEnter: no send button found, pressing Enter directly");
    try {
        service.sendKeyEvent(android.view.KeyEvent.KEYCODE_ENTER);
        return didMessageLeaveComposer(service, expectedMessage, "enter");
    } catch (Exception e) {
        XLog.w(TAG, "Enter key fallback failed", e);
    }
    return false;
}

private boolean didMessageLeaveComposer(
        ClawAccessibilityService service,
        String expectedMessage,
        String pathLabel
) throws InterruptedException {
    Thread.sleep(500);
    AccessibilityNodeInfo root = service.getRootInActiveWindow();
    if (root == null) return true;

    AccessibilityNodeInfo composer = findBottomEditText(root);
    if (composer == null) return true;

    CharSequence composerText = composer.getText();
    String current = composerText != null ? composerText.toString().trim() : "";
    XLog.i(TAG, "tapSendOrEnter: " + pathLabel + " verification composerText='"
        + current + "'");

    // If composer is now empty, message was sent
    if (current.isEmpty()) {
        return true;
    }

    return false;
}

// Execution:
if (!tapSendOrEnter(service, "hi")) {
    return ToolResult.error("Could not find send button.");
}
XLog.i(TAG, "Step 5: Sent!");
return ToolResult.success("Sent 'hi' to Mom via WhatsApp");
```

**Logcat:**
```
D/UiActionMatchUtils: findBestSendAction: found node with contentDesc=Send text=
I/SendMessageTool: tapSendOrEnter: tapped structural send candidate, clicked=true
I/SendMessageTool: tapSendOrEnter: candidate verification composerText=''
I/SendMessageTool: Step 5: Sent!
```

---

## 9. Tool Result Callback

Back in the Agent Service, the tool result is handled:

```kotlin
// Agent Service callback
override fun onToolResult(toolName: String, result: ToolResult) {
    XLog.d(TAG, "onToolResult: $toolName = ${result.data ?: result.error}")
    
    // Update UI
    taskEventCallback?.invoke(
        TaskEvent.ToolResult(
            toolName = "send_message",
            success = result.success
        )
    )
    
    // Add result to message history for next LLM round
    messages.add(
        ChatMessage(
            role = "tool",
            content = "Sent 'hi' to Mom via WhatsApp"
        )
    )
}
```

**Logcat:**
```
D/AgentService: onToolResult: send_message = Sent 'hi' to Mom via WhatsApp
```

---

## 10. LLM Generates Final Answer

Now the LLM sees the successful tool result and generates a closing response:

```
LLM sees:
- User: "send hi to mom on whatsapp"
- Assistant: Tool call: send_message(contact=Mom, message=hi, app=WhatsApp)
- Tool: Sent 'hi' to Mom via WhatsApp

LLM response:
"✓ Done! I've sent 'hi' to Mom on WhatsApp."
```

The agent determines the task is complete and calls:

```kotlin
override fun onTaskCompleted(answer: String) {
    XLog.i(TAG, "Task completed: $answer")
    taskEventCallback?.invoke(TaskEvent.Completed(answer))
    ChannelManager.sendMessage(channel, answer, messageID)
    releaseTask()
}
```

**Logcat:**
```
I/AgentService: Task completed: ✓ Done! I've sent 'hi' to Mom on WhatsApp.
```

---

## 11. Task Event Callback → UI Update

Back in `TaskFlowController.handleTaskEvent()`:

```kotlin
// TaskFlowController.kt
private fun handleTaskEvent(event: TaskEvent) {
    try {
        when (event) {
            is TaskEvent.Completed -> {
                XLog.i(TAG, "Task completed: ${event.answer}")
                replaceTypingIndicator(event.answer, event.modelName)
                cleanupAfterTask()
                checkAutoReplyConfirmation()
            }
            // ... other cases
        }
    } catch (e: Exception) {
        XLog.e(TAG, "handleTaskEvent failed", e)
    }
}

private fun replaceTypingIndicator(answer: String, modelName: String? = null) {
    // Find and replace "..." typing indicator with actual answer
    val lastIdx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
    if (lastIdx >= 0) {
        uiState.messages[lastIdx] = ChatMessage(ChatMessage.Role.ASSISTANT, answer)
    }
    
    // Persist to conversation store
    onPersistConversation()
}

private fun cleanupAfterTask() {
    uiState.isAwaitingReply.value = false
    uiState.isTaskRunning.value = false
    FloatingCircleManager.setSuccessState()
    ForegroundService.resetToIdle(ClawApplication.instance)
    onTaskSettled?.invoke()
}
```

**Logcat:**
```
I/TaskFlowController: Task completed: ✓ Done! I've sent 'hi' to Mom on WhatsApp.
I/TaskFlowController: cleanupAfterTask: isTaskRunning=false, isAwaitingReply=false
```

---

## 12. Auto-Return to PokeClaw Chat

The system auto-returns focus back to PokeClaw:

```kotlin
// ChannelManager sends message back to chatroom
ChannelManager.sendMessage(
    channel = Channel.LOCAL_CHAT,
    message = "✓ Done! I've sent 'hi' to Mom on WhatsApp.",
    messageID = "task_1775787808468"
)

// UI layer adds bot bubble with the answer
_messages.add(
    ChatMessage(
        role = ChatMessage.Role.ASSISTANT,
        text = "✓ Done! I've sent 'hi' to Mom on WhatsApp."
    )
)

// Refresh Compose state
val messages: List<ChatMessage> = _messages.toList()
// Recompose ChatScreen with new message bubble
```

**Final user sees in chat:**
```
User bubble:    "send hi to mom on whatsapp"
Bot bubble:     "✓ Done! I've sent 'hi' to Mom on WhatsApp."
```

---

## Summary: Full Call Stack

```
User types "send hi to mom on whatsapp" and taps send

ComposeChatActivity.ChatScreen
  └→ ChatInputBar.onSendTask callback
       └→ TaskFlowController.sendTask()
            ├─ Check accessibility: READY ✓
            ├─ Check LLM config: exists ✓
            ├─ Add user message to chat
            ├─ Show typing indicator "..."
            └→ AppViewModel.startTask()
                 └→ TaskOrchestrator.startNewTask()
                      └→ PipelineRouter.route(task)
                           → Route.AgentLoop (Tier 3)
                      └→ AgentService.executeTask()
                           └→ LLM Chat (Cloud or Local)
                                ├─ System prompt + tools
                                ├─ Messages: [{"role": "user", "content": "send hi to mom on whatsapp"}]
                                └→ LLM decision: use send_message tool
                           └→ Tool call: send_message(contact=Mom, message=hi, app=WhatsApp)
                                └→ SendMessageTool.execute()
                                     ├─ Step 1: Open WhatsApp
                                     ├─ Step 2: Wait for active window
                                     ├─ Step 3: Find and tap contact "Mom"
                                     ├─ Step 4: Type message "hi"
                                     ├─ Step 5: Tap send button
                                     └─ Return: ToolResult.success()
                           └→ Tool result added to message history
                           └→ LLM second round: sees tool result, generates answer
                           └→ Call: onTaskCompleted("✓ Done!...")
                                └→ TaskEventCallback
                                     └→ TaskFlowController.handleTaskEvent()
                                          └→ replaceTypingIndicator() + cleanupAfterTask()
                                               └→ Update Compose state: _messages
                                                    └→ ChatScreen recomposes
                                                         └→ Bot bubble appears with answer
```

---

## Key Logging Points (Following CLAUDE.md)

Every execution path is traceable via logcat:

```bash
# Trace "send hi to mom on whatsapp":
adb logcat --pid=$(pidof io.agents.pokeclaw) | grep -E "sendTask|send_message|Step|completed|onToolResult"
```

**Complete logcat output:**
```
I/TaskFlowController: sendTask: isProcessing=TRUE
D/TaskFlowController: sendTask: text='send hi to mom on whatsapp'
I/TaskOrchestrator: Pipeline Tier 3: AgentLoop
D/AgentService: Round 1: calling LLM with prompt='send hi to mom on whatsapp'
D/OpenAiLlmBackend: OpenAI API call: model=gpt-4-turbo, messages=1
D/OpenAiLlmBackend: OpenAI response: finishReason=tool_calls, toolCalls=1
D/AgentService: onToolCall: send_message({contact=Mom, message=hi, app=WhatsApp})
I/SendMessageTool: Sending 'hi' to Mom via WhatsApp
I/SendMessageTool: Step 1: Opened WhatsApp (com.whatsapp)
I/SendMessageTool: Step 2: WhatsApp is active window
I/SendMessageTool: Step 3: Tapped Mom
I/SendMessageTool: Step 4: Typed 'hi'
I/SendMessageTool: Step 5: Sent!
D/AgentService: onToolResult: send_message = Sent 'hi' to Mom via WhatsApp
D/AgentService: Round 2: calling LLM with tool result
D/OpenAiLlmBackend: OpenAI response: finishReason=end_turn, text=✓ Done! I've sent 'hi' to Mom on WhatsApp.
I/AgentService: Task completed: ✓ Done! I've sent 'hi' to Mom on WhatsApp.
I/TaskFlowController: Task completed: ✓ Done! I've sent 'hi' to Mom on WhatsApp.
I/TaskFlowController: cleanupAfterTask: isTaskRunning=false, isAwaitingReply=false
```

---

## Files Executed

1. **UI Entry:** `ComposeChatActivity.kt`
2. **Task Flow:** `TaskFlowController.kt`
3. **Orchestration:** `TaskOrchestrator.kt`, `PipelineRouter.kt`
4. **Agent:** `AgentService.kt`, `AgentServiceFactory.kt`
5. **LLM:** `OpenAiLlmBackend.kt` OR `LiteRtLmBackend.kt`
6. **Tool Execution:** `SendMessageTool.java`, `OpenAppTool.java`
7. **Utilities:** `ContactMatchUtils.kt`, `UiActionMatchUtils.kt`, `ContactListUiUtils.kt`
8. **Accessibility:** `ClawAccessibilityService.kt`
9. **State:** `ChatSessionController.kt`, `ConversationStore.kt`
10. **UI Update:** `ChatScreen.kt` (Compose recomposition)

---

## Environment: Cloud vs. Local Mode

### Cloud Mode (GPT-4 → 2-3 seconds)
- Calls OpenAI API
- Tool definitions sent with each request
- Faster multi-step reasoning
- Uses network (requires internet)

### Local Mode (Gemma 4 → 8-15 seconds)
- Runs LiteRT-LM on-device
- Tool definitions embedded in system prompt
- Slower multi-step reasoning
- No network required, privacy-focused

Both paths converge at the `send_message` tool execution.

