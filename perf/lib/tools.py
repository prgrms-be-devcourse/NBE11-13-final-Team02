"""결과 폴더를 채우고 비교하는 도구. run.sh·collect.sh·compare.sh가 불러요(표준 라이브러리만 써요).

  python3 perf/lib/tools.py prom <결과 폴더> <시작 epoch> <끝 epoch>   서버 지표 → CSV·JSON
  python3 perf/lib/tools.py pgdiff <before.csv> <after.csv> <out.csv>   테스트 동안 쌓인 쿼리 시간
  python3 perf/lib/tools.py row <결과 폴더>                             문서 「결과 기록」 표의 한 줄
  python3 perf/lib/tools.py compare <전 폴더> <후 폴더>                 전후 비교 표(마크다운)
"""

import csv
import json
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from dashboard import PANELS  # noqa: E402

PROM = "http://localhost:9090"
KST = timezone(timedelta(hours=9))
API = 'job="api",uri!~"/actuator.*"'
TYPES = ["lookup", "search", "judge"]
TYPE_KO = {"lookup": "조회", "search": "검색", "judge": "판정"}
ERROR_LIMIT = 0.05  # 단계 판정의 에러율 기준 (stagewatch.py와 같게)
# config.js의 p95 기준(초)
P95_LIMIT = {"lookup": 0.2, "search": 0.5, "judge": 1.0}


# 어느 api의 지표인지: prometheus.yml이 로컬 api에는 target="local", 서버 터널에는 target="server"를 붙여요.
# 결과 폴더의 meta.json 대상에 맞춰 export_prom이 정해요(라벨이 생기기 전 지표는 서버 쪽으로 봐요)
TARGET_LABEL = 'target!="local"'


def prom(path, **params):
    if "query" in params:
        params["query"] = params["query"].replace('job="api"', f'job="api",{TARGET_LABEL}')
    url = f"{PROM}/api/v1/{path}?{urllib.parse.urlencode(params)}"
    with urllib.request.urlopen(url, timeout=60) as r:
        body = json.load(r)
    if body.get("status") != "success":
        raise RuntimeError(body)
    return body["data"]["result"]


def instant(query, at):
    return prom("query", query=query, time=at)


def scalar(query, at):
    res = instant(query, at)
    return float(res[0]["value"][1]) if res else None


def num(v):
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return None if f != f else f  # NaN → None


# ── prom: 서버 지표 내보내기 ─────────────────────────────────────────────


def export_prom(out, start, end):
    global TARGET_LABEL
    out = Path(out)
    meta = json.loads((out / "meta.json").read_text()) if (out / "meta.json").exists() else {}
    TARGET_LABEL = 'target="local"' if meta.get("target") == "local" else 'target!="local"'
    w = f"{max(int(end - start), 60)}s"

    def by_uri(q):
        return {(r["metric"].get("method"), r["metric"].get("uri")): num(r["value"][1]) for r in instant(q, end)}

    bucket = f"increase(http_server_requests_seconds_bucket{{{API}}}[{w}])"
    p95 = by_uri(f"histogram_quantile(0.95, sum by (le, method, uri) ({bucket}))")
    p99 = by_uri(f"histogram_quantile(0.99, sum by (le, method, uri) ({bucket}))")
    count = by_uri(f"sum by (method, uri) (increase(http_server_requests_seconds_count{{{API}}}[{w}]))")
    total = by_uri(f"sum by (method, uri) (increase(http_server_requests_seconds_sum{{{API}}}[{w}]))")
    errors = by_uri(
        f'sum by (method, uri) (increase(http_server_requests_seconds_count{{{API},status=~"5.."}}[{w}]))'
    )

    rows = []
    for key, n in count.items():
        if not n:
            continue
        rows.append(
            {
                "method": key[0],
                "uri": key[1],
                "count": round(n),
                "avg_ms": ms(total.get(key, 0) / n),
                "p95_ms": ms(p95.get(key)),
                "p99_ms": ms(p99.get(key)),
                "errors_5xx": round(errors.get(key) or 0),
            }
        )
    rows.sort(key=lambda r: r["p95_ms"] or 0, reverse=True)
    write_csv(out / "server_by_uri.csv", rows, ["method", "uri", "count", "avg_ms", "p95_ms", "p99_ms", "errors_5xx"])

    sub = f"[{w}:15s]"
    cpu = '(1 - avg(rate(node_cpu_seconds_total{mode="idle"}[1m])))'
    steal = 'avg(rate(node_cpu_seconds_total{mode="steal"}[1m]))'
    heap = (
        '(sum by (job) (jvm_memory_used_bytes{area="heap",job="api"})'
        ' / sum by (job) (jvm_memory_max_bytes{area="heap",job="api"} > 0))'
    )
    summary = {
        "window": w,
        "server_rps": num(scalar(f"sum(increase(http_server_requests_seconds_count{{{API}}}[{w}]))", end)),
        "server_p95_ms": ms(scalar(f"histogram_quantile(0.95, sum by (le) ({bucket}))", end)),
        "server_p99_ms": ms(scalar(f"histogram_quantile(0.99, sum by (le) ({bucket}))", end)),
        "server_5xx": num(
            scalar(f'sum(increase(http_server_requests_seconds_count{{{API},status=~"5.."}}[{w}]))', end)
        ),
        "ec2_cpu_avg": num(scalar(f"avg_over_time({cpu}{sub})", end)),
        "ec2_cpu_max": num(scalar(f"max_over_time({cpu}{sub})", end)),
        "ec2_steal_max": num(scalar(f"max_over_time({steal}{sub})", end)),
        "ec2_mem_available_min_bytes": num(scalar(f"min_over_time(node_memory_MemAvailable_bytes[{w}])", end)),
        "api_heap_max_ratio": num(scalar(f"max_over_time({heap}{sub})", end)),
        "api_heap_max_bytes": num(scalar('sum(jvm_memory_max_bytes{area="heap",job="api"} > 0)', end)),
        "api_gc_pause_max_ms": ms(scalar(f'max_over_time(max(jvm_gc_pause_seconds_max{{job="api"}})[{w}:5s])', end)),
        "hikari_max": num(scalar('sum(hikaricp_connections_max{job="api"})', end)),
        "hikari_active_max": num(scalar(f'max_over_time(sum(hikaricp_connections_active{{job="api"}})[{w}:5s])', end)),
        "hikari_pending_max": num(
            scalar(f'max_over_time(sum(hikaricp_connections_pending{{job="api"}})[{w}:5s])', end)
        ),
        # 커넥션을 얻기까지 기다린 시간(평균·최대), 구간 동안 GC 멈춤 합계
        "hikari_acquire_avg_ms": ms(
            scalar(
                f'sum(increase(hikaricp_connections_acquire_seconds_sum{{job="api"}}[{w}]))'
                f' / sum(increase(hikaricp_connections_acquire_seconds_count{{job="api"}}[{w}]))',
                end,
            )
        ),
        "hikari_acquire_max_ms": ms(
            scalar(f'max_over_time(max(hikaricp_connections_acquire_seconds_max{{job="api"}})[{w}:5s])', end)
        ),
        # 커넥션을 쥐는 시간: 빌린 한 번 평균·최대, 요청 하나가 쥔 시간 합(쥔 시간 합계 / 서버 요청 수)
        "hikari_usage_avg_ms": ms(
            scalar(
                f'sum(increase(hikaricp_connections_usage_seconds_sum{{job="api"}}[{w}]))'
                f' / sum(increase(hikaricp_connections_usage_seconds_count{{job="api"}}[{w}]))',
                end,
            )
        ),
        "hikari_usage_max_ms": ms(
            scalar(f'max_over_time(max(hikaricp_connections_usage_seconds_max{{job="api"}})[{w}:5s])', end)
        ),
        "hikari_usage_per_request_ms": ms(
            scalar(
                f'sum(increase(hikaricp_connections_usage_seconds_sum{{job="api"}}[{w}]))'
                f" / sum(increase(http_server_requests_seconds_count{{{API}}}[{w}]))",
                end,
            )
        ),
        "api_gc_pause_total_ms": ms(scalar(f'sum(increase(jvm_gc_pause_seconds_sum{{job="api"}}[{w}]))', end)),
        # 판정 캐시 적중률 (api가 mujuthaek_judging_cache_total{cache, result=hit|miss}을 낼 때만)
        "judging_cache_hit_ratio": {
            r["metric"].get("cache"): num(r["value"][1])
            for r in instant(
                f'sum by (cache) (increase(mujuthaek_judging_cache_total{{job="api",result="hit"}}[{w}]))'
                f' / sum by (cache) (increase(mujuthaek_judging_cache_total{{job="api"}}[{w}]))',
                end,
            )
        },
        "containers": {
            r["metric"].get("name"): {"mem_max_bytes": num(r["value"][1])}
            for r in instant(f'max_over_time(container_memory_working_set_bytes{{name=~"mujuthaek-.+"}}[{w}])', end)
        },
    }
    for r in instant(
        f'max_over_time(sum by (name) (rate(container_cpu_usage_seconds_total{{name=~"mujuthaek-.+"}}[1m])){sub})', end
    ):
        summary["containers"].setdefault(r["metric"].get("name"), {})["cpu_max_cores"] = num(r["value"][1])
    if summary["server_rps"] is not None:
        summary["server_rps"] = round(summary["server_rps"] / (end - start), 2)
    (out / "server_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")

    # 대시보드 패널과 같은 쿼리를 15초 간격 CSV로: 이미지 없이도 숫자로 다시 그릴 수 있어요
    ts = out / "timeseries"
    ts.mkdir(exist_ok=True)
    for i, (title, _unit, targets) in enumerate(PANELS, start=1):
        rows = []
        for expr, legend in targets:
            for r in prom("query_range", query=expr, start=start, end=end, step=15):
                label = legend
                for k, v in r["metric"].items():
                    label = label.replace("{{" + k + "}}", v)
                for t, v in r["values"]:
                    rows.append({"time": kst(float(t)), "series": label, "value": v})
        write_csv(ts / f"{i:02d}.csv", rows, ["time", "series", "value"], title)

    meta = json.loads((out / "meta.json").read_text()) if (out / "meta.json").exists() else {}
    if (out / "plan.json").exists():
        export_stages(out)


# 노트북 한계 의심: 로컬은 k6와 api가 같은 노트북이라, k6가 CPU를 많이 쓰거나 반복을 버리면(dropped) 서버 한계가 아니라 노트북 한계일 수 있어요
K6_CPU_SUSPECT = 200  # k6 평균 CPU % (코어 1개 = 100%)
SYS_CPU_SUSPECT = 90  # 노트북 전체 CPU 최대 %


def export_stages(out):
    """단계(plan.json)마다 판정 구간(처음 30초 뺌)의 VU·RPS·에러율·p95·p99·버려진 반복·k6 CPU를 한 줄씩.
    단계를 다 못 채우고 끝나도 그때까지를 한 줄로(partial). over = 처음 기준(p95·에러율 5%)을 넘은 항목."""
    out = Path(out)
    plan = json.loads((out / "plan.json").read_text())
    meta = json.loads((out / "meta.json").read_text())
    tl = json.loads((out / "timeline.json").read_text()) if (out / "timeline.json").exists() else {}
    testid = meta.get("prometheus_testid")
    sel = f'testid="{testid}"'
    t0 = tl.get("t0") or meta["start_epoch"]
    test_end = meta["end_epoch"]
    aborted = (tl.get("aborted") or {}).get("stage")
    rows = []
    for st in plan["stages"]:
        s_from, s_to = t0 + st["judge_from"], min(t0 + st["end"], test_end)
        partial = t0 + st["end"] > test_end
        if t0 + st["start"] >= test_end:
            break  # 시작도 못 한 단계
        if s_to - s_from < 10:  # 판정 구간이 거의 없으면 그 단계 전체로
            s_from = t0 + st["start"]
        dur = max(int(s_to - s_from), 1)
        w = f"{dur}s"
        row = {
            "stage": st["stage"],
            "label": st["label"],
            "target": st["target"],
            "from": kst(s_from),
            "to": kst(s_to),
            "seconds": dur,
            "partial": partial,
            "aborted_here": aborted == st["stage"],
            "vus_max": num(scalar(f"max_over_time(sum(k6_vus{{{sel}}})[{w}:5s])", s_to)),
            "rps": num(scalar(f"sum(increase(k6_http_reqs_total{{{sel}}}[{w}])) / {dur}", s_to)),
            "error_rate": num(
                scalar(
                    f'sum(increase(k6_http_reqs_total{{{sel},expected_response="false"}}[{w}]))'
                    f" / sum(increase(k6_http_reqs_total{{{sel}}}[{w}]))",
                    s_to,
                )
            ),
            "dropped_iterations": num(scalar(f"sum(increase(k6_dropped_iterations_total{{{sel}}}[{w}]))", s_to)) or 0,
            "hikari_active_max": num(scalar(f'max_over_time(sum(hikaricp_connections_active{{job="api"}})[{w}:5s])', s_to)),
            "hikari_pending_max": num(scalar(f'max_over_time(sum(hikaricp_connections_pending{{job="api"}})[{w}:5s])', s_to)),
            "hikari_usage_avg_ms": ms(
                scalar(
                    f'sum(increase(hikaricp_connections_usage_seconds_sum{{job="api"}}[{w}]))'
                    f' / sum(increase(hikaricp_connections_usage_seconds_count{{job="api"}}[{w}]))',
                    s_to,
                )
            ),
            "server_p95_ms": ms(
                scalar(
                    f"histogram_quantile(0.95, sum by (le) (increase(http_server_requests_seconds_bucket{{{API}}}[{w}])))",
                    s_to,
                )
            ),
        }
        over = []
        for q in ("0.95", "0.99"):
            vals = {
                r["metric"].get("type"): num(r["value"][1])
                for r in instant(
                    f"histogram_quantile({q}, sum by (type) (increase(k6_http_req_duration_seconds{{{sel}}}[{w}])))", s_to
                )
            }
            for ty in TYPES:
                row[f"p{q[2:]}_{ty}_ms"] = ms(vals.get(ty))
                if q == "0.95" and vals.get(ty) is not None and vals[ty] > P95_LIMIT[ty]:
                    over.append(f"{TYPE_KO[ty]} p95")
        if row["error_rate"] is None and row["rps"]:
            row["error_rate"] = 0.0  # 실패한 요청이 없으면 실패 시계열이 없어요
        if (row["error_rate"] or 0) >= ERROR_LIMIT:
            over.append("에러율")
        samples = [x for x in tl.get("samples", []) if s_from <= x["t"] <= s_to]
        k6c = [x["k6_cpu"] for x in samples if x.get("k6_cpu") is not None]
        sysc = [x["sys_cpu"] for x in samples if x.get("sys_cpu") is not None]
        row["k6_cpu_avg"] = round(sum(k6c) / len(k6c), 1) if k6c else None
        row["sys_cpu_max"] = max(sysc) if sysc else None
        suspect = []
        if meta.get("target") == "local":
            if row["dropped_iterations"] > 0:
                suspect.append(f"버려진 반복 {int(row['dropped_iterations'])}")
            if (row["k6_cpu_avg"] or 0) >= K6_CPU_SUSPECT:
                suspect.append(f"k6 CPU {row['k6_cpu_avg']}%")
            if (row["sys_cpu_max"] or 0) >= SYS_CPU_SUSPECT:
                suspect.append(f"노트북 CPU {row['sys_cpu_max']}%")
        row["over"] = "·".join(over)
        row["laptop_suspect"] = "·".join(suspect)
        rows.append(row)
    cols = (
        ["stage", "label", "target", "from", "to", "seconds", "partial", "aborted_here", "vus_max", "rps", "error_rate"]
        + [f"p{q}_{ty}_ms" for q in ("95", "99") for ty in TYPES]
        + ["server_p95_ms", "hikari_active_max", "hikari_pending_max", "hikari_usage_avg_ms"]
        + ["dropped_iterations", "k6_cpu_avg", "sys_cpu_max", "over", "laptop_suspect"]
    )
    write_csv(out / "stages.csv", rows, cols)


# ── pgdiff ──────────────────────────────────────────────────────────────


def pgdiff(before, after, out):
    def load(p):
        with open(p, newline="") as f:
            return {r["queryid"]: r for r in csv.DictReader(f)}

    b, a = load(before), load(after)
    rows = []
    for qid, r in a.items():
        prev = b.get(qid, {})
        calls = int(r["calls"]) - int(prev.get("calls", 0))
        if calls <= 0:
            continue
        total = float(r["total_ms"]) - float(prev.get("total_ms", 0))
        rows.append(
            {
                "total_ms": round(total, 1),
                "calls": calls,
                "mean_ms": round(total / calls, 2),
                "rows": int(r["rows"]) - int(prev.get("rows", 0)),
                "blks_read": int(r["shared_blks_read"]) - int(prev.get("shared_blks_read", 0)),
                "queryid": qid,
                "query": r["query"],
            }
        )
    rows.sort(key=lambda r: r["total_ms"], reverse=True)
    write_csv(out, rows[:50], ["total_ms", "calls", "mean_ms", "rows", "blks_read", "queryid", "query"])


# ── row · compare ───────────────────────────────────────────────────────


def load_result(d):
    d = Path(d)
    meta = json.loads((d / "meta.json").read_text())
    k6 = json.loads((d / "summary.json").read_text()).get("metrics", {}) if (d / "summary.json").exists() else {}
    srv = json.loads((d / "server_summary.json").read_text()) if (d / "server_summary.json").exists() else {}
    uris = read_csv(d / "server_by_uri.csv")
    slow = read_csv(d / "slow_queries.csv")
    stages = read_csv(d / "stages.csv")
    return meta, k6, srv, uris, slow, stages


def k6v(k6, metric, stat):
    m = k6.get(metric)
    if not m:
        return None
    return (m.get("values") or m).get(stat)


def k6_type(k6, ty):
    key = f"http_req_duration{{type:{ty}}}"
    return k6v(k6, key, "p(95)"), k6v(k6, key, "p(99)")


def limit_stage(stages):
    """처음으로 p95나 에러율 기준을 넘은 단계 (단계 판정 구간 기준)."""
    for s in stages:
        if s.get("over"):
            return s
    return None


def stage_verdict(meta, stages):
    """결과 줄·비교표의 한계 칸: 처음 기준을 넘은 단계, 끝까지 버텼으면 「N명까지 버팀」, 노트북 한계 의심 단계도."""
    test = meta["test"]
    if test not in ("stress", "breakpoint", "spike"):
        return ""
    if not stages:
        return "단계 기록 없음"
    unit = "건/초" if test == "breakpoint" else "명"
    lim = limit_stage(stages)
    last = stages[-1]
    if test == "spike":
        text = f"기준 넘은 구간: {lim['label']}({lim['over']})" if lim else "모든 구간 기준 안"
    elif lim:
        text = f"한계: {lim['label']} 단계({lim['over']})"
        if test == "breakpoint":
            i = stages.index(lim)
            text += f", 최대 RPS {stages[i - 1]['rps'] if i > 0 else '-'}"
    elif str(last.get("partial")) == "True":
        text = f"{last['label']} 단계에서 끝남(기준 안 넘음)"
    else:
        text = f"{int(float(last['target'])):,}{unit}까지 버팀"
    suspects = [f"{s['label']}({s['laptop_suspect']})" for s in stages if s.get("laptop_suspect")]
    if suspects:
        text += " · 노트북 한계 의심: " + ", ".join(suspects)
    return text


def row(d):
    meta, k6, srv, uris, _slow, stages = load_result(d)
    lat = " / ".join(f"{fmt(p95)}·{fmt(p99)}" for p95, p99 in (k6_type(k6, t) for t in TYPES))
    slowest = uris[0] if uris else None
    server = f"{slowest['p95_ms']}·{slowest['p99_ms']} ({slowest['method']} {slowest['uri']})" if slowest else "-"
    err = k6v(k6, "http_req_failed", "rate")
    bottleneck = stage_verdict(meta, stages)
    cells = [
        meta["started_at"][:16].replace("T", " "),
        TEST_KO.get(meta["test"], meta["test"]),
        meta.get("api_tag", "-"),
        meta.get("load", ""),
        fmt(k6v(k6, "http_reqs", "rate"), 1),
        lat,
        server,
        f"{err * 100:.2f}%" if err is not None else "-",
        bottleneck,
        meta.get("note", ""),
    ]
    print("| " + " | ".join(str(c) for c in cells) + " |")


TEST_KO = {"smoke": "스모크", "load": "부하", "soak": "내구", "stress": "스트레스", "breakpoint": "한계 찾기", "spike": "스파이크"}


def compare(da, db):
    ma, ka, sa, ua, qa, sta = load_result(da)
    mb, kb, sb, ub, qb, stb = load_result(db)
    print(f"## {TEST_KO.get(ma['test'], ma['test'])}: {ma.get('api_tag')} → {mb.get('api_tag')}\n")
    kinds = [f"{m.get('repeat')}회 중앙값" if m.get("repeat", 1) > 1 else "한 번" for m in (ma, mb)]
    print(f"측정: 전 {kinds[0]} · 후 {kinds[1]} (예열 전 {ma.get('warmup_seconds', 0)}초 · 후 {mb.get('warmup_seconds', 0)}초, 결과에서 뺐어요)\n")
    if mb.get("note"):
        print(f"고친 것: {mb['note']}\n")
    print("| 지표 | 전 | 후 | 변화 |\n|---|---|---|---|")
    lines = [("초당 요청(k6)", k6v(ka, "http_reqs", "rate"), k6v(kb, "http_reqs", "rate"), 1)]
    for t in TYPES:
        a95, a99 = k6_type(ka, t)
        b95, b99 = k6_type(kb, t)
        lines += [(f"k6 {TYPE_KO[t]} p95 (ms)", a95, b95, 0), (f"k6 {TYPE_KO[t]} p99 (ms)", a99, b99, 0)]
    lines += [
        ("에러율 (%)", pct(k6v(ka, "http_req_failed", "rate")), pct(k6v(kb, "http_req_failed", "rate")), 2),
        ("서버 p95 (ms)", sa.get("server_p95_ms"), sb.get("server_p95_ms"), 0),
        ("서버 p99 (ms)", sa.get("server_p99_ms"), sb.get("server_p99_ms"), 0),
        ("EC2 CPU 평균 (%)", pct(sa.get("ec2_cpu_avg")), pct(sb.get("ec2_cpu_avg")), 1),
        ("EC2 CPU 최대 (%)", pct(sa.get("ec2_cpu_max")), pct(sb.get("ec2_cpu_max")), 1),
        ("힙 최대 (%)", pct(sa.get("api_heap_max_ratio")), pct(sb.get("api_heap_max_ratio")), 1),
        ("GC 멈춤 최대 (ms)", sa.get("api_gc_pause_max_ms"), sb.get("api_gc_pause_max_ms"), 0),
        ("DB 커넥션 사용 최대", sa.get("hikari_active_max"), sb.get("hikari_active_max"), 0),
        ("DB 커넥션 대기 최대", sa.get("hikari_pending_max"), sb.get("hikari_pending_max"), 0),
        ("DB 커넥션 얻는 시간 평균 (ms)", sa.get("hikari_acquire_avg_ms"), sb.get("hikari_acquire_avg_ms"), 1),
        ("DB 커넥션 얻는 시간 최대 (ms)", sa.get("hikari_acquire_max_ms"), sb.get("hikari_acquire_max_ms"), 0),
        ("GC 멈춤 합계 (ms)", sa.get("api_gc_pause_total_ms"), sb.get("api_gc_pause_total_ms"), 0),
        ("DB 커넥션 쥐는 시간 평균 (ms, 빌린 한 번)", sa.get("hikari_usage_avg_ms"), sb.get("hikari_usage_avg_ms"), 2),
        ("DB 커넥션 쥐는 시간 최대 (ms)", sa.get("hikari_usage_max_ms"), sb.get("hikari_usage_max_ms"), 0),
        ("요청 하나가 커넥션을 쥔 시간 (ms)", sa.get("hikari_usage_per_request_ms"), sb.get("hikari_usage_per_request_ms"), 1),
    ]
    for cache in sorted(set(sa.get("judging_cache_hit_ratio") or {}) | set(sb.get("judging_cache_hit_ratio") or {})):
        lines.append(
            (
                f"판정 캐시 적중률 {cache} (%)",
                pct((sa.get("judging_cache_hit_ratio") or {}).get(cache)),
                pct((sb.get("judging_cache_hit_ratio") or {}).get(cache)),
                1,
            )
        )
    for name, a, b, nd in lines:
        print(f"| {name} | {fmt(a, nd)} | {fmt(b, nd)} | {change(a, b)} |")
    if ma["test"] in ("stress", "breakpoint", "spike"):
        print(f"| 한계 | {stage_verdict(ma, sta)} | {stage_verdict(mb, stb)} | |")

    print("\n### API별 서버 p95 (전 기준 느린 10개)\n\n| API | 전 p95 | 후 p95 | 변화 |\n|---|---|---|---|")
    after = {(u["method"], u["uri"]): u for u in ub}
    for u in ua[:10]:
        v = after.get((u["method"], u["uri"]), {})
        print(f"| {u['method']} {u['uri']} | {u['p95_ms']} | {v.get('p95_ms', '-')} | {change(num(u['p95_ms']), num(v.get('p95_ms')))} |")

    print("\n### 느린 쿼리 (전 기준 총 시간 상위 5개)\n\n| 쿼리 | 전 평균 ms | 후 평균 ms | 전 호출 | 후 호출 |\n|---|---|---|---|---|")
    after_q = {q["queryid"]: q for q in qb}
    for q in qa[:5]:
        v = after_q.get(q["queryid"], {})
        text = q["query"][:80].replace("|", "\\|")
        print(f"| `{text}` | {q['mean_ms']} | {v.get('mean_ms', '-')} | {q['calls']} | {v.get('calls', '-')} |")


# ── 공통 ────────────────────────────────────────────────────────────────


def ms(v):
    v = num(v)
    return None if v is None else round(v * 1000, 1)


def pct(v):
    v = num(v)
    return None if v is None else v * 100


def fmt(v, nd=0):
    v = num(v)
    if v is None:
        return "-"
    return f"{v:.{nd}f}" if nd else f"{round(v)}"


def change(a, b):
    a, b = num(a), num(b)
    if a is None or b is None or a == 0:
        return "-"
    return f"{(b - a) / a * 100:+.0f}%"


def kst(epoch):
    return datetime.fromtimestamp(epoch, KST).isoformat(timespec="seconds")


def write_csv(path, rows, cols, title=None):
    with open(path, "w", newline="") as f:
        if title:
            f.write(f"# {title}\n")
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)


def read_csv(path):
    if not Path(path).exists():
        return []
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


# ── median: 반복 측정(run.sh --repeat)의 중앙값 ──────────────────────────────


def median_of(values):
    nums = sorted(v for v in (num(x) for x in values) if v is not None)
    if not nums:
        return None
    mid = len(nums) // 2
    return nums[mid] if len(nums) % 2 else (nums[mid - 1] + nums[mid]) / 2


def median_tree(trees):
    """같은 모양의 JSON 여러 개에서 숫자 칸은 중앙값, 나머지는 첫 번째 값."""
    first = trees[0]
    if isinstance(first, dict):
        keys = list(dict.fromkeys(k for t in trees if isinstance(t, dict) for k in t))
        return {k: median_tree([t[k] for t in trees if isinstance(t, dict) and k in t]) for k in keys}
    if isinstance(first, bool) or not isinstance(first, (int, float)):
        return first
    m = median_of(trees)
    return round(m, 3) if isinstance(m, float) else m


def median_csv(paths, out, key_cols, sort_col=None):
    """CSV 여러 개를 key_cols가 같은 줄끼리 묶어 숫자 칸은 중앙값으로."""
    tables = [read_csv(p) for p in paths]
    tables = [t for t in tables if t]
    if not tables:
        return
    cols = list(tables[0][0].keys())
    groups = {}
    for t in tables:
        for r in t:
            groups.setdefault(tuple(r.get(k) for k in key_cols), []).append(r)
    rows = []
    for rs in groups.values():
        row_ = dict(rs[0])
        for c in cols:
            if c in key_cols:
                continue
            vals = [r.get(c) for r in rs]
            if all(num(v) is not None for v in vals if v not in (None, "")) and any(v not in (None, "") for v in vals):
                m = median_of(vals)
                row_[c] = "" if m is None else (f"{m:.1f}" if isinstance(m, float) and not m.is_integer() else str(int(m)) if m == int(m) else str(m))
        rows.append(row_)
    if sort_col:
        rows.sort(key=lambda r: -(num(r.get(sort_col)) or 0))
    write_csv(out, rows, cols)


def median(out, *runs):
    """반복 측정한 결과 폴더들(run1, run2 …)의 중앙값을 out에 같은 파일 이름으로 써요. compare·row가 그대로 읽어요."""
    out, runs = Path(out), [Path(r) for r in runs]
    metas = [json.loads((r / "meta.json").read_text()) for r in runs]
    for name in ("summary.json", "server_summary.json"):
        trees = [json.loads((r / name).read_text()) for r in runs if (r / name).exists()]
        if trees:
            (out / name).write_text(json.dumps(median_tree(trees), ensure_ascii=False, indent=2) + "\n")
    median_csv([r / "server_by_uri.csv" for r in runs], out / "server_by_uri.csv", ["method", "uri"], "p95_ms")
    median_csv([r / "slow_queries.csv" for r in runs], out / "slow_queries.csv", ["queryid"], "total_ms")
    median_csv([r / "stages.csv" for r in runs], out / "stages.csv", ["stage", "label"])
    meta = dict(metas[0])
    meta.update(
        {
            "repeat": len(runs),
            "runs": [r.name for r in runs],
            "median_of": "runs의 중앙값 (2회면 평균)",
            "started_at": metas[0]["started_at"],
            "ended_at": metas[-1]["ended_at"],
            "start_epoch": metas[0]["start_epoch"],
            "end_epoch": metas[-1]["end_epoch"],
            "k6_exit_code": max(m.get("k6_exit_code", 0) for m in metas),
            "prometheus_testid": [m.get("prometheus_testid") for m in metas],
        }
    )
    (out / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    cmd, *args = sys.argv[1:] or ["help"]
    if cmd == "prom":
        export_prom(args[0], float(args[1]), float(args[2]))
    elif cmd == "pgdiff":
        pgdiff(*args)
    elif cmd == "row":
        row(args[0])
    elif cmd == "compare":
        compare(args[0], args[1])
    elif cmd == "median":
        median(args[0], *args[1:])
    else:
        print(__doc__)
        sys.exit(1)
