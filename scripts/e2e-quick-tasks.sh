#!/bin/bash
set -euo pipefail

MODE="${1:-cloud}"
PASS=0
FAIL=0
BLOCKED=0
TIMEOUT=0
TOTAL=0

RESULTS_FILE="${RESULTS_FILE:-/tmp/pokeclaw-e2e-${MODE}-quick-tasks-$(date +%Y%m%d-%H%M%S).log}"
LOCAL_MODEL_PATH="${LOCAL_MODEL_PATH:-}"
LOCAL_MODEL_NAME="${LOCAL_MODEL_NAME:-}"
CLOUD_MODEL_NAME="${CLOUD_MODEL_NAME:-gpt-4.1}"

adb_retry() {
    local attempt=1
    local max_attempts="${ADB_MAX_ATTEMPTS:-3}"
    while true; do
        if "$@"; then
            return 0
        fi
        if [ "$attempt" -ge "$max_attempts" ]; then
            return 1
        fi
        sleep 2
        attempt=$((attempt + 1))
    done
}

print_usage() {
    echo "Usage: $0 [cloud|local]"
    echo "Env:"
    echo "  RESULTS_FILE=/tmp/custom.log"
    echo "  LOCAL_MODEL_PATH=/storage/.../model.litertlm"
    echo "  LOCAL_MODEL_NAME=gemma4-e4b"
    echo "  CLOUD_MODEL_NAME=gpt-4.1"
}

resolve_local_model() {
    if [ -n "$LOCAL_MODEL_PATH" ] && [ -n "$LOCAL_MODEL_NAME" ]; then
        return 0
    fi

    local models_dir="/storage/emulated/0/Android/data/io.agents.pokeclaw/files/models"
    local e4b_path="${models_dir}/gemma-4-E4B-it.litertlm"
    local e2b_path="${models_dir}/gemma-4-E2B-it.litertlm"

    if [ -z "$LOCAL_MODEL_PATH" ]; then
        if adb shell test -f "$e4b_path" >/dev/null 2>&1; then
            LOCAL_MODEL_PATH="$e4b_path"
        elif adb shell test -f "$e2b_path" >/dev/null 2>&1; then
            LOCAL_MODEL_PATH="$e2b_path"
        fi
    fi

    if [ -z "$LOCAL_MODEL_NAME" ] && [ -n "$LOCAL_MODEL_PATH" ]; then
        case "$LOCAL_MODEL_PATH" in
            *gemma-4-E4B-it.litertlm) LOCAL_MODEL_NAME="gemma4-e4b" ;;
            *gemma-4-E2B-it.litertlm) LOCAL_MODEL_NAME="gemma4-e2b" ;;
        esac
    fi

    if [ -z "$LOCAL_MODEL_PATH" ] || [ -z "$LOCAL_MODEL_NAME" ]; then
        echo "Unable to resolve local model. Set LOCAL_MODEL_PATH and LOCAL_MODEL_NAME." >&2
        exit 1
    fi
}

configure_mode() {
    case "$MODE" in
        cloud)
            if [ -z "${OPENAI_API_KEY:-}" ] && [ -f /home/nicole/MyGithub/PokeClaw/.env ]; then
                # shellcheck disable=SC1091
                source /home/nicole/MyGithub/PokeClaw/.env
            fi
            if [ -z "${OPENAI_API_KEY:-}" ]; then
                echo "OPENAI_API_KEY not set and .env not available"
                exit 1
            fi
            adb_retry adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw --es task 'config:' --es api_key '$OPENAI_API_KEY' --es model_name '$CLOUD_MODEL_NAME'" >/dev/null
            ;;
        local)
            resolve_local_model
            adb_retry adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw --es task 'config:' --es provider 'LOCAL' --es base_url '$LOCAL_MODEL_PATH' --es model_name '$LOCAL_MODEL_NAME'" >/dev/null
            ;;
        -h|--help|help)
            print_usage
            exit 0
            ;;
        *)
            echo "Unknown mode: $MODE"
            print_usage
            exit 1
            ;;
    esac
    sleep 2
}

cancel_running_task() {
    adb_retry adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw --es task 'cancel:'" >/dev/null 2>&1 || true
    sleep "${TASK_CANCEL_SETTLE_SECONDS:-2}"
}

dismiss_blocking_system_dialogs() {
    local window_state
    window_state="$(adb shell dumpsys window 2>/dev/null || true)"
    if printf '%s' "$window_state" | grep -q "Application Not Responding: io.agents.pokeclaw"; then
        # Android ANR dialogs use package=android and block Accessibility focus.
        # Coordinates default to the Pixel 8 Pro QA device; override if needed.
        adb shell input tap "${ANR_CLOSE_X:-300}" "${ANR_CLOSE_Y:-1158}" >/dev/null 2>&1 || true
        sleep 2
    fi
}

wait_for_accessibility() {
    local attempt
    for attempt in $(seq 1 "${ACCESSIBILITY_WAIT_ATTEMPTS:-10}"); do
        if adb shell dumpsys accessibility 2>/dev/null | grep -q "Bound services:{Service\\[label=PokeClaw"; then
            return 0
        fi
        sleep 1
    done
    echo "    WARN — PokeClaw Accessibility service did not report bound before task start" | tee -a "$RESULTS_FILE"
    return 1
}

reset_foreground() {
    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    adb shell cmd statusbar collapse >/dev/null 2>&1 || true
    dismiss_blocking_system_dialogs
    adb_retry adb shell am start -n io.agents.pokeclaw/.ui.splash.SplashActivity >/dev/null 2>&1 || true
    wait_for_accessibility >/dev/null 2>&1 || true
    sleep "${TASK_RESET_SETTLE_SECONDS:-2}"
}

classify_blocked() {
    local message_lower
    message_lower="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
    printf '%s' "$message_lower" | grep -qE "not installed|cannot resolve|can't resolve|could not resolve|contact .* not found|couldn't find .*contact|failed to find .*contact|failed to send .*contact|notification access|accessibility service is not running|no local model|model file.*missing|__system_dialog_blocked__|system dialog may be blocking the screen"
}

record_result() {
    local label="$1"
    local seconds="$2"
    local detail="$3"
    local tools="${4:-}"
    echo "    [${seconds}s] ${label} — $(printf '%s' "$detail" | head -c 160)" | tee -a "$RESULTS_FILE"
    if [ -n "$tools" ]; then
        echo "    Tools: $(printf '%s' "$tools" | head -c 180)" | tee -a "$RESULTS_FILE"
    fi
}

run() {
    local name="$1"
    local task="$2"
    local max="${3:-45}"
    TOTAL=$((TOTAL + 1))
    echo "" | tee -a "$RESULTS_FILE"
    echo "[$TOTAL] $name" | tee -a "$RESULTS_FILE"
    echo "    Task: $task" | tee -a "$RESULTS_FILE"

    cancel_running_task
    reset_foreground
    adb_retry adb logcat -c >/dev/null 2>&1 || true
    sleep 1
    adb_retry adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw --es task '$task'" >/dev/null 2>&1

    local i=0
    while [ "$i" -lt "$max" ]; do
        sleep 5
        i=$((i + 5))

        local pid
        pid="$(adb shell pidof io.agents.pokeclaw 2>/dev/null | tr -d '\r')"
        if [ -z "$pid" ]; then
            record_result "FAIL" "$i" "app process missing"
            FAIL=$((FAIL + 1))
            return
        fi

        local log
        log="$(adb logcat -d 2>/dev/null | grep "$pid" || true)"
        local comp err blocked already ans tools err_detail
        comp="$(printf '%s\n' "$log" | grep 'onComplete:.*answer=' | tail -1 || true)"
        err="$(printf '%s\n' "$log" | grep 'onError' | tail -1 || true)"
        blocked="$(printf '%s\n' "$log" | grep 'onSystemDialogBlocked' | tail -1 || true)"
        already="$(printf '%s\n' "$log" | grep 'already running' | tail -1 || true)"

        if [ -n "$already" ]; then
            echo "    [${i}s] BLOCKED — agent still running previous task, cancelling and retrying..." | tee -a "$RESULTS_FILE"
            cancel_running_task
            reset_foreground
            adb_retry adb logcat -c >/dev/null 2>&1 || true
            adb_retry adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw --es task '$task'" >/dev/null 2>&1
            continue
        fi

        if [ -n "$blocked" ]; then
            record_result "BLOCKED" "$i" "system dialog blocked foreground automation"
            BLOCKED=$((BLOCKED + 1))
            return
        fi

        if [ -n "$comp" ]; then
            ans="$(printf '%s\n' "$comp" | sed 's/.*answer=//')"
            tools="$(printf '%s\n' "$log" | grep 'onToolCall:' | sed 's/.*onToolCall: //' | tr '\n' ' ' || true)"
            if classify_blocked "$ans"; then
                record_result "BLOCKED" "$i" "$ans" "$tools"
                BLOCKED=$((BLOCKED + 1))
            elif printf '%s' "$ans" | grep -qiE '(^|[[:space:]])failed:|budget limit reached|task cancelled|task stopped:'; then
                record_result "FAIL" "$i" "$ans" "$tools"
                FAIL=$((FAIL + 1))
            else
                record_result "PASS" "$i" "$ans" "$tools"
                PASS=$((PASS + 1))
            fi
            return
        fi

        if [ -n "$err" ]; then
            err_detail="$(printf '%s\n' "$err" | sed 's/.*onError: //')"
            if classify_blocked "$err_detail"; then
                record_result "BLOCKED" "$i" "$err_detail"
                BLOCKED=$((BLOCKED + 1))
            else
                record_result "FAIL" "$i" "$err_detail"
                FAIL=$((FAIL + 1))
            fi
            return
        fi
    done

    echo "    TIMEOUT (${max}s)" | tee -a "$RESULTS_FILE"
    cancel_running_task
    reset_foreground
    TIMEOUT=$((TIMEOUT + 1))
}

echo "==========================================" | tee "$RESULTS_FILE"
echo "  POKECLAW E2E — QUICK TASKS (${MODE^^})" | tee -a "$RESULTS_FILE"
echo "  $(date)" | tee -a "$RESULTS_FILE"
echo "  results: $RESULTS_FILE" | tee -a "$RESULTS_FILE"
echo "==========================================" | tee -a "$RESULTS_FILE"

adb_retry adb shell am start -n io.agents.pokeclaw/.ui.splash.SplashActivity >/dev/null 2>&1 || true
sleep 3
configure_mode
cancel_running_task
reset_foreground

if [ "$MODE" = "cloud" ]; then
    run "Reddit pokeclaw"       "Open Reddit and search for pokeclaw" 60
    run "YouTube cat fails"     "Search YouTube for funny cat fails" 60
    run "Install Telegram"      "Install Telegram from Play Store" 90
    run "Twitter trending"      "Check whats trending on Twitter and tell me" 60
    run "WhatsApp chat summary" "Check my latest WhatsApp chat and summarize it" 60
    run "Copy email + Google"   "Copy the latest email subject and Google it" 60
    run "Write email"           "Write an email saying I will be late today" 60

    run "Notifications triage"  "Check my notifications — anything important?" 30
    run "Clipboard explain"     "Read my clipboard and explain what it says" 30
    run "Storage analysis"      "Check my storage and apps — what can I delete?" 30
    run "Notification summary"  "Read my notifications and summarize" 30
    run "Battery advice"        "Check my battery and tell me if I need to charge" 30

    run "WhatsApp send"         "Send hi to Girlfriend on WhatsApp" 45
    run "What apps"             "What apps do I have?" 30
    run "Phone temp"            "How hot is my phone?" 20
    run "Bluetooth"             "Is bluetooth on?" 20
    run "Battery"               "How much battery left?" 20
    run "Call Mom"              "Call Mom" 30
    run "Storage"               "How much storage do I have?" 20
    run "Android version"       "What Android version am I running?" 20
else
    run "Notifications triage"  "Check my notifications — anything important?" 180
    run "Clipboard explain"     "Read my clipboard and explain what it says" 180
    run "Storage analysis"      "Check my storage and apps — what can I delete?" 180
    run "Notification summary"  "Read my notifications and summarize" 180
    run "Battery advice"        "Check my battery and tell me if I need to charge" 180

    run "WhatsApp send"         "Send hi to Mom on WhatsApp" 180
    run "What apps"             "What apps do I have?" 180
    run "Phone temp"            "How hot is my phone?" 180
    run "Bluetooth"             "Is bluetooth on?" 180
    run "Battery"               "How much battery left?" 180
    run "Call Mom"              "Call Mom" 180
    run "Storage"               "How much storage do I have?" 180
    run "Android version"       "What Android version am I running?" 180
fi

echo "" | tee -a "$RESULTS_FILE"
echo "==========================================" | tee -a "$RESULTS_FILE"
echo "  RESULTS: $PASS PASS / $FAIL FAIL / $BLOCKED BLOCKED / $TIMEOUT TIMEOUT / $TOTAL TOTAL" | tee -a "$RESULTS_FILE"
echo "==========================================" | tee -a "$RESULTS_FILE"
