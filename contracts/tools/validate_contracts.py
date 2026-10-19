"""Offline contract checks. Source authenticity is the extraction verifier's responsibility."""

from __future__ import annotations

import argparse
import calendar
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://mujuthaek.kr/contracts/"
SCHEMAS = {}
for folder in ("schemas", "events"):
    for path in (ROOT / folder).glob("*.json"):
        value = json.loads(path.read_text(encoding="utf-8"))
        if "$schema" in value:
            SCHEMAS[f"{folder}/{path.name}"] = value
REGISTRY = Registry().with_resources((s["$id"], Resource.from_contents(s)) for s in SCHEMAS.values())
# Per-attribute unit/operator/value rules live in the shared dictionary, not in eligibility.v1.json.
ATTRIBUTE_DICTIONARY = json.loads((ROOT / "schemas/eligibility-attributes.v1.json").read_text(encoding="utf-8"))
ATTRIBUTES = {a["code"]: a for a in ATTRIBUTE_DICTIONARY["attributes"]}
LIST_OPERATORS = {"IN", "NOT_IN", "BETWEEN"}
KST = timezone(timedelta(hours=9))
SECTIONS = ("notice", "locations", "schedules", "supplyGroups", "eligibility", "selectionRules", "requiredDocuments", "guidance")
CRITICAL_SECTIONS = {"notice", "eligibility"}  # 판정·목록에 직접 쓰는 섹션 (2026-10-15 검수 등급 결정)
# Source claims with field-level confidence/evidence, excluding IDs and operational metadata.
CLAIM_KEYS = {
    "title",
    "publisher",
    "field",
    "supplyType",
    "announcedOn",
    "description",
    "targetGroups",
    "region",
    "buildingName",
    "complexName",
    "address",
    "regionCode",
    "rawLabel",
    "channels",
    "startsAt",
    "endsAt",
    "endCondition",
    "applicationUrl",
    "applicationMode",
    "priorityRank",
    "value",
    "amountKrw",
    "sourceAmount",
    "sourceUnit",
    "taxTreatment",
    "vatRate",
    "billingPeriod",
    "housingTypeCode",
    "buildingUse",
    "exclusiveAreaSqm",
    "exclusiveAreaRange",
    "amountRangeKrw",
    "unitLabel",
    "floor",
    "commonAreaSqm",
    "initialMonths",
    "renewalMonths",
    "maxMonths",
    "originalText",
    "appliesTo",
    "referenceTime",
    "points",
    "maxPoints",
    "order",
    "name",
    "submissionTime",
    "basis",
    "phone",
    "url",
}



def _section(entry):
    return entry["target"].lstrip("/").split("/", 1)[0]


READY_STATUSES = {"PASSED", "PARTIALLY_PASSED"}
SETTLED_REASONS = {"NOT_IN_SOURCE", "NOT_APPLICABLE", "TO_BE_ANNOUNCED"}  # 원문이 없다·해당 없다·추후 통보라고 확인된 빈칸
# 부분 완료로도 보류할 수 없는 곳: 공고 정체성·입력·원본 문서. 여기에 문제가 있으면 공고 전체가 검수입니다
BLOCKING_TOP = {"notice", "input", "documents", "sourceReferences", "evidence", "quality", ""}


def under_held(path, held):
    return any(path == target or path.startswith(target + "/") for target in held)


def _held_path(data, path):
    """검사 위치 → 보류 범위와 비교할 위치. 섹션 coverage는 그 섹션, 누락 기록은 그 누락이 가리키는 칸."""
    path = path.split(" ", 1)[0]
    if path.startswith("/quality/coverage/"):
        return "/" + path.split("/")[3]
    if path.startswith("/quality/missingFields/"):
        try:
            return resolve(data, path)["target"]
        except (KeyError, IndexError, TypeError, ValueError):
            return path
    return path

def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def walk(value, path=""):
    if isinstance(value, dict):
        yield path, value
        for key, child in value.items():
            yield from walk(child, f"{path}/{key.replace('~', '~0').replace('/', '~1')}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk(child, f"{path}/{index}")


def resolve(value, pointer):
    if pointer == "":
        return value
    for token in pointer.lstrip("/").split("/"):
        token = token.replace("~1", "/").replace("~0", "~")
        if isinstance(value, list):
            if not token.isdigit() or (len(token) > 1 and token.startswith("0")):
                raise KeyError(pointer)
            value = value[int(token)]
        else:
            value = value[token]
    return value


def claim_paths(data):
    result = set()
    for section in SECTIONS:
        for path, node in walk(data[section], f"/{section}"):
            if "evidenceIds" not in node:
                continue
            for key, value in node.items():
                if key in CLAIM_KEYS and value is not None and value != []:
                    result.add(f"{path}/{key}")
    return result


def temporal_bounds(value):
    if value is None:
        return None
    precision, raw = value["precision"], value["value"]
    if precision == "MINUTE":
        point = datetime.fromisoformat(raw)
        return point, point
    if precision == "MONTH":
        year, month = map(int, raw.split("-"))
        start = datetime(year, month, 1, tzinfo=KST)
        end = start + timedelta(days=calendar.monthrange(year, month)[1]) - timedelta(microseconds=1)
    else:
        start = datetime.fromisoformat(raw).replace(tzinfo=KST)
        end = start + timedelta(days=1) - timedelta(microseconds=1)
    return start, end


def validate(data, schema_name):
    schema = SCHEMAS[schema_name]
    validator = Draft202012Validator(schema, registry=REGISTRY, format_checker=FormatChecker())
    errors = [
        f"SCHEMA /{'/'.join(map(str, error.absolute_path))}: {error.message}" for error in validator.iter_errors(data)
    ]
    if errors:
        return errors
    errors = attribute_errors(data) + region_errors(data)
    if schema_name in {"schemas/eligibility.v1.json", "schemas/notice-common.v1.json"}:
        return errors  # References require a complete enclosing notice; use extracted/standard validation for those.
    if schema_name.startswith("events/"):
        return errors + event_errors(data["payload"])
    ready = data.get("publicationState") == "APPROVED" or data["quality"]["verification"]["status"] in READY_STATUSES
    return errors + semantic_errors(data, ready)


def event_errors(payload):
    """extraction.completed: 바깥 칸과 상세 notice가 같은 공고·버전·조건을 가리키는지, AUTO_PASSED면 완료 조건을 만족하는지."""
    errors = []
    notice = payload.get("notice")
    if notice is None:
        return errors
    errors += semantic_errors(notice, ready=payload["reviewStatus"] in {"AUTO_PASSED", "PARTIALLY_PASSED"})
    held = [h["target"] for h in notice["quality"].get("heldItems", [])]
    if payload.get("heldItems", []) != notice["quality"].get("heldItems", []):
        errors.append("EVENT_HELD_ITEMS_CONFLICT /payload/heldItems")
    for index, condition in enumerate(payload["conditions"]):
        if under_held(f"/eligibility/{index}", held) and not condition["inReview"]:
            errors.append(f"HELD_CONDITION_NOT_IN_REVIEW /payload/conditions/{index}")
    incoming = notice["input"]
    if (payload["rawPostId"], payload["rawVersion"], payload["field"]) != (
        incoming["rawPostId"],
        incoming["rawVersion"],
        incoming["field"],
    ):
        errors.append("EVENT_NOTICE_IDENTITY_CONFLICT /payload")
    if [c["id"] for c in payload["conditions"]] != [e["id"] for e in notice["eligibility"]]:
        errors.append("EVENT_CONDITIONS_CONFLICT /payload/conditions")
    region = payload.get("regionCode")
    codes = [loc["regionCode"] for loc in notice["locations"] if loc["regionCode"]]
    if region and codes and not any(code.startswith(region) for code in codes):
        errors.append("EVENT_REGION_CONFLICT /payload/regionCode")
    return errors


def region_code_ok(code):
    """법정동코드 10자리 = 시도 2 + 시군구 3 + 읍면동 3 + 리 2. 모르는 하위 단계는 0으로 채우고, 0 뒤에 다시 값이 올 수 없습니다."""
    parts = (code[:2], code[2:5], code[5:8], code[8:])
    if parts[0] == "00":
        return False
    zero_seen = False
    for part in parts[1:]:
        if set(part) == {"0"}:
            zero_seen = True
        elif zero_seen:
            return False
    return True


def region_prefix(code):
    """하위 단계 0을 뺀 의미 있는 앞부분. 조상 코드의 앞부분은 자손 코드의 앞부분입니다."""
    for start, end in ((8, 10), (5, 8), (2, 5)):
        if code[start:end] != "0" * (end - start):
            return code[:end]
    return code[:2]


def region_errors(data):
    errors = []
    for path, node in walk(data):
        values = [("regionCode", node.get("regionCode"))]
        values += [(f"regionCodes/{i}", c) for i, c in enumerate(node.get("regionCodes") or [])]
        for key, code in values:
            if isinstance(code, str) and not region_code_ok(code):
                errors.append(f"REGION_CODE_HIERARCHY {path}/{key}")
    return errors


def region_covers(ancestor, code):
    return code.startswith(region_prefix(ancestor))


def value_matches(spec, value):
    kind = spec["valueType"]
    if kind == "boolean":
        return isinstance(value, bool)
    if kind == "string":
        if not isinstance(value, str) or not value:
            return False
        if "pattern" in spec and not re.fullmatch(spec["pattern"], value):
            return False
        if spec.get("regionCode") and not region_code_ok(value):
            return False
        return "enum" not in spec or value in spec["enum"]
    if isinstance(value, bool) or not isinstance(value, int if kind == "integer" else (int, float)):
        return False
    return "minimum" not in spec or value >= spec["minimum"]


def attribute_errors(data):
    errors = []
    for path, node in walk(data):
        if node.get("kind") != "ATOMIC":
            continue
        spec = ATTRIBUTES.get(node["attribute"])
        if spec is None:
            errors.append(f"ATTRIBUTE_UNKNOWN {path}/attribute")
            continue
        if node["unit"] != spec["unit"]:
            errors.append(f"ATTRIBUTE_UNIT_CONFLICT {path}/unit")
        if node["operator"] not in spec["operators"]:
            errors.append(f"ATTRIBUTE_OPERATOR_CONFLICT {path}/operator")
        value = node["value"]
        values = value if node["operator"] in LIST_OPERATORS and isinstance(value, list) else [value]
        if not all(value_matches(spec, x) for x in values):
            errors.append(f"ATTRIBUTE_VALUE_INVALID {path}/value")
        if spec["benchmarkRequired"] and node["benchmark"] is None:
            errors.append(f"BENCHMARK_REQUIRED {path}/benchmark")
    return errors


def dictionary_errors():
    errors = []
    atomic = SCHEMAS["schemas/eligibility.v1.json"]["$defs"]["atomic"]["properties"]
    codes = [a["code"] for a in ATTRIBUTE_DICTIONARY["attributes"]]
    if len(set(codes)) != len(codes):
        errors.append("DICTIONARY_DUPLICATE_CODE")
    for spec in ATTRIBUTE_DICTIONARY["attributes"]:
        if not re.fullmatch(atomic["attribute"]["pattern"], spec["code"]):
            errors.append(f"DICTIONARY_CODE_FORMAT {spec['code']}")
        if spec["unit"] not in atomic["unit"]["enum"]:
            errors.append(f"DICTIONARY_UNIT {spec['code']}")
        if not set(spec["operators"]) <= set(atomic["operator"]["enum"]):
            errors.append(f"DICTIONARY_OPERATOR {spec['code']}")
        if spec["valueType"] not in {"integer", "number", "string", "boolean"}:
            errors.append(f"DICTIONARY_VALUE_TYPE {spec['code']}")
    return errors


def semantic_errors(data, ready=False):
    errors = []
    quality = data["quality"]
    partial = quality["verification"]["status"] == "PARTIALLY_PASSED"
    held = [h["target"] for h in quality.get("heldItems", [])] if partial else []

    def error(code, path):
        errors.append(f"{code} {path or '/'}")

    def ready_error(code, path):
        """완료 조건 위반. 부분 완료(PARTIALLY_PASSED)면 보류한 항목·섹션 안의 위반은 허용합니다(그 범위는 쓰지 않음)."""
        if not under_held(_held_path(data, path), held):
            error(code, path)

    nodes = list(walk(data))
    ids = {}
    for path, node in nodes:
        if "id" in node:
            if node["id"] in ids:
                error("DUPLICATE_ID", path)
            ids[node["id"]] = path
    locations = {x["id"]: x for x in data["locations"]}
    groups = {x["id"]: x for x in data["supplyGroups"]}
    documents = {x["id"]: x for x in data["documents"]}
    evidence = {x["id"]: x for x in data["evidence"]}
    counts = {node["id"]: node for _, node in nodes if "aggregationRole" in node}
    schedules = {x["id"]: x for x in data["schedules"]}
    for path, node in nodes:
        for evidence_id in node.get("evidenceIds", []):
            if evidence_id not in evidence:
                error("DANGLING_EVIDENCE", path)
        if "locationId" in node and node["locationId"] is not None and node["locationId"] not in locations:
            error("DANGLING_LOCATION", path)
        if "parentLocationId" in node:
            parent_id = node["parentLocationId"]
            if parent_id is not None and (
                parent_id not in locations
                or node["kind"] != "BUILDING"
                or locations.get(parent_id, {}).get("kind") != "COMPLEX"
            ):
                error("INVALID_LOCATION_PARENT", path)
        if "scope" in node:
            scope = node["scope"]
            if any(x not in locations for x in scope["locationIds"]) or any(
                x not in groups for x in scope["supplyGroupIds"]
            ):
                error("DANGLING_SCOPE", path)
            for group_id in scope["supplyGroupIds"]:
                location_id = groups.get(group_id, {}).get("locationId")
                if scope["locationIds"] and location_id not in scope["locationIds"]:
                    error("SCOPE_LOCATION_CONFLICT", path)
            if ready and scope["resolution"] != "RESOLVED":
                ready_error("UNRESOLVED_SCOPE", path)
        if node.get("kind") == "ATOMIC":
            value = node["value"]
            if node["operator"] == "BETWEEN" and value[0] > value[1]:
                error("REVERSED_RANGE", path)
            benchmark = node["benchmark"]
            if (
                node["attribute"] == "INCOME_BENCHMARK_PERCENT"
                and benchmark
                and (
                    benchmark["householdScope"] is None
                    or (benchmark["referenceYear"] is None and benchmark["referenceVersion"] is None)
                )
            ):
                error("INCOMPLETE_INCOME_BENCHMARK", path)
        if "startsAt" in node:
            is_application = "APPLICATION" in node["kinds"]
            if is_application == (node["applicationMode"] == "NOT_APPLICABLE"):
                error("APPLICATION_MODE_CONFLICT", path)
            start, end = temporal_bounds(node["startsAt"]), temporal_bounds(node["endsAt"])
            if start and end and start[0] > end[1]:
                error("REVERSED_SCHEDULE", path)
        # 범위 값(선택 칸): 원문이 범위로만 적었을 때만 쓰고 단일 값과 함께 쓰지 않습니다
        for single, ranged in (("exclusiveAreaSqm", "exclusiveAreaRange"), ("amountKrw", "amountRangeKrw")):
            span = node.get(ranged) if single in node else None
            if span is not None:
                if span["min"] > span["max"]:
                    error("RANGE_REVERSED", f"{path}/{ranged}")
                if node[single] is not None:
                    error("RANGE_WITH_SINGLE_VALUE", f"{path}/{ranged}")
        if "amountKrw" in node:
            expected_period = {"MONTHLY_RENT": "MONTHLY", "ANNUAL_RENT": "ANNUAL"}.get(node["kind"])
            if expected_period and node["billingPeriod"] not in {expected_period, "UNKNOWN"}:
                error("PRICE_PERIOD_CONFLICT", path)
            if node["taxTreatment"] in {"EXEMPT", "NOT_APPLICABLE", "UNKNOWN"} and node["vatRate"] is not None:
                error("VAT_CONFLICT", path)
        if (
            "initialMonths" in node
            and node["initialMonths"]
            and node["maxMonths"]
            and node["initialMonths"] > node["maxMonths"]
        ):
            error("LEASE_TERM_CONFLICT", path)
        if (
            node.get("kind") == "SCORING"
            and node["points"] is not None
            and node["maxPoints"] is not None
            and node["points"] > node["maxPoints"]
        ):
            error("SCORE_EXCEEDS_MAX", path)
        if ready and node.get("exceptionsStatus") == "NOT_REVIEWED":
            ready_error("EXCEPTIONS_NOT_REVIEWED", path)
        for key, value in node.items():
            if key.endswith("Url") and value is not None:
                parsed = urlsplit(value)
                secret_names = {"servicekey", "apikey", "token", "access_token", "signature", "x-amz-signature"}
                params = {name.lower() for name, _ in parse_qsl(parsed.query)}
                if parsed.username or parsed.password or params & secret_names:
                    error("PRIVATE_URL", f"{path}/{key}")
    for index, item in enumerate(data["evidence"]):
        path = f"/evidence/{index}"
        document = documents.get(item["sourceId"])
        if document is None:
            error("DANGLING_SOURCE", path)
            continue
        locator = item["locator"]
        expected_kind = {"PDF_PAGE": "PDF", "TEXT_RANGE": "TEXT", "UNIT_ROW": "UNITS"}[locator["kind"]]
        if document["kind"] != expected_kind:
            error("LOCATOR_SOURCE_CONFLICT", path)
        if locator["kind"] == "PDF_PAGE":
            if document["pageCount"] is not None and locator["page"] > document["pageCount"]:
                error("PAGE_OUT_OF_RANGE", path)
            if ready and document["pageCount"] is None:
                error("PAGE_COUNT_REQUIRED", path)
        if locator["kind"] == "TEXT_RANGE" and locator["start"] >= locator["end"]:
            error("EMPTY_TEXT_RANGE", path)
    for count_id, count in counts.items():
        parent_id = count["parentCountId"]
        if parent_id is not None:
            parent = counts.get(parent_id)
            if parent is None or parent["aggregationRole"] != "TOTAL" or parent_id == count_id:
                error("INVALID_COUNT_PARENT", f"/counts/{count_id}")
            elif parent["kind"] != count["kind"]:
                error("COUNT_CONCEPT_CONFLICT", f"/counts/{count_id}")
        if count["aggregationComplete"]:
            children = [c for c in counts.values() if c["parentCountId"] == count_id]
            if (
                count["aggregationRole"] != "TOTAL"
                or not children
                or count["value"] is None
                or any(c["value"] is None for c in children)
            ):
                error("INCOMPLETE_COUNT_COMPONENTS", f"/counts/{count_id}")
            elif sum(c["value"] for c in children) != count["value"]:
                error("COUNT_SUM_MISMATCH", f"/counts/{count_id}")
    references = data["sourceReferences"]
    ref_keys = [(r["sourceCode"], r["rawPostId"], r["rawVersion"]) for r in references]
    if len(set(ref_keys)) != len(ref_keys):
        error("DUPLICATE_SOURCE_REFERENCE", "/sourceReferences")
    for index, item in enumerate(references):
        if item["supersedesPostId"] == item["rawPostId"]:
            error("SELF_SUPERSEDES", f"/sourceReferences/{index}")
        if item["kind"] == "NOTICE" and item["supersedesPostId"] is not None:
            error("NOTICE_HAS_SUPERSEDES", f"/sourceReferences/{index}")
        if ready and item["kind"] != "NOTICE" and item["supersedesPostId"] is None:
            error("UNLINKED_RELATED_NOTICE", f"/sourceReferences/{index}")
    if "input" in data:
        incoming = data["input"]
        if not any(
            r["rawPostId"] == incoming["rawPostId"] and r["rawVersion"] == incoming["rawVersion"] for r in references
        ):
            error("INPUT_REFERENCE_MISMATCH", "/input")
        if data["notice"]["field"] is not None and incoming["field"] != data["notice"]["field"]:
            error("INPUT_FIELD_MISMATCH", "/input/field")
    if "noticeVersion" in data:
        expected_previous = data["noticeVersion"] - 1 if data["noticeVersion"] > 1 else None
        if data["previousNoticeVersion"] != expected_previous:
            error("NOTICE_VERSION_CONFLICT", "/previousNoticeVersion")
        if data["noticeVersion"] == 1 and data["changedFields"]:
            error("INITIAL_VERSION_HAS_CHANGES", "/changedFields")
        if data["noticeVersion"] > 1 and not data["changedFields"]:
            error("UPDATED_VERSION_WITHOUT_CHANGES", "/changedFields")
        summary = data["applicationSummary"]
        basis = summary["basisScheduleIds"]
        if any(x not in schedules or "APPLICATION" not in schedules[x]["kinds"] for x in basis):
            error("INVALID_APPLICATION_BASIS", "/applicationSummary")
        if summary["startsOn"] and summary["endsOn"] and summary["startsOn"] > summary["endsOn"]:
            error("REVERSED_APPLICATION_SUMMARY", "/applicationSummary")
        if summary["status"] in {"UPCOMING", "OPEN", "ROLLING"} and not basis:
            error("APPLICATION_BASIS_REQUIRED", "/applicationSummary")
        if summary["status"] == "ROLLING" and not any(
            schedules[x]["applicationMode"] == "ROLLING" for x in basis if x in schedules
        ):
            error("ROLLING_WITHOUT_EXPLICIT_CONDITION", "/applicationSummary")
        codes = set(data["regionCodes"])
        # 위치 코드는 색인의 같은 코드나 상위 코드(예: 서울 전체 1100000000)로 찾을 수 있어야 합니다.
        if any(
            x["regionCode"] is not None and not any(region_covers(c, x["regionCode"]) for c in codes)
            for x in data["locations"]
        ):
            error("LOCATION_REGION_NOT_INDEXED", "/regionCodes")
        if data["regionScope"] == "UNKNOWN" and codes:
            error("UNKNOWN_REGION_WITH_CODES", "/regionCodes")
    for key, entry in quality["coverage"].items():
        if key != "notice":
            if entry["status"] == "EXTRACTED" and not data[key]:
                error("EMPTY_EXTRACTED_SECTION", f"/quality/coverage/{key}")
            if entry["status"] in {"NOT_APPLICABLE", "NOT_IN_SOURCE", "NOT_ATTEMPTED", "FAILED"} and data[key]:
                error("COVERAGE_CONTENT_CONFLICT", f"/quality/coverage/{key}")
        # 판정에 쓰는 섹션은 완전해야 합니다. 그 밖의 섹션은 근거를 못 찾은 항목을 빼고(PARTIAL) quality.notes에 남긴 채 완료할 수 있습니다
        incomplete = {"PARTIAL", "NOT_ATTEMPTED", "FAILED"} if key in CRITICAL_SECTIONS else {"NOT_ATTEMPTED", "FAILED"}
        if ready and entry["status"] in incomplete:
            ready_error("INCOMPLETE_EXTRACTION", f"/quality/coverage/{key}")
    for collection in ["missingFields", "reviewIssues", "notes"]:
        seen = set()
        for index, entry in enumerate(quality[collection]):
            if collection == "missingFields" and entry["target"] in seen:
                error("DUPLICATE_FIELD_RECORD", f"/quality/{collection}/{index}")
            seen.add(entry["target"])
            try:
                target_value = resolve(data, entry["target"])
            except (KeyError, IndexError, TypeError, ValueError):
                error("DANGLING_FIELD_POINTER", f"/quality/{collection}/{index}")
                continue
            if collection == "missingFields" and target_value is not None and target_value != []:
                error("MISSING_FIELD_HAS_VALUE", f"/quality/{collection}/{index}")
            if ready and collection == "missingFields" and entry["reason"] not in SETTLED_REASONS and _section(entry) in CRITICAL_SECTIONS:
                ready_error("UNRESOLVED_MISSING_FIELD", f"/quality/{collection}/{index}")
    comparison = quality["verification"]["apiComparison"]
    identity = comparison["referenceIdentity"]
    if identity is not None and not any(all(r[k] == identity[k] for k in identity) for r in references):
        error("API_REFERENCE_IDENTITY_CONFLICT", "/quality/verification/apiComparison/referenceIdentity")
    statuses = [x["status"] for x in comparison["fields"]]
    if comparison["status"] == "MATCHED" and ("MATCH" not in statuses or "MISMATCH" in statuses):
        error("INVALID_API_MATCH", "/quality/verification/apiComparison")
    if comparison["status"] == "MISMATCH" and "MISMATCH" not in statuses:
        error("INVALID_API_MISMATCH", "/quality/verification/apiComparison")
    if comparison["status"] == "NO_COMPARABLE_FIELDS" and any(s != "NOT_COMPARABLE" for s in statuses):
        error("API_COMPARABLE_FIELD_FOUND", "/quality/verification/apiComparison")
    for index, field in enumerate(comparison["fields"]):
        if field["status"] == "MATCH" and (
            field["expected"] is None
            or type(field["expected"]) is not type(field["actual"])
            or field["expected"] != field["actual"]
        ):
            error("FALSE_API_MATCH", f"/quality/verification/apiComparison/fields/{index}")
        if field["status"] == "MISMATCH" and field["expected"] == field["actual"]:
            error("FALSE_API_MISMATCH", f"/quality/verification/apiComparison/fields/{index}")
        if field["status"] == "NOT_COMPARABLE" and field["reason"] is None:
            error("COMPARISON_REASON_REQUIRED", f"/quality/verification/apiComparison/fields/{index}")
        try:
            if resolve(data, field["target"]) != field["actual"]:
                error("API_ACTUAL_VALUE_CONFLICT", field["target"])
        except (KeyError, IndexError, TypeError, ValueError):
            error("DANGLING_API_TARGET", field["target"])
    for index, item in enumerate(quality.get("heldItems", [])):
        try:
            resolve(data, item["target"])
        except (KeyError, IndexError, TypeError, ValueError):
            error("DANGLING_HELD_TARGET", f"/quality/heldItems/{index}")
        if item["target"].lstrip("/").split("/", 1)[0] not in BLOCKING_TOP and item["target"].startswith("/"):
            continue
        error("HELD_TARGET_NOT_ALLOWED", f"/quality/heldItems/{index}")
    # 함께 쓰는 모집 물량(allocationPoolId): 같은 ID는 같은 종류·값이어야 합니다(합산하지 않는 한 물량)
    pools = {}
    for path, node in nodes:
        if "aggregationRole" in node and node.get("allocationPoolId"):
            first = pools.setdefault(node["allocationPoolId"], (node["kind"], node["value"]))
            if first != (node["kind"], node["value"]):
                error("ALLOCATION_POOL_CONFLICT", path)
    if ready:
        if quality["verification"]["status"] not in READY_STATUSES:
            error("VERIFICATION_REQUIRED", "/quality/verification")
        if partial and not held:
            error("HELD_ITEMS_REQUIRED", "/quality/heldItems")
        for index, issue in enumerate(quality["reviewIssues"]):
            if not partial:
                error("UNRESOLVED_REVIEW_ISSUES", "/quality/reviewIssues")
                break
            if not under_held(_held_path(data, issue["target"]), held):
                error("REVIEW_ISSUE_NOT_HELD", f"/quality/reviewIssues/{index}")
        if any(data["notice"][k] is None for k in ("title", "publisher", "field", "supplyType")):
            error("MISSING_NOTICE_CORE", "/notice")
        critical_unknowns = ["/notice/announcedOn"]
        critical_unknowns += [f"/locations/{i}/regionCode" for i in range(len(data["locations"]))]
        critical_unknowns += [
            f"/supplyGroups/{i}/prices/{j}/amountKrw"
            for i, group in enumerate(data["supplyGroups"])
            for j, price in enumerate(group["prices"])
            if price.get("amountRangeKrw") is None  # 범위로 적은 가격은 그 범위가 값입니다
        ]
        explained = {x["target"] for x in quality["missingFields"]}
        for pointer in critical_unknowns:
            if resolve(data, pointer) is None and pointer not in explained:
                ready_error("UNKNOWN_REASON_REQUIRED", pointer)
    return errors


def check_all():
    failures = dictionary_errors()
    for schema in SCHEMAS.values():
        Draft202012Validator.check_schema(schema)
    manifest = load(ROOT / "examples" / "manifest.json")
    for case in manifest["examples"]:
        data = load(ROOT / "examples" / case["file"])
        errors = validate(data, case["schema"])
        if errors:
            failures.extend(f"{case['file']}: {e}" for e in errors)
        else:
            print(f"PASS {case['file']}")
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", nargs="?")
    parser.add_argument("--schema", choices=sorted(SCHEMAS), default="schemas/standard-notice.v1.json")
    args = parser.parse_args()
    failures = validate(load(args.file), args.schema) if args.file else check_all()
    for error in failures:
        print(error)
    if not failures:
        print("Contract validation passed (offline; no API calls).")
    return bool(failures)


if __name__ == "__main__":
    raise SystemExit(main())
