#!/usr/bin/env python3
"""Execute an App Graph script task and emit the Java backend report contract.

The default driver is deterministic and hardware-free so the full contract can be
demonstrated locally. Replace DemoDeviceDriver with the HDC/UI automation adapter
without changing the task or report JSON structures.
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


@dataclass
class DemoDeviceDriver:
    evidence_dir: Path

    def reset_app(self) -> dict[str, Any]:
        time.sleep(0.03)
        return {"observation": "应用已冷启动，账号态有效，未发现遮挡弹窗", "image": "evidence/01-launch.png"}

    def execute(self, step: dict[str, Any]) -> dict[str, Any]:
        time.sleep(0.03)
        action = step.get("type", "performAction")
        return {
            "observation": f"AI 识图确认动作 {action} 执行后页面结构符合预期",
            "image": f"evidence/{int(step.get('order', 0)) + 2:02d}-{action}.png",
        }

    def collect_metrics(self) -> list[dict[str, Any]]:
        return [
            metric("fps", 58.8, 43.6, "fps", 52.0, "higher"),
            metric("jank", 2.8, 10.9, "%", 8.0, "lower"),
            metric("cpuPeak", 31.2, 57.4, "%", 50.0, "lower"),
            metric("powerAverage", 0.84, 1.48, "W", 1.3, "lower"),
            metric("memoryPeak", 512.0, 698.0, "MB", 680.0, "lower"),
        ]


def metric(name: str, baseline: float, actual: float, unit: str, threshold: float, comparison: str) -> dict[str, Any]:
    failed = actual > threshold if comparison == "lower" else actual < threshold
    return {
        "name": name,
        "baseline": baseline,
        "actual": actual,
        "unit": unit,
        "threshold": threshold,
        "comparison": comparison,
        "status": "failed" if failed else "passed",
        "sampleSummary": {"sampleCount": 120, "windowSeconds": 30},
    }


def demo_task() -> dict[str, Any]:
    return {
        "taskId": "11111111-1111-1111-1111-111111111111",
        "appName": "QQ",
        "packageName": "com.tencent.mobileqq",
        "caseType": "processScenario",
        "alertId": "ALERT-20260819-001",
        "alertUrl": "mqq://message/list?source=monitor",
        "resetPolicy": {
            "forceStopBeforeRun": True,
            "returnToHostAppFromMiniProgram": True,
            "dismissPopupAndAds": True,
            "forbidLogout": True,
        },
        "steps": [
            {"order": 1, "type": "navigate", "description": "从首页点击消息入口"},
            {"order": 2, "type": "swipe", "description": "消息列表连续向上滑动", "repeat": 4},
            {"order": 3, "type": "tap", "description": "打开首个会话并等待内容稳定"},
        ],
        "collectionPolicy": {"metrics": ["fps", "jank", "cpuPeak", "powerAverage", "memoryPeak"]},
    }


def run_task(task: dict[str, Any], evidence_dir: Path) -> dict[str, Any]:
    started_at = utc_now()
    driver = DemoDeviceDriver(evidence_dir)
    results: list[dict[str, Any]] = []

    start = time.perf_counter()
    reset = driver.reset_app()
    results.append(step_result(1, "script", "重置应用并恢复账号态", "forceStop -> launch", start, reset))

    for index, planned in enumerate(task.get("steps", []), start=2):
        start = time.perf_counter()
        observed = driver.execute(planned)
        results.append(step_result(
            index,
            "script",
            planned.get("description") or planned.get("type") or "执行图谱动作",
            json.dumps(planned, ensure_ascii=False, separators=(",", ":")),
            start,
            observed,
        ))

    metrics = driver.collect_metrics()
    failed_metrics = [item for item in metrics if item["status"] == "failed"]
    score = max(0, 100 - len(failed_metrics) * 12)
    finished_at = utc_now()
    return {
        "testCaseId": task["taskId"],
        "triggerSource": "monitorRiskUrl",
        "alertId": task.get("alertId"),
        "alertUrl": task.get("alertUrl"),
        "deviceInfo": {"deviceId": "demo-device-01", "os": "Android 15", "driver": "DemoDeviceDriver"},
        "environment": {"network": "lab-wifi", "battery": 80, "temperatureC": 25},
        "status": "completed",
        "score": score,
        "verdict": "failed" if failed_metrics else "passed",
        "diagnosis": "连续交互阶段出现 FPS 下探，并伴随 CPU、功耗和内存峰值。" if failed_metrics else "指标稳定。",
        "summary": {"plannedSteps": len(task.get("steps", [])) + 1, "completedSteps": len(results), "failedMetrics": len(failed_metrics)},
        "startedAt": started_at,
        "finishedAt": finished_at,
        "steps": results,
        "metrics": metrics,
    }


def step_result(step_no: int, stage: str, title: str, action: str, started: float, observed: dict[str, Any]) -> dict[str, Any]:
    return {
        "stepNo": step_no,
        "stage": stage,
        "title": title,
        "action": action,
        "status": "passed",
        "durationMs": max(1, round((time.perf_counter() - started) * 1000)),
        "beforeImage": None,
        "afterImage": observed.get("image"),
        "aiObservation": observed.get("observation", ""),
        "rawPayload": {},
    }


def post_report(url: str, report: dict[str, Any]) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        data=json.dumps(report, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="Run an App Graph test task and generate a report payload")
    parser.add_argument("--task", type=Path, help="JSON returned by /appGraph/api/testCases/{id}/scriptTask")
    parser.add_argument("--output", type=Path, default=Path("build/demo-test-report.json"))
    parser.add_argument("--callback-url", help="Optional /appGraph/api/testRuns/import URL")
    args = parser.parse_args()

    task = json.loads(args.task.read_text(encoding="utf-8")) if args.task else demo_task()
    if "task" in task:
        task = task["task"]
    try:
        uuid.UUID(str(task["taskId"]))
    except (KeyError, ValueError) as error:
        raise SystemExit(f"taskId must be a UUID: {error}") from error

    report = run_task(task, args.output.parent / "evidence")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"report: {args.output.resolve()}")
    print(f"verdict: {report['verdict']} score={report['score']} failedMetrics={report['summary']['failedMetrics']}")
    if args.callback_url:
        response = post_report(args.callback_url, report)
        print(json.dumps(response, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
