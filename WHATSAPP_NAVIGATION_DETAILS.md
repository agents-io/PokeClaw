# WhatsApp Navigation: "What Happens After Opening WhatsApp?"

Based on the code in `SendMessageTool.java` and `ContactListUiUtils.java`, here are the exact answers to your questions:

---

## Question 1: What Does It Do Next After Navigating to WhatsApp Screen?

**Answer: It "prepares for contact lookup" — a diagnostic loop that gets the screen into the right state to search for contacts.**

### The Flow:

After WhatsApp opens (Step 1) and becomes active (Step 2), the code calls:

```java
// SendMessageTool.java, line 104
if (!ContactListUiUtils.prepareForContactLookup(service, packageName, 4, 1200)) {
    return ToolResult.error("Could not reach a searchable " + app + " chat list.");
}
```

This is NOT a single action — it's a **loop-based diagnostic** that tries up to **4 times** to get the screen into a state where contacts can be searched.

---

## Question 2: Which Code Is Executed?

**Answer: `ContactListUiUtils.prepareForContactLookup()` — lines 43-77**

```java
public static boolean prepareForContactLookup(
    ClawAccessibilityService service,
    String packageName,
    int maxBacks,        // 4 (number of attempts)
    long settleMs        // 1200 (ms to wait between attempts)
) throws InterruptedException {
    int attempts = Math.min(Math.max(maxBacks, 1), 6);
    // attempts = 4 (capped at 6 max)
    
    for (int attempt = 0; attempt <= attempts; attempt++) {
        // Attempt 0, 1, 2, 3, 4 (5 total iterations)
        
        // Check if screen is ready
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (isContactLookupReady(root)) {
            XLog.i(TAG, "prepareForContactLookup: ready on attempt=" + attempt);
            return true;  // SUCCESS — screen is ready to search contacts
        }

        // Stop if we've exhausted all attempts
        if (attempt == attempts) {
            break;
        }

        // NOT ready yet — try to fix it
        CharSequence activePackage = root != null ? root.getPackageName() : null;
        if (activePackage == null || !activePackage.toString().equals(packageName)) {
            // PROBLEM: WhatsApp lost focus (another app became active)
            // SOLUTION: Reopen WhatsApp
            XLog.i(TAG, "prepareForContactLookup: app not active, reopening " + packageName);
            service.openApp(packageName);
        } else {
            // PROBLEM: WhatsApp is active, but screen state is wrong
            // (e.g., inside a chat, or a modal dialog is open)
            // SOLUTION: Press back button to navigate to contact list
            XLog.i(TAG, "prepareForContactLookup: screen not ready, pressing back");
            service.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            );
        }

        // Wait for screen to settle
        Thread.sleep(Math.max(settleMs, 700L));  // Wait 1200ms minimum
    }

    // After all attempts, do final check
    AccessibilityNodeInfo root = service.getRootInActiveWindow();
    boolean ready = isContactLookupReady(root);
    XLog.i(TAG, "prepareForContactLookup: final ready=" + ready);
    return ready;  // Return true/false
}
```

### The Ready Check: `isContactLookupReady()`

```java
// ContactListUiUtils.java, lines 325-337
public static boolean isContactLookupReady(AccessibilityNodeInfo root) {
    if (root == null) return false;

    // Check 1: Is there a search field visible?
    if (UiActionMatchUtils.findBestSearchField(root) != null) return true;

    // Check 2: Or does the screen have chat list signals?
    int[] metrics = new int[3];
    collectVisibleListSignals(root, metrics);
    int visibleTextRows = metrics[0];        // Count of text nodes (contact/chat names)
    int clickableRows = metrics[1];          // Count of clickable rows
    int scrollableContainers = metrics[2];   // Count of scrollable lists

    // The screen is "ready" if it has:
    // - At least 1 scrollable container (a list view)
    // - At least 3 visible text rows (shows multiple contacts)
    // - At least 2 clickable rows (can tap on contacts)
    return scrollableContainers > 0 && visibleTextRows >= 3 && clickableRows >= 2;
}
```

---

## Question 3: How Does It Get to the Contact?

**Answer: Two-stage search (Search first, then Scroll)**

After the screen is ready, the code calls:

```java
// SendMessageTool.java, line 108
if (!findAndTapContact(service, contact)) {
    return ToolResult.error("Could not find '" + contact + "' in " + app + " chat list.");
}
XLog.i(TAG, "Step 3: Tapped " + contact);
```

Which routes to:

```java
// SendMessageTool.java, lines 207-211
private boolean findAndTapContact(ClawAccessibilityService service, String contact) 
        throws InterruptedException {
    java.util.LinkedHashSet<String> normalizedAliases = 
        ContactMatchUtils.buildNormalizedAliases(contact);  // ["mom", "mom"]
    java.util.LinkedHashSet<String> digitAliases = 
        ContactMatchUtils.buildDigitAliases(contact);      // [] (no digits in "Mom")
    
    return ContactListUiUtils.searchOrScrollAndFindAndClick(
        service, contact, normalizedAliases, digitAliases, 12, 800
    );
}
```

### Stage 1: Search

```java
// ContactListUiUtils.java, lines 125-155
public static boolean searchOrScrollAndFindAndClick(
    ClawAccessibilityService service,
    String rawQuery,            // "Mom"
    LinkedHashSet<String> normalizedAliases,  // ["mom"]
    LinkedHashSet<String> digitAliases,       // []
    int maxScrolls,             // 12
    long settleMs               // 800ms
) throws InterruptedException {
    // Try searching up to 3 times (recovery attempts)
    for (int recoveryAttempt = 0; recoveryAttempt < 3; recoveryAttempt++) {
        // Try to find a search field and type "Mom"
        SearchAttemptResult searchResult = trySearchAndClick(
            service, rawQuery, normalizedAliases, digitAliases, settleMs
        );
        
        if (searchResult == SearchAttemptResult.FOUND) {
            // FOUND IT! Contact tapped during search
            return true;
        }
        
        if (searchResult == SearchAttemptResult.NO_MATCH) {
            // No search field available, or contact not in search results
            // Break out and try scrolling instead
            break;
        }

        // Search UI was present but something went wrong
        // Press back and try again
        XLog.i(TAG, "searchOrScrollAndFindAndClick: recovering from " + searchResult);
        service.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        );
        Thread.sleep(Math.max(settleMs, 700L));
        if (!prepareForContactLookup(service, activePackageName(service), 2, settleMs)) {
            break;
        }
    }
    
    // If search didn't work, fall through to scrolling
    return scrollAndFindAndClick(service, normalizedAliases, digitAliases, maxScrolls, settleMs);
}
```

### The Search Attempt: `trySearchAndClick()`

```java
// ContactListUiUtils.java, lines 229-278 (simplified)
private static SearchAttemptResult trySearchAndClick(
    ClawAccessibilityService service,
    String rawQuery,            // "Mom"
    Set<String> normalizedAliases,
    Set<String> digitAliases,
    long settleMs
) throws InterruptedException {
    AccessibilityNodeInfo root = service.getRootInActiveWindow();
    if (root == null) {
        return SearchAttemptResult.SEARCH_UI_MISSING;
    }

    // Find the search input field (usually at top of screen)
    AccessibilityNodeInfo searchField = UiActionMatchUtils.findBestSearchField(root);
    if (searchField == null) {
        // No search field on screen yet — look for a search icon/button to tap
        AccessibilityNodeInfo searchAction = UiActionMatchUtils.findBestSearchAction(root);
        if (searchAction != null) {
            boolean clicked = service.clickNode(searchAction);
            XLog.i(TAG, "trySearchAndClick: tapped search action");
            if (clicked) {
                Thread.sleep(Math.max(settleMs, 500L));
                // Re-fetch root after search field appears
                root = service.getRootInActiveWindow();
                searchField = UiActionMatchUtils.findBestSearchField(root);
            }
        }
    }

    if (searchField == null) {
        XLog.i(TAG, "trySearchAndClick: no search field available");
        return SearchAttemptResult.NO_MATCH;
    }

    // Type "Mom" into the search field
    if (!setText(searchField, rawQuery)) {
        XLog.i(TAG, "trySearchAndClick: failed to type query into search field");
        return SearchAttemptResult.TYPE_FAILED;
    }

    Thread.sleep(Math.max(settleMs, 600L));  // Wait for results to appear

    // Look for "Mom" in the filtered results
    root = service.getRootInActiveWindow();
    AccessibilityNodeInfo bestMatch = findBestVisibleResultNode(root, normalizedAliases, digitAliases);
    if (bestMatch != null) {
        XLog.i(TAG, "trySearchAndClick: matched result, tapping it");
        return service.clickNode(bestMatch) ? SearchAttemptResult.FOUND : SearchAttemptResult.NO_MATCH;
    }

    XLog.i(TAG, "trySearchAndClick: no result matched query");
    return SearchAttemptResult.NO_MATCH;
}

// Helper: Set text in a field
private static boolean setText(AccessibilityNodeInfo node, String text) {
    if (node == null) return false;
    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    clearText(node);  // Clear any existing text

    Bundle args = new Bundle();
    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
    boolean success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    return success;
}
```

**What happens if search finds "Mom":** The contact is tapped immediately, and the method returns `true`. Done.

### Stage 2: Scroll (If Search Failed)

If the search didn't find "Mom" (e.g., no search field exists, or contact not in results), it scrolls through the contact list:

```java
// ContactListUiUtils.java, lines 79-123
public static boolean scrollAndFindAndClick(
    ClawAccessibilityService service,
    LinkedHashSet<String> normalizedAliases,  // ["mom"]
    LinkedHashSet<String> digitAliases,       // []
    int maxScrolls,             // 12 (up to 12 scroll attempts)
    long settleMs               // 800ms
) throws InterruptedException {
    int attempts = Math.min(Math.max(maxScrolls, 1), 20);  // 12
    String lastScreen = safeScreenSnapshot(service);       // Capture initial screen state

    for (int attempt = 0; attempt <= attempts; attempt++) {
        // Look for "Mom" on current screen
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        AccessibilityNodeInfo bestMatch = findBestVisibleContactNode(root, normalizedAliases, digitAliases);
        
        if (bestMatch != null) {
            // Found it on screen!
            XLog.i(TAG, "scrollAndFindAndClick: matched node on attempt=" + attempt);
            return service.clickNode(bestMatch);  // Tap it
        }

        if (attempt == attempts || root == null) {
            // Exhausted scroll attempts
            return false;
        }

        // Swipe up to scroll through the contact list
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int centerX = rootBounds.centerX();
        int fromY = rootBounds.top + (int) (rootBounds.height() * 0.72f);  // Start at 72% down
        int toY = rootBounds.top + (int) (rootBounds.height() * 0.28f);    // End at 28% down (upward swipe)
        
        boolean swiped = service.performSwipe(centerX, fromY, centerX, toY, 320);  // Duration 320ms
        XLog.i(TAG, "scrollAndFindAndClick: swipe attempt=" + (attempt + 1));
        
        if (!swiped) {
            return false;  // Swipe failed
        }

        Thread.sleep(settleMs);  // Wait 800ms for list to settle

        // Check if the screen actually changed (reached end of list?)
        String currentScreen = safeScreenSnapshot(service);
        if (currentScreen != null && currentScreen.equals(lastScreen)) {
            XLog.i(TAG, "scrollAndFindAndClick: screen did not change, reached end of list");
            return false;
        }
        lastScreen = currentScreen;
    }

    return false;  // Never found "Mom"
}
```

### Contact Matching: How "Mom" is Recognized

The matching happens in `ContactMatchUtils`:

```java
// ContactMatchUtils.java, lines 37-43
public static LinkedHashSet<String> buildNormalizedAliases(String rawTarget) {
    LinkedHashSet<String> aliases = new LinkedHashSet<>();
    
    // Example: "Mom" → normalized to "mom"
    // Example: "Mom Smith" → split to ["Mom", "Smith"], each normalized
    
    for (String candidate : splitCandidates(rawTarget)) {
        addNormalizedAlias(aliases, candidate);
    }
    addNormalizedAlias(aliases, rawTarget);
    return aliases;
}

// Normalization rules:
public static String normalizeText(String value) {
    if (value == null) return "";
    return value
        .trim()
        .replaceAll("[^\\p{L}\\p{Nd}]+", " ")  // Keep only letters and digits
        .replaceAll("\\s+", " ")               // Collapse whitespace
        .trim()
        .toLowerCase(Locale.ROOT);             // Convert to lowercase
}

// Example:
// Input: "Mom"
// Output: "mom"
//
// Input: "mom smith"
// Output: "mom smith"
//
// Input: "Mom +1 555-1234"
// Output: "mom 1 555 1234"
```

Then when scanning the accessibility tree, the code checks if visible text **contains** the normalized alias:

```java
// ContactMatchUtils.java, lines 71-81
public static boolean matchesCandidate(
    String candidate,                   // e.g., "Mom" (from WhatsApp UI)
    Set<String> normalizedAliases,      // ["mom"]
    Set<String> digitAliases            // []
) {
    if (candidate == null || candidate.isEmpty()) return false;

    String normalizedCandidate = normalizeText(candidate);  // "mom"
    for (String alias : normalizedAliases) {
        if (!alias.isEmpty() && normalizedCandidate.contains(alias)) {
            // Match! "mom" contains "mom"
            return true;
        }
    }
    
    // Also check digit-only matches for phone numbers
    String digitCandidate = digitsOnly(candidate);
    for (String digitAlias : digitAliases) {
        if (!digitAlias.isEmpty() && digitCandidate.contains(digitAlias)) {
            return true;
        }
    }
    
    return false;
}
```

---

## Question 4: Why Does It Switch Back and Forth Between App and WhatsApp?

**Answer: To fix the screen state. There are two failure scenarios:**

### Scenario A: App Lost Focus

```java
CharSequence activePackage = root != null ? root.getPackageName() : null;
if (activePackage == null || !activePackage.toString().equals(packageName)) {
    // WhatsApp was in the background
    // Something else (e.g., home screen, system popup) came to foreground
    // FIX: Reopen WhatsApp to bring it back to focus
    XLog.i(TAG, "app not active, reopening " + packageName);
    service.openApp(packageName);
}
```

**Why this happens:**
- User tapped another app
- System dialog appeared (e.g., "Allow access?" permission)
- Notification caused focus switch
- Activity was destroyed and recreated

**Action:** Call `service.openApp("com.whatsapp")` to bring WhatsApp back to foreground.

---

### Scenario B: WhatsApp Is Active, But Screen State Is Wrong

```java
else {
    // WhatsApp is active, but we're not at the contact list screen
    // Examples:
    //   - Still inside an old chat (need to go back to chat list)
    //   - Modal dialog is open (need to dismiss it)
    //   - Still loading (need to back out and retry)
    // FIX: Press back to navigate to the contact list
    XLog.i(TAG, "screen not ready, pressing back");
    service.performGlobalAction(
        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
    );
}
```

**Why this happens:**
- WhatsApp opened, but it went to the last chat instead of chat list
- A permission dialog or popup is covering the list
- The screen is still loading/rendering
- Need to clear state before searching

**Action:** Send `GLOBAL_ACTION_BACK` keypress to navigate back through WhatsApp screens.

---

### Timeline Example: "Mom" Contact

```
Attempt 0:
  ├─ Check: Is screen ready? NO
  ├─ Check: Is WhatsApp active? YES
  ├─ Action: Press back (dismiss any dialogs, go to chat list)
  └─ Wait 1200ms

Attempt 1:
  ├─ Check: Is screen ready? YES
  ├─ Action: Search for "Mom" in the visible search field
  ├─ Type: "Mom" into search
  └─ Wait 600ms for results

Search Results:
  ├─ Look for "Mom" in filtered results
  ├─ Found: Contact node with text="Mom"
  └─ Tap it

Result:
  └─ Chat with Mom opens
```

Or if search fails:

```
Attempt 0: Press back, wait
Attempt 1: Check ready, NO
Attempt 1: WhatsApp is active, press back again, wait
Attempt 2: Check ready, YES
Attempt 2: Search field exists, search for "Mom"
Attempt 2: NOT found in search results
Attempt 2: Recover — press back, reprepare

Scroll phase:
  ├─ Swipe up (from 72% to 28% of screen height)
  ├─ Check: Is "Mom" visible? NO
  ├─ Swipe up again
  ├─ Check: Is "Mom" visible? YES
  └─ Tap it
```

---

## Summary

| Question | Answer |
|----------|--------|
| **What next after opening WhatsApp?** | Call `prepareForContactLookup()` — a diagnostic loop that ensures the screen is in the right state (showing the contact list, search field ready, etc.) |
| **Which code?** | `ContactListUiUtils.prepareForContactLookup()` and `isContactLookupReady()` checks for searchable state |
| **How get to contact?** | Two-stage: (1) Search for "Mom" if search field exists, (2) If search fails, scroll through contact list with swipe gestures |
| **Why switch back and forth?** | **Scenario A:** WhatsApp lost focus → reopen it. **Scenario B:** WhatsApp active but on wrong screen → press back to navigate to contact list |

The "back and forth" is **not aimless** — it's a recovery mechanism that fixes two specific failure modes:
- If the wrong app is in focus, open WhatsApp
- If WhatsApp is in focus but on wrong screen, press back to navigate

