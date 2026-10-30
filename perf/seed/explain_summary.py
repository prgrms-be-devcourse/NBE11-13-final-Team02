"""explain.sh 결과(all.txt)를 쿼리별 파일로 나누고 summary.md를 써요.

summary.md: 쿼리별 실행 시간·읽은 블록·큰 Seq Scan·디스크 정렬, 그리고 인덱스 후보(적기만 해요).
  python3 perf/seed/explain_summary.py <결과 폴더> <대상>
"""

import re
import sys
from pathlib import Path

# 쿼리 이름 → 어떤 API에서 쓰는지
API = {
    "notice_candidates_sido": "notices/search · home/summary (시도 필터)",
    "notice_candidates_sigungu": "notices/search · home/summary (시군구 필터)",
    "notice_candidates_all": "notices/search · home/summary (지역 없음)",
    "notice_current_versions": "notices/search · home/summary",
    "notice_judging_payloads": "notices/search · home/summary (판정 캐시가 빌 때)",
    "area_locations": "notices/search · home/summary · notices/{id}",
    "area_metrics": "notices/search · home/summary · notices/{id}",
    "area_life_scores": "notices/search · home/summary · notices/{id}",
    "map_points_seoul": "notices/map (서울)",
    "map_points_capital": "notices/map (수도권)",
    "map_summaries": "notices/map",
    "notice_detail": "notices/{id}",
    "notice_area_scores": "notices/{id}",
    "benefits_open_sido": "benefits/search · home/summary (시도 필터)",
    "benefits_open_all": "benefits/search · home/summary (지역 없음)",
    "benefit_conditions": "benefits/search · home/summary",
    "benefit_supports": "benefits/search · home/summary",
    "favorites_page": "users/me/favorites",
    "favorite_benefit_one": "users/me/favorites (혜택 한 줄마다)",
    "notifications_page": "users/me/notifications",
    "notifications_unread_count": "users/me/notifications/unread-count",
}

# 코드를 읽어 이미 보이는 후보. 계획에서 그 모양이 보일 때만 적어요
HINTS = [
    ("notice_candidates", lambda p: re.search(r'"?left"?\(\(?(r\.)?sigungu_code', p),
     "notice.notice_regions: `left(sigungu_code, 2) in (…)`는 `notice_regions_code_idx (sigungu_code, notice_id)`를 못 써요 "
     "→ `(left(sigungu_code, 2), notice_id)` 식 인덱스, 또는 쿼리를 `sigungu_code like '11%'`(text_pattern_ops)로"),
    ("notice_candidates", lambda p: "Seq Scan on notices" in p,
     "notice.notices: 후보 300개를 마감순으로 뽑는데 표 전체를 훑어요 → `(field, coalesce(apply_end, '9999-12-31'), id) "
     "where current_version > 0 and not hidden and merged_into is null` 부분 인덱스(정렬까지 인덱스로)"),
    ("notice_judging_payloads", lambda p: True,
     "notice.notice_versions.payload: 판정 캐시가 빌 때마다 300개 payload(압축된 큰 jsonb)를 다 풀어요 "
     "→ 판정에 쓰는 judgment·notice만 따로 저장하거나, 캐시(현재 2,000개 LRU)를 데워 두기"),
    ("map_points", lambda p: "Sort" in p,
     "area.notice_locations: `distinct on (notice_id)` 때문에 영역 안 위치를 다 읽고 정렬한 뒤 5,000개를 잘라요 "
     "→ 대표 위치만 표시하는 열 + 부분 GiST, 또는 확대 단계별 집계"),
    ("map_summaries", lambda p: "SubPlan" in p,
     "notice.notice_regions: 지도 요약이 공고마다 `array(select …)` 하위 쿼리를 돌려요(최대 1,000번) → join + array_agg로"),
    ("benefits_open", lambda p: True,
     "benefit.benefits: 지역 앞자리 `like`는 `benefits_region_idx`(GIN)를 못 써요. 게다가 열린 혜택 전부와 조건 전부를 "
     "요청마다 읽어 Java에서 판정해요 → 시도 코드 배열 열 + GIN(`&&`), 또는 열린 혜택·조건을 앱 메모리에 캐시"),
    ("favorite_benefit_one", lambda p: True,
     "관심 목록이 혜택·지원 사업을 한 줄씩 따로 읽어요(N+1, 한 페이지 최대 20번) → id 묶음으로 한 번에"),
]

BIG_ROWS = 5000


def num(pattern, text, cast=float):
    m = re.search(pattern, text)
    return cast(m.group(1)) if m else None


def sections(text):
    out, name, buf = {}, None, []
    for line in text.splitlines():
        m = re.match(r"^===== (\S+)", line)
        if m:
            if name:
                out[name] = "\n".join(buf)
            name, buf = m.group(1), []
        elif name:
            buf.append(line)
    if name:
        out[name] = "\n".join(buf)
    return out


def seq_scans(plan):
    found = []
    lines = plan.splitlines()
    for i, line in enumerate(lines):
        m = re.search(r"Seq Scan on (\S+).*actual time=[\d.]+\.\.[\d.]+ rows=(\d+) loops=(\d+)", line)
        if not m:
            continue
        removed = 0
        for nxt in lines[i + 1 : i + 6]:
            r = re.search(r"Rows Removed by Filter: (\d+)", nxt)
            if r:
                removed = int(r.group(1))
                break
        scanned = (int(m.group(2)) + removed) * int(m.group(3))
        if scanned >= BIG_ROWS:
            found.append(f"{m.group(1)} {scanned:,}행")
    return found


def main(out, target):
    out = Path(out)
    parts = sections((out / "all.txt").read_text())
    rows, hints = [], []
    for name, plan in parts.items():
        (out / f"{name}.txt").write_text(plan.strip() + "\n")
        exec_ms = num(r"Execution Time: ([\d.]+) ms", plan)
        hit = num(r"Buffers: shared hit=(\d+)", plan, int) or 0
        read = num(r"Buffers: shared (?:hit=\d+ )?read=(\d+)", plan, int) or 0
        disk = "있음" if "external merge" in plan or "Disk:" in plan else ""
        jit = num(r"Timing: Generation [\d.]+ ms.*Total ([\d.]+) ms", plan)
        scans = ", ".join(seq_scans(plan))
        rows.append((name, exec_ms, jit, hit, read, scans, disk))
        for prefix, test, text in HINTS:
            if name.startswith(prefix) and test(plan) and text not in hints:
                hints.append(text)
        for s in seq_scans(plan):
            table = s.split()[0]
            generic = f"{table}: 큰 Seq Scan({name}) — 거르는 열에 인덱스가 있는지 확인"
            if not any(table.split(".")[-1] in h for h in hints) and generic not in hints:
                hints.append(generic)

    md = [f"# 실행 계획 요약 ({target}, {out.name})", ""]
    md += ["블록은 8KB, hit = 메모리, read = 디스크. 실행 시간은 캐시를 한 번 데운 뒤 잰 값이에요.", ""]
    md += ["| 쿼리 | 쓰는 API | 실행 ms | 그중 JIT ms | 블록 hit / read | 큰 Seq Scan | 디스크 정렬 |",
           "|---|---|---:|---:|---:|---|---|"]
    for name, ms, jit, hit, read, scans, disk in sorted(rows, key=lambda r: -(r[1] or 0)):
        md.append(f"| {name} | {API.get(name, '')} | {ms if ms is not None else '-'} | {jit or ''} | {hit:,} / {read:,} | {scans} | {disk} |")
    md += ["", "## 인덱스·쿼리 후보 (적기만 했어요. 서비스 코드·마이그레이션은 바꾸지 않았어요)", ""]
    slow_gather = [n for n, plan in parts.items() if (g := num(r"Gather(?: Merge)?\s+\(cost=[^)]*\) \(actual time=([\d]+(?:\.\d+)?)\.\.", plan)) and g > 50]
    if slow_gather:
        hints.append(f"병렬 실행: {', '.join(slow_gather)}는 병렬 작업자를 띄우는 데 시간 대부분을 써요(노드 시간은 몇 ms) "
                     "→ 작은 표에는 `max_parallel_workers_per_gather = 0`이나 `parallel_setup_cost`를 올려 다시 재 보기(로컬 Docker에서 특히 커요)")
    if any(r[2] and r[1] and r[2] > r[1] / 2 for r in rows):
        hints.append("JIT: 실행 시간의 절반 넘게 JIT 컴파일이에요(비용 추정이 큰 쿼리) → `jit = off`(DB 파라미터)로 다시 재 보기")
    md += [f"- {h}" for h in hints] or ["- 없음"]
    (out / "summary.md").write_text("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
