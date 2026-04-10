#!/bin/bash
set -euo pipefail

MODE="${1:-cloud}"
PASS=0
FAIL=0
BLOCKED=0
TIMEOUT=0
TOTAL=0

RESULTS_FILE="${RESULTS_FILE:-/tmp/pokeclaw-e2e-${MODE}-quick-tasks-$(date +%Y%m%d-%H%M%S).log}"
LOCAL_MODEL_PATH="${LOCAL_MODEL_PATH:-/storage/emulated/0/Android/data/io.agents.pokeclaw/files/models/gemma-4-E2B-it.litertlm}"
LOCAL_MODEL_NAME="${LOCAL_MODEL_NAME:-gemma4-e2b}"
CLOUD_MODEL_NAME="${CLOUD_MODEL_NAME:-gpt-4.1}"

print_usage() {
    echo "Usage: $0 [cloud|local]"
    echo "Env:"
    echo "  RESULTS_FILE=/tmp/custom.log"
    echo "  LOCAL_MODEL_PATH=/storage/.../model.litertlm"
    echo "  LOCAL_MODEL_NAME=gemma4-e2b"
    echo "  CLOUD_MODEL_NAME=gpt-4.1"
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
            adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw --es task 'config:' --es api_key '$OPENAI_API_KEY' --es model_name '$CLOUD_MODEL_NAME'" >/dev/null
            ;;
        local)
            adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw --es task 'config:' --es provider 'LOCAL' --es base_url '$LOCAL_MODEL_PATH' --es model_name '$LOCAL_MODEL_NAME'" >/dev/null
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

classify_blocked() {
    local message_lower
    message_lower="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
    printf '%s' "$message_lower" | grep -qE "not installed|cannot resolve|can't resolve|could not resolve|contact .* not found|couldn't find .*contact|failed to find .*contact|failed to send .*contact|notification access|accessibility service is not running|no local model|model file.*missing"
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

    adb logcat -c >/dev/null 2>&1 || true
    sleep 1
    adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw --es task '$task'" >/dev/null 2>&1

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
        local comp err already ans tools err_detail
        comp="$(printf '%s\n' "$log" | grep 'onComplete:.*answer=' | tail -1 || true)"
        err="$(printf '%s\n' "$log" | grep 'onError' | tail -1 || true)"
        already="$(printf '%s\n' "$log" | grep 'already running' | tail -1 || true)"

        if [ -n "$already" ]; then
            echo "    [${i}s] BLOCKED — agent still running previous task, retrying..." | tee -a "$RESULTS_FILE"
            sleep 15
            i=$((i + 15))
            adb logcat -c >/dev/null 2>&1 || true
            adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw --es task '$task'" >/dev/null 2>&1
            continue
        fi

        if [ -n "$comp" ]; then
            ans="$(printf '%s\n' "$comp" | sed 's/.*answer=//')"
            tools="$(printf '%s\n' "$log" | grep 'onToolCall:' | sed 's/.*onToolCall: //' | tr '\n' ' ')"
            if printf '%s' "$ans" | grep -qi 'budget limit reached'; then
                record_result "FAIL" "$i" "$ans" "$tools"
                FAIL=$((FAIL + 1))
            elif classify_blocked "$ans"; then
                record_result "BLOCKED" "$i" "$ans" "$tools"
                BLOCKED=$((BLOCKED + 1))
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
    TIMEOUT=$((TIMEOUT + 1))
}

echo "==========================================" | tee "$RESULTS_FILE"
echo "  POKECLAW E2E — QUICK TASKS (${MODE^^})" | tee -a "$RESULTS_FILE"
echo "  $(date)" | tee -a "$RESULTS_FILE"
echo "  results: $RESULTS_FILE" | tee -a "$RESULTS_FILE"
echo "==========================================" | tee -a "$RESULTS_FILE"

adb shell am start -n io.agents.pokeclaw/.ui.splash.SplashActivity >/dev/null 2>&1
sleep 3
configure_mode

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
