"""단계가 있는 테스트(stress·breakpoint·spike)의 단계 계획과 실행 중 감시.

  python3 perf/lib/stagewatch.py plan <test>  < k6 inspect 출력 JSON  > plan.json
  python3 perf/lib/stagewatch.py watch <결과 폴더> <testid> <k6 pid> [--abort]

plan: k6 프로필의 stages를 단계로 묶어요(같은 목표가 이어지면 한 단계: 올리기 + 유지). 단계마다 시작·끝(초)과 판정 시작
(스트레스는 올리는 구간 + 30초, 한계 찾기는 처음 30초를 빼요. spike는 구간 전체를 기록만).
watch: k6가 도는 동안 5초마다 Prometheus에서 최근 30초 p95(종류별)·에러율을 보고, --abort면 판정 구간에서 p95 기준을 넘거나
에러율이 5% 이상일 때 k6에 SIGINT(정상 종료: 결과 요약은 남아요)를 보내요. k6·노트북 CPU도 5초마다 남겨요(timeline.json).
k6 응답 시간은 Prometheus native histogram(k6_http_req_duration_seconds)이라 구간 p95가 정확해요.
"""

import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

PROM = "http://localhost:9090"
P95_LIMIT = {"lookup": 0.2, "search": 0.5, "judge": 1.0}  # config.js LATENCY의 p95 (초)
ERROR_LIMIT = 0.05
SETTLE = 30  # 단계마다 처음 몇 초는 판정에서 빼요 (올리는 중·막 올린 직후의 흔들림)
WINDOW = 30  # 「최근 30초」 p95·에러율로 판정
POLL = 5


def seconds(d):
    total = 0.0
    for value, unit in re.findall(r"([\d.]+)(ms|h|m|s)", d):
        total += float(value) * {"ms": 0.001, "s": 1, "m": 60, "h": 3600}[unit]
    return total


def plan(test, inspect):
    sc = inspect["scenarios"][test]
    stages, t = [], 0.0
    rate = sc.get("startRate")
    for s in sc.get("stages") or []:
        dur, target = seconds(s["duration"]), s["target"]
        if test == "stress" and stages and stages[-1]["target"] == target:
            stages[-1]["end"] = t + dur  # 유지 구간을 같은 단계로
        else:
            ramp = dur if test == "stress" else 0  # 스트레스는 단계의 첫 조각이 올리는 구간
            if sc["executor"] == "ramping-arrival-rate":
                label = f"초당 {rate}→{target}건"
            elif test == "spike":
                prev = stages[-1]["target"] if stages else 0
                label = f"{target}명 유지" if prev == target else f"{prev}→{target}명"
            else:
                label = f"{target}명"
            stages.append({"stage": len(stages) + 1, "label": label, "target": target, "start": t, "end": t + dur, "ramp": ramp})
        t += dur
        rate = target
    for s in stages:
        # 판정은 올리는 구간이 끝나고 30초 뒤부터 (spike는 짧은 구간 전체를 기록만 해요)
        settle = 0 if test == "spike" else s["ramp"] + SETTLE
        s["judge_from"] = min(s["start"] + settle, s["end"])
    return {"test": test, "executor": sc["executor"], "settle_seconds": SETTLE, "window_seconds": WINDOW, "stages": stages}


def query(expr, at=None):
    params = {"query": expr}
    if at:
        params["time"] = at
    with urllib.request.urlopen(f"{PROM}/api/v1/query?{urllib.parse.urlencode(params)}", timeout=10) as r:
        return json.load(r)["data"]["result"]


def cpu(pid):
    """k6 CPU(코어 1개 = 100%), 노트북 전체 사용률(%)."""
    try:
        k6 = float(subprocess.run(["ps", "-o", "%cpu=", "-p", str(pid)], capture_output=True, text=True).stdout.strip() or 0)
    except ValueError:
        k6 = None
    out = subprocess.run(["ps", "-A", "-o", "%cpu="], capture_output=True, text=True).stdout.split()
    total = sum(float(x) for x in out if re.match(r"^[\d.]+$", x))
    return k6, round(total / (os.cpu_count() or 1), 1)


def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def k6_pid(testid, parent, timeout=30):
    """run.sh가 넘긴 번호는 k6를 띄운 셸일 수 있어서, 이 testid로 도는 k6 프로세스를 찾아요."""
    until = time.time() + timeout
    while time.time() < until:
        found = subprocess.run(["pgrep", "-f", f"testid={testid} "], capture_output=True, text=True).stdout.split()
        found = [int(x) for x in found if int(x) != os.getpid()]
        if found:
            return found[0]
        if not alive(parent):
            break
        time.sleep(0.5)
    return parent


def watch(out, testid, pid, abort):
    out = Path(out)
    pid = k6_pid(testid, pid)
    p = json.loads((out / "plan.json").read_text())
    sel = f'testid="{testid}"'
    timeline = {"testid": testid, "k6_pid": pid, "abort_enabled": abort, "samples": [], "aborted": None}
    t0 = None
    while alive(pid):
        now = time.time()
        if t0 is None:
            try:
                if query(f"sum(k6_vus{{{sel}}})"):
                    t0 = now - POLL  # 첫 k6 지표가 보인 때(밀어 넣는 주기만큼 앞)
                    timeline["t0"] = t0
            except OSError:
                pass
        k6_cpu, sys_cpu = cpu(pid)
        sample = {"t": round(now, 1), "k6_cpu": k6_cpu, "sys_cpu": sys_cpu}
        if t0 is not None:
            el = now - t0
            stage = next((s for s in p["stages"] if s["start"] <= el < s["end"]), None)
            sample["elapsed"] = round(el, 1)
            if stage:
                sample["stage"] = stage["stage"]
            judged = stage and el - WINDOW >= stage["judge_from"]
            if judged:
                try:
                    p95 = {
                        r["metric"].get("type"): float(r["value"][1])
                        for r in query(
                            f"histogram_quantile(0.95, sum by (type) (rate(k6_http_req_duration_seconds{{{sel}}}[{WINDOW}s])))"
                        )
                    }
                    err = query(
                        f'sum(rate(k6_http_reqs_total{{{sel},expected_response="false"}}[{WINDOW}s]))'
                        f" / sum(rate(k6_http_reqs_total{{{sel}}}[{WINDOW}s]))"
                    )
                    err = float(err[0]["value"][1]) if err else 0.0
                except OSError:
                    p95, err = {}, 0.0
                over = [t for t, lim in P95_LIMIT.items() if (p95.get(t) or 0) > lim]
                if err >= ERROR_LIMIT:
                    over.append("에러율")
                sample.update({"p95": p95, "error_rate": err, "over": over})
                if over and abort and timeline["aborted"] is None:
                    timeline["aborted"] = {"stage": stage["stage"], "label": stage["label"], "elapsed": round(el, 1), "over": over}
                    print(f"\n  ! {stage['label']} 단계에서 최근 {WINDOW}초 기준 넘음({', '.join(over)}) → k6 정상 종료", flush=True)
                    os.kill(pid, signal.SIGINT)
        timeline["samples"].append(sample)
        (out / "timeline.json").write_text(json.dumps(timeline, ensure_ascii=False))
        time.sleep(POLL)
    timeline["ended"] = round(time.time(), 1)
    (out / "timeline.json").write_text(json.dumps(timeline, ensure_ascii=False))


if __name__ == "__main__":
    cmd, *args = sys.argv[1:] or ["help"]
    if cmd == "plan":
        print(json.dumps(plan(args[0], json.load(sys.stdin)), ensure_ascii=False, indent=2))
    elif cmd == "watch":
        watch(args[0], args[1], int(args[2]), "--abort" in args)
    else:
        print(__doc__)
        sys.exit(1)
