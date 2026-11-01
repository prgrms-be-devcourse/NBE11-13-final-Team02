"""단계가 있는 테스트(stress·breakpoint·spike)의 단계 계획과 실행 중 감시.

  python3 perf/lib/stagewatch.py plan <test>  < k6 inspect 출력 JSON  > plan.json
  python3 perf/lib/stagewatch.py watch <결과 폴더> <testid> <k6 pid> <BASE_URL>

plan: k6 프로필의 stages를 단계로 묶어요(같은 목표가 이어지면 한 단계: 올리기 + 유지). 단계마다 시작·끝(초)과 판정 시작
(스트레스는 올리는 구간 + 30초, 한계 찾기는 처음 30초를 빼요. spike는 구간 전체).
watch: 측정을 끊지 않고 기록만 해요. 5초마다 최근 30초 p95(종류별)·에러율, k6·노트북 CPU를 timeline.json에 남겨요(단계표가 「기준을 넘은
30초 창 수·가장 나빴던 창」을 여기서 세요). k6를 멈추는 건 안전 장치 두 가지뿐이에요:
  a) 5xx·연결 실패가 60초 넘게 계속 요청의 50%를 넘을 때
  b) api 헬스체크(BASE_URL/actuator/health)가 60초 넘게 계속 실패할 때
멈추면 사유를 timeline.json의 safety_stop과 화면에 남기고 k6에 SIGINT(결과 요약은 남아요)를 보내요.
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
ERROR_LIMIT = 0.01  # 단계 통과 기준의 에러율 (요청 실패 1% 미만)
SAFETY_ERROR = 0.5  # 안전 장치: 5xx·연결 실패 비율
SAFETY_SECONDS = 60  # 안전 장치: 이만큼 계속되면 멈춤
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


def healthy(base_url):
    try:
        with urllib.request.urlopen(f"{base_url}/actuator/health", timeout=5) as r:
            return r.status == 200
    except OSError:
        return False


def watch(out, testid, pid, base_url):
    out = Path(out)
    pid = k6_pid(testid, pid)
    p = json.loads((out / "plan.json").read_text()) if (out / "plan.json").exists() else {"stages": []}
    sel = f'testid="{testid}"'
    timeline = {"testid": testid, "k6_pid": pid, "samples": [], "safety_stop": None}
    t0 = None
    bad_since = {"error": None, "health": None}  # 안전 장치 조건이 처음 시작된 시각
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
                sample["judged"] = el - WINDOW >= stage["judge_from"]  # 30초 창이 판정 구간 안에 들어왔는지
            try:
                sample["p95"] = {
                    r["metric"].get("type"): float(r["value"][1])
                    for r in query(
                        f"histogram_quantile(0.95, sum by (type) (rate(k6_http_req_duration_seconds{{{sel}}}[{WINDOW}s])))"
                    )
                }
                err = query(
                    f'sum(rate(k6_http_reqs_total{{{sel},expected_response="false"}}[{WINDOW}s]))'
                    f" / sum(rate(k6_http_reqs_total{{{sel}}}[{WINDOW}s]))"
                )
                sample["error_rate"] = float(err[0]["value"][1]) if err else 0.0
                # 안전 장치 a): 최근 10초의 5xx·연결 실패(status 0) 비율
                hard = query(
                    f'sum(rate(k6_http_reqs_total{{{sel},status=~"5..|0"}}[10s]))'
                    f" / sum(rate(k6_http_reqs_total{{{sel}}}[10s]))"
                )
                sample["hard_error_rate"] = float(hard[0]["value"][1]) if hard else 0.0
            except (OSError, ValueError):
                pass
            sample["over"] = [t for t, lim in P95_LIMIT.items() if (sample.get("p95", {}).get(t) or 0) > lim]
            if (sample.get("error_rate") or 0) >= ERROR_LIMIT:
                sample["over"].append("에러율")
        sample["health"] = healthy(base_url)
        timeline["samples"].append(sample)
        reason = safety(sample, bad_since, now)
        if reason and timeline["safety_stop"] is None:
            timeline["safety_stop"] = {"reason": reason, "at": round(now, 1), "elapsed": sample.get("elapsed"), "stage": sample.get("stage")}
            print(f"\n  !! 안전 장치로 측정을 멈춰요: {reason}", flush=True)
            os.kill(pid, signal.SIGINT)
        (out / "timeline.json").write_text(json.dumps(timeline, ensure_ascii=False))
        time.sleep(POLL)
    timeline["ended"] = round(time.time(), 1)
    (out / "timeline.json").write_text(json.dumps(timeline, ensure_ascii=False))


def safety(sample, bad_since, now):
    """안전 장치 두 가지가 SAFETY_SECONDS 넘게 계속됐으면 사유를, 아니면 None."""
    checks = {
        "error": (sample.get("hard_error_rate") or 0) > SAFETY_ERROR,
        "health": not sample["health"],
    }
    for key, bad in checks.items():
        if not bad:
            bad_since[key] = None
        elif bad_since[key] is None:
            bad_since[key] = now
    if bad_since["error"] is not None and now - bad_since["error"] >= SAFETY_SECONDS:
        return f"5xx·연결 실패가 {SAFETY_SECONDS}초 넘게 계속 요청의 {int(SAFETY_ERROR * 100)}%를 넘음"
    if bad_since["health"] is not None and now - bad_since["health"] >= SAFETY_SECONDS:
        return f"api 헬스체크가 {SAFETY_SECONDS}초 넘게 계속 실패(응답 없음)"
    return None


def scenario_seconds(test, inspect):
    """프로필 하나가 도는 시간(초): 단계 합 또는 duration (예상 소요 시간 계산용)."""
    sc = inspect["scenarios"][test if test in inspect["scenarios"] else next(iter(inspect["scenarios"]))]
    if sc.get("stages"):
        return int(sum(seconds(s["duration"]) for s in sc["stages"]) + seconds(sc.get("gracefulRampDown") or "30s"))
    return int(seconds(sc.get("duration") or "0s"))


if __name__ == "__main__":
    cmd, *args = sys.argv[1:] or ["help"]
    if cmd == "seconds":
        print(scenario_seconds(args[0], json.load(sys.stdin)))
    elif cmd == "plan":
        print(json.dumps(plan(args[0], json.load(sys.stdin)), ensure_ascii=False, indent=2))
    elif cmd == "watch":
        watch(args[0], args[1], int(args[2]), args[3])
    else:
        print(__doc__)
        sys.exit(1)
