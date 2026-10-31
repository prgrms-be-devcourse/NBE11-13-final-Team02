"""동시성 시험 결과 폴더(raw/·시나리오 JSON)를 시나리오별 합격/불합격과 summary.md로 정리해요.

  python3 perf/concurrency/report.py <결과 폴더> <동시 수> <회 수>
"""

import json
import sys
from collections import Counter
from pathlib import Path

TYPES = ["HAPPY_HOUSING", "NATIONAL_RENTAL", "PUBLIC_SALE", "PERMANENT_RENTAL"]  # k6/at-once.js와 같게


def load(path):
    p = Path(path)
    return json.loads(p.read_text()) if p.exists() else None


def codes_of(raw, s, r):
    k6 = load(raw / f"s{s}_r{r}.json") or {}
    return {k: int(v) for k, v in (k6.get("codes") or {}).items()}


def server_errors(codes):
    return sum(v for k, v in codes.items() if k.startswith("5") or k == "other")


def ok_count(codes):
    return sum(v for k, v in codes.items() if k.startswith("2"))


def scenario_k6(out, s, vus, rounds):
    raw = out / "raw"
    if not (raw / f"s{s}_r1.db.json").exists():
        return None
    regions = load(raw / "s3_regions.json") or []
    rows, total = [], Counter()
    for r in range(1, rounds + 1):
        codes = codes_of(raw, s, r)
        total.update(codes)
        db = load(raw / f"s{s}_r{r}.db.json") or {}
        if s == 1:
            checks = {
                "모든 응답 2xx": ok_count(codes) == vus,
                "5xx 없음": server_errors(codes) == 0,
                "DB 한 줄": db.get("db_rows") == 1,
                "목록에도 한 번": db.get("api_listed") == 1,
            }
        elif s == 2:
            checks = {
                "모든 응답 2xx": ok_count(codes) == vus,
                "5xx 없음": server_errors(codes) == 0,
                "DB 0 또는 1줄(중복 없음)": db.get("db_rows") in (0, 1),
                "목록과 DB가 같음": db.get("api_listed") == db.get("db_rows"),
            }
        else:
            checks = s3_checks(db, codes, vus, regions)
        passed = all(checks.values())
        rows.append({"round": r, "pass": passed, "codes": codes, "checks": checks, "db": db})
    name = {1: "1 같은 회원·같은 공고 관심 등록(PUT)", 2: "2 관심 등록·해제 섞기", 3: "3 알림 설정·관심 조건 동시 저장(서로 다른 값)"}[s]
    return {"scenario": name, "pass": all(x["pass"] for x in rows), "codes_total": dict(sorted(total.items())), "rounds": rows}


def s3_checks(db, codes, vus, regions):
    st = (db.get("db") or {}).get("settings") or {}
    hs = (db.get("db") or {}).get("housing") or {}
    api = db.get("api") or {}
    # 알림 설정: VU i가 보낸 값은 (i%2==0)×2, (i%3==0)×2, (i%5==0)×2. 저장된 값이 어떤 한 VU의 값과 통째로 같아야 해요
    settings_from_one = bool(st) and any(
        st["new_notice"] == st["new_support"] == (i % 2 == 0)
        and st["deadline"] == st["correction"] == (i % 3 == 0)
        and st["follow_up"] == st["quiet_enabled"] == (i % 5 == 0)
        for i in range(vus)
    )
    # 관심 조건: 지역 REGIONS[i%40], 종류 TYPES[i%4], 전국 포함 i%2==0 이 같은 i에서 와야 해요
    housing_from_one = False
    if hs and len(hs.get("region_codes") or []) == 1 and len(hs.get("notice_types") or []) == 1 and regions:
        r = regions.index(hs["region_codes"][0]) if hs["region_codes"][0] in regions else -1
        t = TYPES.index(hs["notice_types"][0]) if hs["notice_types"][0] in TYPES else -1
        housing_from_one = any(
            i % len(regions) == r and i % 4 == t and (i % 2 == 0) == hs["include_nationwide"] for i in range(vus)
        )
    a_set = api.get("settings") or {}
    a_house = api.get("housing") or {}
    api_same = bool(st) and bool(hs) and (
        [a_set.get(k) for k in ("newNotice", "newSupport", "deadline", "correction", "followUp", "quietEnabled")]
        == [st[k] for k in ("new_notice", "new_support", "deadline", "correction", "follow_up", "quiet_enabled")]
        and a_house.get("regionCodes") == hs.get("region_codes")
        and a_house.get("noticeTypes") == hs.get("notice_types")
        and a_house.get("includeNationwide") == hs.get("include_nationwide")
    )
    return {
        "모든 응답 2xx": ok_count(codes) == 2 * vus,
        "5xx 없음": server_errors(codes) == 0,
        "알림 설정이 한 요청 값 그대로": settings_from_one,
        "관심 조건이 한 요청 값 그대로": housing_from_one,
        "조회(API)와 DB가 같음": api_same,
    }


def scenario_file(out, name, title):
    data = load(out / f"{name}.json")
    if data is None:
        return {"scenario": title, "pass": None, "note": "실행 실패(로그 확인)", "rounds": []}
    return data


def main(out, vus, rounds):
    out = Path(out)
    results = []
    for s in (1, 2, 3):
        r = scenario_k6(out, s, vus, rounds)
        if r:
            results.append(r)
    if (out / "raw-4-1.log").exists():
        results.append(scenario_file(out, "4-1_ingestion", "4-1 공고 저장 리스너: 같은 eventId 동시 수신"))
    if (out / "raw-4-2.log").exists():
        results.append(scenario_file(out, "4-2_notification", "4-2 알림 요청 리스너: 같은 eventId 동시 수신"))
    if (out / "raw-5.log").exists():
        results.append(scenario_file(out, "5_review", "5 관리자 동시 승인·수정"))
    six = load(out / "raw-6.json")
    if six is not None:
        results.append(
            {
                "scenario": "6 관심 수 같은 집계",
                "pass": None if not six else False,
                "note": "집계 칸이 없어 건너뛰었어요 (관심 수는 그때그때 세요)" if not six else f"집계 칸 {six}: 시험을 더해야 해요",
                "rounds": [],
            }
        )
    for r in results:
        (out / f"{r['scenario'].split()[0]}.json").write_text(json.dumps(r, ensure_ascii=False, indent=2))

    md = [f"# 동시성 시험 ({out.name})", "", f"동시 {vus}개를 한꺼번에 출발 × {rounds}회. 응답과 끝난 뒤 DB로 판단해요.", ""]
    md += ["| 시나리오 | 결과 | 합격 회 | 응답·결과 합계 | DB·비고 |", "|---|---|---|---|---|"]
    for r in results:
        verdict = "건너뜀" if r["pass"] is None and "건너뛰" in r.get("note", "") else ("합격" if r["pass"] else "불합격")
        rs = r.get("rounds") or []
        passed = f"{sum(1 for x in rs if x.get('pass'))}/{len(rs)}" if rs else "-"
        total = r.get("codes_total")
        if total is None and rs:
            c = Counter()
            for x in rs:
                c.update(x.get("outcomes") or {})
            total = dict(sorted(c.items()))
        note = r.get("note") or ""
        if rs:
            failed = [x for x in rs if not x.get("pass")]
            sample = failed[0] if failed else rs[0]
            note = note or json.dumps(sample.get("checks") or sample.get("db"), ensure_ascii=False)
        md.append(f"| {r['scenario']} | {verdict} | {passed} | {json.dumps(total or {}, ensure_ascii=False)} | {note} |")
    (out / "summary.md").write_text("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]))
