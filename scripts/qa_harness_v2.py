#!/usr/bin/env python3
"""PokeClaw QA Harness v2.

Replaces the old fixed-sleep bash runner with a logcat-driven host-side harness.

Key properties:
- one trial at a time
- explicit app reset between trials
- explicit debug receiver broadcast
- logcat-based completion detection
- separate PASS / FAIL / CANCELLED / BLOCKED / TIMEOUT / INFRA_ERROR
- repeated-trial success-rate reporting
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
import time
from typing import Iterable


APP_ID = "io.agents.pokeclaw"
SPLASH_ACTIVITY = "io.agents.pokeclaw/.ui.splash.SplashActivity"
DEBUG_RECEIVER = "io.agents.pokeclaw/.debug.DebugTaskReceiver"
DEBUG_ACTION = "io.agents.pokeclaw.DEBUG_TASK"
ACCESSIBILITY_COMPONENT = "io.agents.pokeclaw/io.agents.pokeclaw.service.ClawAccessibilityService"
DEFAULT_CLOUD_MODEL = "gpt-4.1"
DEFAULT_POLL_SECS = 2.0
DEFAULT_TIMEOUT_SECS = 180.0
DEFAULT_START_TIMEOUT_SECS = 20.0
DEFAULT_CONFIG_TIMEOUT_SECS = 20.0

LOG_ON_COMPLETE = re.compile(
    r"onComplete: rounds=(?P<rounds>\d+), totalTokens=(?P<tokens>\d+), model=(?P<model>.*?), answer=(?P<answer>.*)"
)
LOG_ON_ERROR = re.compile(r"onError: (?P<error>.*), totalTokens=(?P<tokens>\d+)")
LOG_ON_LOOP = re.compile(r"onLoopStart: round=(?P<round>\d+)")
LOG_TOOL_CALL = re.compile(r"onToolCall: (?P<tool>.+)")
LOG_CONFIGURED = re.compile(r"LLM configured: provider=(?P<provider>[^,]+), model=(?P<model>.*)")


SUITES: dict[str, list[str]] = {
    "cloud-headline": [
        "Copy the latest email subject and Google it",
        "Write an email saying I will be late today",
    ],
    "cloud-full": [
        "Open Reddit and search for pokeclaw",
        "Search YouTube for funny cat fails",
        "Install Telegram from Play Store",
        "Check whats trending on Twitter and tell me",
        "Check my latest WhatsApp chat and summarize it",
        "Copy the latest email subject and Google it",
        "Write an email saying I will be late today",
        "Check my notifications — anything important?",
        "Read my clipboard and explain what it says",
        "Check my storage and apps — what can I delete?",
        "Read my notifications and summarize",
        "Check my battery and tell me if I need to charge",
        "Send hi to Girlfriend on WhatsApp",
        "What apps do I have?",
        "How hot is my phone?",
        "Is bluetooth on?",
        "How much battery left?",
        "Call Mom",
        "How much storage do I have?",
        "What Android version am I running?",
    ],
    "cloud-quick": [
        "Open Reddit and search for pokeclaw",
        "Search YouTube for funny cat fails",
        "Install Telegram from Play Store",
        "Check whats trending on Twitter and tell me",
        "Check my latest WhatsApp chat and summarize it",
        "Read my clipboard and explain what it says",
        "Check my notifications — anything important?",
        "Check my storage and apps — what can I delete?",
    ],
    "local-core": [
        "Read my clipboard and explain what it says",
        "Check my notifications and summarize",
        "Check my battery and tell me if I need to charge",
        "Check my storage and apps — what can I delete?",
    ],
    "local-full": [
        "Check my notifications — anything important?",
        "Read my clipboard and explain what it says",
        "Check my storage and apps — what can I delete?",
        "Read my notifications and summarize",
        "Check my battery and tell me if I need to charge",
        "Send hi to Mom on WhatsApp",
        "What apps do I have?",
        "How hot is my phone?",
        "Is bluetooth on?",
        "How much battery left?",
        "Call Mom",
        "How much storage do I have?",
        "What Android version am I running?",
    ],
}


@dataclasses.dataclass
class TrialResult:
    trial: int
    task: str
    status: str
    elapsed_secs: float
    rounds: int | None = None
    tokens: int | None = None
    model: str | None = None
    detail: str = ""
    tool_calls: list[str] = dataclasses.field(default_factory=list)
    error_kind: str | None = None
    log_path: str | None = None
    assertions: list[str] = dataclasses.field(default_factory=list)


def run(cmd: list[str], *, check: bool = True, capture: bool = True, timeout: float | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        check=check,
        text=True,
        capture_output=capture,
        timeout=timeout,
    )


def adb(*args: str, check: bool = True, capture: bool = True, timeout: float | None = None) -> subprocess.CompletedProcess[str]:
    return run(["adb", *args], check=check, capture=capture, timeout=timeout)


def adb_shell(*args: str, check: bool = True, capture: bool = True, timeout: float | None = None) -> subprocess.CompletedProcess[str]:
    return adb("shell", *args, check=check, capture=capture, timeout=timeout)


def adb_shell_command(command: str, *, check: bool = True, capture: bool = True, timeout: float | None = None) -> subprocess.CompletedProcess[str]:
    return adb("shell", command, check=check, capture=capture, timeout=timeout)


def ensure_device() -> None:
    out = adb("devices", "-l").stdout.strip().splitlines()
    devices = [line for line in out[1:] if line.strip()]
    if not devices:
        raise SystemExit("No adb device connected.")


def read_env_file(repo_root: pathlib.Path) -> dict[str, str]:
    candidates = [
        repo_root / ".env",
        pathlib.Path("/home/nicole/MyGithub/PokeClaw/.env"),
    ]
    values: dict[str, str] = {}
    for env_path in candidates:
        if not env_path.exists():
            continue
        for raw in env_path.read_text().splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
        if values:
            return values
    return values


def resolve_openai_api_key(repo_root: pathlib.Path) -> str:
    if os.environ.get("OPENAI_API_KEY"):
        return os.environ["OPENAI_API_KEY"]
    env_values = read_env_file(repo_root)
    if env_values.get("OPENAI_API_KEY"):
        return env_values["OPENAI_API_KEY"]
    raise SystemExit("OPENAI_API_KEY not found in environment or repo .env")


def resolve_local_model() -> tuple[str, str]:
    explicit_path = os.environ.get("LOCAL_MODEL_PATH", "").strip()
    explicit_name = os.environ.get("LOCAL_MODEL_NAME", "").strip()
    if explicit_path and explicit_name:
        return explicit_path, explicit_name

    models_dir = "/storage/emulated/0/Android/data/io.agents.pokeclaw/files/models"
    candidates = [
        (f"{models_dir}/gemma-4-E4B-it.litertlm", "gemma4-e4b"),
        (f"{models_dir}/gemma-4-E2B-it.litertlm", "gemma4-e2b"),
    ]
    for path, name in candidates:
        result = adb_shell("test", "-f", path, check=False, capture=True)
        if result.returncode == 0:
            return path, name
    raise SystemExit("Unable to resolve local model. Set LOCAL_MODEL_PATH and LOCAL_MODEL_NAME.")


def logcat_clear() -> None:
    adb("logcat", "-c", check=False, capture=True)


def dump_logcat() -> str:
    return adb("logcat", "-d", "-v", "threadtime", check=False, capture=True).stdout


def write_text(path: pathlib.Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)


def force_stop_app() -> None:
    adb_shell("am", "force-stop", APP_ID, check=False, capture=True)


def start_app() -> None:
    adb_shell("am", "start", "-n", SPLASH_ACTIVITY, check=False, capture=True)


def get_pid() -> str:
    return adb_shell("pidof", APP_ID, check=False, capture=True).stdout.strip()


def wait_for_pid(timeout_secs: float) -> str:
    deadline = time.monotonic() + timeout_secs
    while time.monotonic() < deadline:
        pid = get_pid()
        if pid:
            return pid
        time.sleep(0.5)
    raise TimeoutError(f"App process did not start within {timeout_secs:.0f}s")


def enable_accessibility_setting() -> None:
    adb_shell("settings", "put", "secure", "enabled_accessibility_services", ACCESSIBILITY_COMPONENT, check=False, capture=True)
    adb_shell("settings", "put", "secure", "accessibility_enabled", "1", check=False, capture=True)


def accessibility_state() -> str:
    dumpsys = adb_shell("dumpsys", "accessibility", check=False, capture=True).stdout
    enabled = ACCESSIBILITY_COMPONENT in dumpsys and "Enabled services:{" in dumpsys
    bound = "Bound services:{Service[" in dumpsys and "PokeClaw" in dumpsys
    binding = ACCESSIBILITY_COMPONENT in dumpsys and "Binding services:{" in dumpsys
    if enabled and bound:
        return "ready"
    if enabled or binding:
        return "connecting"
    return "disabled"


def wait_for_accessibility_ready(timeout_secs: float) -> None:
    deadline = time.monotonic() + timeout_secs
    while time.monotonic() < deadline:
        state = accessibility_state()
        if state == "ready":
            return
        if state == "disabled":
            enable_accessibility_setting()
        time.sleep(0.5)
    raise RuntimeError(f"Accessibility service not ready after {timeout_secs:.0f}s (state={accessibility_state()})")


def explicit_broadcast(*extras: str) -> None:
    parts = [
        "am", "broadcast",
        "-n", DEBUG_RECEIVER,
        "-a", DEBUG_ACTION,
        *extras,
    ]
    command = " ".join(shlex.quote(part) for part in parts)
    adb_shell_command(command, check=False, capture=True)


def configure_backend(mode: str, repo_root: pathlib.Path, cloud_model: str) -> None:
    logcat_clear()
    if mode == "cloud":
        api_key = resolve_openai_api_key(repo_root)
        explicit_broadcast("--es", "task", "config:", "--es", "api_key", api_key, "--es", "model_name", cloud_model)
    else:
        model_path, model_name = resolve_local_model()
        explicit_broadcast(
            "--es", "task", "config:",
            "--es", "provider", "LOCAL",
            "--es", "base_url", model_path,
            "--es", "model_name", model_name,
        )

    deadline = time.monotonic() + DEFAULT_CONFIG_TIMEOUT_SECS
    while time.monotonic() < deadline:
        logs = dump_logcat()
        if LOG_CONFIGURED.search(logs):
            return
        if not get_pid():
            raise RuntimeError("App process died during backend configuration")
        time.sleep(DEFAULT_POLL_SECS)
    raise TimeoutError(f"{mode} backend did not confirm configuration within {DEFAULT_CONFIG_TIMEOUT_SECS:.0f}s")


def classify_blocked(text: str) -> bool:
    lowered = text.lower()
    fragments = [
        "not installed",
        "cannot resolve",
        "can't resolve",
        "could not resolve",
        "notification access",
        "accessibility service is not running",
        "accessibility service is not enabled",
        "no local model",
        "model file",
        "__system_dialog_blocked__",
        "system dialog may be blocking the screen",
    ]
    if "contact" in lowered and ("not found" in lowered or "couldn't find" in lowered or "failed to find" in lowered):
        return True
    return any(fragment in lowered for fragment in fragments)


def classify_cancelled(text: str) -> bool:
    lowered = text.lower()
    return any(fragment in lowered for fragment in [
        "task cancelled",
        "task stopped:",
        "budget limit reached",
        "cancelled by user",
    ])


def parse_result(logs: str) -> tuple[str | None, dict[str, object]]:
    rounds_seen = [int(match.group("round")) for match in LOG_ON_LOOP.finditer(logs)]
    tool_calls = [match.group("tool").strip() for match in LOG_TOOL_CALL.finditer(logs)]

    complete_match = None
    for match in LOG_ON_COMPLETE.finditer(logs):
        complete_match = match
    if complete_match is not None:
        answer = complete_match.group("answer").strip()
        status = "PASS"
        if classify_cancelled(answer):
            status = "CANCELLED"
        elif classify_blocked(answer):
            status = "BLOCKED"
        details = {
            "rounds": int(complete_match.group("rounds")),
            "tokens": int(complete_match.group("tokens")),
            "model": complete_match.group("model").strip(),
            "detail": answer,
            "tool_calls": tool_calls,
        }
        return status, details

    error_match = None
    for match in LOG_ON_ERROR.finditer(logs):
        error_match = match
    if error_match is not None:
        error = error_match.group("error").strip()
        status = "FAIL"
        if classify_cancelled(error):
            status = "CANCELLED"
        elif classify_blocked(error):
            status = "BLOCKED"
        details = {
            "rounds": max(rounds_seen) if rounds_seen else None,
            "tokens": int(error_match.group("tokens")),
            "detail": error,
            "tool_calls": tool_calls,
            "error_kind": "agent_error",
        }
        return status, details

    if "onSystemDialogBlocked" in logs:
        return "BLOCKED", {
            "rounds": max(rounds_seen) if rounds_seen else None,
            "detail": "System dialog blocked foreground automation",
            "tool_calls": tool_calls,
            "error_kind": "system_dialog",
        }

    if "Agent is already running a task" in logs:
        return "INFRA_ERROR", {
            "rounds": max(rounds_seen) if rounds_seen else None,
            "detail": "Agent already running previous task",
            "tool_calls": tool_calls,
            "error_kind": "stale_task",
        }

    if "Interrupted during action execution" in logs:
        return "FAIL", {
            "rounds": max(rounds_seen) if rounds_seen else None,
            "detail": "Task interrupted during action execution",
            "tool_calls": tool_calls,
            "error_kind": "interrupted",
        }

    return None, {
        "rounds": max(rounds_seen) if rounds_seen else None,
        "tool_calls": tool_calls,
    }


def apply_assertions(
    result: TrialResult,
    *,
    expect_tools: list[str],
    require_tool_call: bool,
    forbid_texts: list[str],
) -> TrialResult:
    if result.status != "PASS":
        return result

    failures: list[str] = []
    normalized_tool_calls = [call.lower() for call in result.tool_calls]
    if require_tool_call and not result.tool_calls:
        failures.append("expected at least one tool call")
    for tool in expect_tools:
        tool_lower = tool.lower()
        if not any(tool_lower in call for call in normalized_tool_calls):
            failures.append(f"missing expected tool '{tool}'")
    detail_lower = result.detail.lower()
    for text in forbid_texts:
        if text.lower() in detail_lower:
            failures.append(f"forbidden reply fragment '{text}'")

    if not failures:
        return result

    return dataclasses.replace(
        result,
        status="FAIL",
        detail="; ".join(failures),
        assertions=failures,
        error_kind=result.error_kind or "assertion_failed",
    )


def run_trial(
    *,
    task: str,
    trial_num: int,
    mode: str,
    repo_root: pathlib.Path,
    out_dir: pathlib.Path,
    timeout_secs: float,
    cloud_model: str,
) -> TrialResult:
    force_stop_app()
    wait_for_accessibility_ready(DEFAULT_START_TIMEOUT_SECS)
    configure_backend(mode, repo_root, cloud_model)
    wait_for_pid(DEFAULT_START_TIMEOUT_SECS)
    logcat_clear()
    explicit_broadcast("--es", "task", task)

    started_at = time.monotonic()
    while True:
        elapsed = time.monotonic() - started_at
        logs = dump_logcat()
        status, details = parse_result(logs)
        if status is not None:
            log_path = out_dir / f"trial-{trial_num:02d}.logcat.txt"
            write_text(log_path, logs)
            return TrialResult(
                trial=trial_num,
                task=task,
                status=status,
                elapsed_secs=elapsed,
                rounds=details.get("rounds"),
                tokens=details.get("tokens"),
                model=details.get("model"),
                detail=str(details.get("detail", "")).strip(),
                tool_calls=list(details.get("tool_calls", [])),
                error_kind=details.get("error_kind"),
                log_path=str(log_path),
            )

        if not get_pid():
            log_path = out_dir / f"trial-{trial_num:02d}.logcat.txt"
            write_text(log_path, logs)
            return TrialResult(
                trial=trial_num,
                task=task,
                status="FAIL",
                elapsed_secs=elapsed,
                detail="App process missing during task execution",
                error_kind="process_missing",
                log_path=str(log_path),
            )

        if elapsed >= timeout_secs:
            log_path = out_dir / f"trial-{trial_num:02d}.logcat.txt"
            write_text(log_path, logs)
            return TrialResult(
                trial=trial_num,
                task=task,
                status="TIMEOUT",
                elapsed_secs=elapsed,
                rounds=details.get("rounds"),
                detail=f"Timed out after {timeout_secs:.0f}s",
                tool_calls=list(details.get("tool_calls", [])),
                error_kind="timeout",
                log_path=str(log_path),
            )

        time.sleep(DEFAULT_POLL_SECS)


def print_trial(result: TrialResult) -> None:
    rounds = f", rounds={result.rounds}" if result.rounds is not None else ""
    tokens = f", tokens={result.tokens}" if result.tokens is not None else ""
    model = f", model={result.model}" if result.model else ""
    print(
        f"[trial {result.trial:02d}] {result.status:<11} {result.elapsed_secs:6.1f}s{rounds}{tokens}{model}"
        f" :: {result.detail}"
    )


def summarize(results: list[TrialResult]) -> dict[str, int | float]:
    counts: dict[str, int] = {}
    for result in results:
        counts[result.status] = counts.get(result.status, 0) + 1
    total = len(results)
    passed = counts.get("PASS", 0)
    pass_rate = (passed / total * 100.0) if total else 0.0
    eligible = total - counts.get("BLOCKED", 0)
    effective_pass_rate = (passed / eligible * 100.0) if eligible else 0.0
    return {
        "total": total,
        "pass_rate": pass_rate,
        "effective_pass_rate": effective_pass_rate,
        **counts,
    }


def write_summary_json(path: pathlib.Path, *, args: argparse.Namespace, results: list[TrialResult]) -> None:
    payload = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "mode": args.mode,
        "suite": args.suite,
        "task": args.task,
        "trials": args.trials,
        "timeout_secs": args.timeout,
        "cloud_model": args.cloud_model,
        "summary": summarize(results),
        "results": [dataclasses.asdict(result) for result in results],
    }
    write_text(path, json.dumps(payload, indent=2))


def parse_args(argv: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="PokeClaw QA Harness v2")
    parser.add_argument("--mode", choices=["cloud", "local"], required=True)
    parser.add_argument("--suite", choices=sorted(SUITES.keys()))
    parser.add_argument("--task", help="Single task to run")
    parser.add_argument("--trials", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECS)
    parser.add_argument("--cloud-model", default=DEFAULT_CLOUD_MODEL)
    parser.add_argument("--results-dir", default=None)
    parser.add_argument("--expect-tool", action="append", default=[])
    parser.add_argument("--forbid-text", action="append", default=[])
    parser.add_argument("--require-tool-call", action="store_true")
    args = parser.parse_args(list(argv))
    if not args.task and not args.suite:
        parser.error("Pass either --task or --suite")
    return args


def main(argv: Iterable[str]) -> int:
    args = parse_args(argv)
    ensure_device()

    repo_root = pathlib.Path(__file__).resolve().parents[1]
    tasks = [args.task] if args.task else SUITES[args.suite]
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    results_dir = pathlib.Path(args.results_dir or f"/tmp/pokeclaw-qa-v2-{args.mode}-{timestamp}")
    results_dir.mkdir(parents=True, exist_ok=True)

    print("=== PokeClaw QA Harness v2 ===")
    print(f"mode={args.mode} suite={args.suite or 'single'} trials={args.trials} timeout={args.timeout:.0f}s")
    print(f"results_dir={results_dir}")
    print()

    all_results: list[TrialResult] = []
    for task in tasks:
        print(f"--- task: {task}")
        task_slug = re.sub(r"[^a-z0-9]+", "-", task.lower()).strip("-")[:80]
        task_dir = results_dir / task_slug
        task_dir.mkdir(parents=True, exist_ok=True)
        task_results: list[TrialResult] = []
        for trial_num in range(1, args.trials + 1):
            try:
                result = run_trial(
                    task=task,
                    trial_num=trial_num,
                    mode=args.mode,
                    repo_root=repo_root,
                    out_dir=task_dir,
                    timeout_secs=args.timeout,
                    cloud_model=args.cloud_model,
                )
            except Exception as exc:
                detail = str(exc).strip() or exc.__class__.__name__
                status = "BLOCKED" if "Accessibility service not ready" in detail else "INFRA_ERROR"
                result = TrialResult(
                    trial=trial_num,
                    task=task,
                    status=status,
                    elapsed_secs=0.0,
                    detail=detail,
                    error_kind=exc.__class__.__name__,
                )
            result = apply_assertions(
                result,
                expect_tools=args.expect_tool,
                require_tool_call=args.require_tool_call,
                forbid_texts=args.forbid_text,
            )
            print_trial(result)
            task_results.append(result)
            all_results.append(result)

        summary = summarize(task_results)
        print(
            "summary:"
            f" pass={summary.get('PASS', 0)}"
            f" fail={summary.get('FAIL', 0)}"
            f" cancelled={summary.get('CANCELLED', 0)}"
            f" blocked={summary.get('BLOCKED', 0)}"
            f" timeout={summary.get('TIMEOUT', 0)}"
            f" infra={summary.get('INFRA_ERROR', 0)}"
            f" pass_rate={summary['pass_rate']:.1f}%"
            f" effective_pass_rate={summary['effective_pass_rate']:.1f}%"
        )
        print()

    write_summary_json(results_dir / "summary.json", args=args, results=all_results)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
