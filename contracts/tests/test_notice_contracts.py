"""Regression cases for contract boundaries, incomplete extraction and unsafe handoff."""

import copy
import hashlib
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import validate_contracts as contract


class NoticeContractTest(unittest.TestCase):
    def setUp(self):
        self.notice = contract.load(ROOT / "examples/housing.standard.json")
        self.event = contract.load(ROOT / "examples/extraction.completed.json")

    def assert_valid(self, value=None, schema="schemas/standard-notice.v1.json"):
        self.assertEqual([], contract.validate(self.notice if value is None else value, schema))

    def assert_invalid(self, code, value=None, schema="schemas/standard-notice.v1.json"):
        errors = contract.validate(self.notice if value is None else value, schema)
        self.assertTrue(any(code in error for error in errors), errors)

    def add_api_match(self):
        source = self.notice["sourceReferences"][0]
        comparison = self.notice["quality"]["verification"]["apiComparison"]
        comparison.update(
            status="MATCHED",
            referenceSha256="a" * 64,
            referenceIdentity={k: source[k] for k in ("sourceCode", "sourceNoticeId", "rawPostId", "rawVersion")},
            fields=[
                {
                    "target": "/notice/announcedOn",
                    "sourcePath": "/dsList/0/PAN_DT",
                    "expected": "2026-10-01",
                    "actual": "2026-10-01",
                    "status": "MATCH",
                    "reason": None,
                }
            ],
        )
        return comparison

    def test_schemas_examples_and_offline_references(self):
        self.assertEqual([], contract.check_all())
        ids = {s["$id"] for s in contract.SCHEMAS.values()}
        for schema in contract.SCHEMAS.values():
            for _, node in contract.walk(schema):
                if "$ref" in node and not node["$ref"].startswith("#"):  # "#/..."는 같은 파일 안 참조 (collector 스키마)
                    self.assertIn(node["$ref"].split("#")[0], ids)

    def test_examples_have_actual_fixture_hashes_quotes_and_original_text(self):
        for prefix in ("housing", "business"):
            data = contract.load(ROOT / f"examples/{prefix}.extracted.json")
            raw = (ROOT / "examples/sources" / f"{prefix}.synthetic.txt").read_bytes()
            text = raw.decode("utf-8")
            self.assertEqual(hashlib.sha256(raw).hexdigest(), data["documents"][0]["sha256"])
            for evidence in data["evidence"]:
                locator = evidence["locator"]
                self.assertIn(evidence["quote"], text[locator["start"] : locator["end"]])
            for _, node in contract.walk(data):
                if "originalText" in node:
                    self.assertIn(node["originalText"], text)

    def test_partial_correction_is_sent_only_as_in_review(self):
        partial = contract.load(ROOT / "examples/correction.partial.json")
        self.assert_valid(partial, "schemas/extracted-notice.v1.json")
        payload = self.event["payload"]
        payload.update(
            notice=partial,
            rawPostId=partial["input"]["rawPostId"],
            rawVersion=partial["input"]["rawVersion"],
            regionCode=None,
            conditions=[
                {
                    "id": item["id"],
                    "target": item["appliesTo"],
                    "targetGroups": item["scope"]["targetGroups"],
                    "category": item["category"],
                    "description": item["description"],
                    "originalText": item["originalText"],
                    "referenceTime": item["referenceTime"],
                    "rule": item["rule"],
                    "exceptions": item["exceptions"],
                    "inReview": True,
                    "reviewReasons": ["CORRECTION_PARTIAL"],
                }
                for item in partial["eligibility"]
            ],
        )
        schema = "events/extraction.completed.v1.json"
        self.assert_invalid("SCHEMA", self.event, schema)  # AUTO_PASSED로는 보낼 수 없음
        payload["reviewStatus"] = "IN_REVIEW"
        self.assert_valid(self.event, schema)  # 검수 중 표시와 함께 보낼 수 있음

    def test_partial_correction_cannot_overwrite_approved_notice(self):
        partial = contract.load(ROOT / "examples/correction.partial.json")
        self.notice["quality"] = partial["quality"]
        self.notice["schedules"] = []
        self.assert_invalid("SCHEMA")

    def test_api_absence_allows_independent_source_verification(self):
        self.assert_valid()
        self.assertEqual("SKIPPED_NO_API_REFERENCE", self.notice["quality"]["verification"]["apiComparison"]["status"])

    def test_matching_same_notice_api_reference_is_valid(self):
        self.add_api_match()
        self.assert_valid()

    def test_other_notice_api_response_is_rejected(self):
        self.add_api_match()["referenceIdentity"]["sourceNoticeId"] = "different-notice"
        self.assert_invalid("API_REFERENCE_IDENTITY_CONFLICT")

    def test_other_raw_version_api_response_is_rejected(self):
        self.add_api_match()["referenceIdentity"]["rawVersion"] = 2
        self.assert_invalid("API_REFERENCE_IDENTITY_CONFLICT")

    def test_false_match_does_not_pass(self):
        self.add_api_match()["fields"][0]["expected"] = "2026-10-02"
        self.assert_invalid("FALSE_API_MATCH")

    def test_comparison_actual_must_be_extracted_value(self):
        field = self.add_api_match()["fields"][0]
        field["expected"] = field["actual"] = "2026-10-02"
        self.assert_invalid("API_ACTUAL_VALUE_CONFLICT")

    def test_mismatch_requires_review(self):
        comparison = self.add_api_match()
        comparison.update(status="MISMATCH")
        comparison["fields"][0].update(status="MISMATCH", expected="2026-10-02")
        self.assert_invalid("SCHEMA")

    def test_completed_notice_may_omit_unverified_items_outside_judgment_sections(self):
        # 검수 등급(2026-10-15): 서류·일정·공급 등은 근거를 못 찾은 항목을 빼고 notes에 남긴 채 완료할 수 있습니다
        quality = self.notice["quality"]
        quality["coverage"]["requiredDocuments"] = {"status": "PARTIAL", "reason": "근거를 확인하지 못한 항목을 제외했습니다."}
        quality["notes"].append({"code": "ITEM_DROPPED_NO_EVIDENCE", "target": "/requiredDocuments", "description": "서류 1개를 뺐습니다."})
        self.assert_valid()
        quality["coverage"]["eligibility"] = {"status": "PARTIAL", "reason": "근거를 확인하지 못한 항목을 제외했습니다."}
        self.assert_invalid("INCOMPLETE_EXTRACTION")

    def test_note_pointer_must_resolve(self):
        self.notice["quality"]["notes"].append({"code": "EVIDENCE_QUOTE_NOT_FOUND", "target": "/requiredDocuments/9", "description": "x"})
        self.assert_invalid("DANGLING_FIELD_POINTER")

    def test_dangling_evidence_and_source_are_rejected(self):
        for target, key, value, code in [
            (self.notice["notice"], "evidenceIds", ["missing"], "DANGLING_EVIDENCE"),
            (self.notice["evidence"][0], "sourceId", "missing", "DANGLING_SOURCE"),
        ]:
            with self.subTest(code=code):
                previous = target[key]
                target[key] = value
                self.assert_invalid(code)
                target[key] = previous

    def test_duplicate_ids_are_rejected(self):
        self.notice["supplyGroups"][0]["id"] = "building-a"
        self.assert_invalid("DUPLICATE_ID")

    def test_pdf_physical_page_must_exist(self):
        self.notice["documents"][0].update(kind="PDF", pageCount=10)
        self.notice["evidence"][0]["locator"] = {"kind": "PDF_PAGE", "page": 11, "printedPage": "9"}
        self.assert_invalid("PAGE_OUT_OF_RANGE")

    def test_page_number_zero_is_rejected(self):
        self.notice["documents"][0].update(kind="PDF", pageCount=10)
        self.notice["evidence"][0]["locator"] = {"kind": "PDF_PAGE", "page": 0, "printedPage": None}
        self.assert_invalid("SCHEMA")

    def test_text_range_must_be_nonempty(self):
        self.notice["evidence"][0]["locator"].update(start=10, end=5)
        self.assert_invalid("EMPTY_TEXT_RANGE")

    def test_document_and_locator_types_must_match(self):
        self.notice["documents"][0]["kind"] = "PDF"
        self.assert_invalid("LOCATOR_SOURCE_CONFLICT")

    def test_scope_must_refer_to_existing_supply_group(self):
        self.notice["supplyGroups"][0]["scope"]["supplyGroupIds"] = ["nonexistent"]
        self.assert_invalid("DANGLING_SCOPE")

    def test_building_parent_must_be_complex(self):
        self.notice["locations"][0]["parentLocationId"] = "building-a"
        self.assert_invalid("INVALID_LOCATION_PARENT")

    def test_unknown_attribute_stays_unstructured(self):
        rule = self.notice["eligibility"][0]["rule"]
        rule["expression"]["children"][0]["attribute"] = "UNSUPPORTED_ATTRIBUTE"
        self.assert_invalid("ATTRIBUTE_UNKNOWN")
        rule.update(status="UNSTRUCTURED", expression=None, reason="지원하지 않는 조건이므로 원문을 보존합니다.")
        self.assert_valid()

    def test_eligibility_unit_must_match_attribute(self):
        self.notice["eligibility"][0]["rule"]["expression"]["children"][0]["unit"] = "KRW"
        self.assert_invalid("ATTRIBUTE_UNIT_CONFLICT")

    def test_age_integer_and_nonnegative_constraints(self):
        atom = self.notice["eligibility"][0]["rule"]["expression"]["children"][0]
        # A boolean fails the operator's generic value shape in the schema; the rest fail the dictionary.
        for value, code in ((19.5, "ATTRIBUTE_VALUE_INVALID"), (-1, "ATTRIBUTE_VALUE_INVALID"), (True, "SCHEMA")):
            with self.subTest(value=value):
                atom["value"] = value
                self.assert_invalid(code)

    def test_attribute_operator_must_be_in_dictionary(self):
        atom = self.notice["eligibility"][0]["rule"]["expression"]["children"][0]
        atom.update(operator="IN", value=[19, 20])
        self.assert_invalid("ATTRIBUTE_OPERATOR_CONFLICT")

    def test_code_attributes_use_dictionary_pattern_and_enum(self):
        atom = self.notice["eligibility"][0]["rule"]["expression"]["children"][0]
        for attribute, value in (
            ("RESIDENCE_REGION_CODE", "41110"),
            ("BUSINESS_REGION_CODE", "4100010100"),
            ("MARITAL_STATUS", "SINGLE"),
            ("INDUSTRY_CODE", ""),
        ):
            with self.subTest(attribute=attribute):
                atom.update(attribute=attribute, unit="CODE", operator="EQ", value=value)
                self.assert_invalid("ATTRIBUTE_VALUE_INVALID")
                valid = {
                    "RESIDENCE_REGION_CODE": "4111000000",
                    "BUSINESS_REGION_CODE": "4100000000",
                    "MARITAL_STATUS": "MARRIED",
                }
                atom["value"] = valid.get(attribute, "68")
                errors = contract.validate(self.notice, "schemas/standard-notice.v1.json")
                self.assertFalse(any(e.startswith("ATTRIBUTE_") for e in errors), errors)

    def test_income_ratio_requires_benchmark(self):
        self.notice["eligibility"][2]["rule"]["expression"]["benchmark"] = None
        self.assert_invalid("BENCHMARK_REQUIRED")

    def test_attribute_checks_run_on_standalone_eligibility(self):
        data = contract.load(ROOT / "examples/eligibility.json")
        schema = "schemas/eligibility.v1.json"
        self.assertEqual([], contract.validate(data, schema))
        atoms = [node for _, node in contract.walk(data) if node.get("kind") == "ATOMIC"]
        atoms[0]["unit"] = "BOOLEAN" if atoms[0]["unit"] != "BOOLEAN" else "KRW"
        self.assert_invalid("ATTRIBUTE_UNIT_CONFLICT", data, schema)

    def test_attribute_dictionary_matches_schema_enums(self):
        self.assertEqual([], contract.dictionary_errors())
        self.assertEqual(22, len(contract.ATTRIBUTES))

    def test_between_range_order_is_checked(self):
        atom = self.notice["eligibility"][0]["rule"]["expression"]["children"][0]
        atom.update(operator="BETWEEN", value=[39, 19])
        self.assert_invalid("REVERSED_RANGE")

    def test_income_ratio_requires_reference_version_and_household_scope(self):
        benchmark = self.notice["eligibility"][2]["rule"]["expression"]["benchmark"]
        benchmark.update(referenceYear=None, referenceVersion=None, householdScope=None)
        self.assert_invalid("INCOMPLETE_INCOME_BENCHMARK")

    def test_exception_replace_requires_expression(self):
        self.notice["eligibility"][2]["exceptions"][0]["effectExpression"] = None
        self.assert_invalid("SCHEMA")

    def test_unreviewed_exceptions_do_not_complete(self):
        self.notice["eligibility"][0]["exceptionsStatus"] = "NOT_REVIEWED"
        self.assert_invalid("EXCEPTIONS_NOT_REVIEWED")

    def test_unit_price_period_must_match_rent_type(self):
        self.notice["supplyGroups"][0]["prices"][1]["billingPeriod"] = "ANNUAL"
        self.assert_invalid("PRICE_PERIOD_CONFLICT")

    def test_unknown_amount_is_not_zero(self):
        price = self.notice["supplyGroups"][0]["prices"][0]
        price["amountKrw"] = None
        self.assert_invalid("UNKNOWN_REASON_REQUIRED")
        self.notice["quality"]["missingFields"].append(
            {
                "target": "/supplyGroups/0/prices/0/amountKrw",
                "reason": "NOT_IN_SOURCE",
                "description": "금액이 원문에 없습니다.",
            }
        )
        self.assert_valid()
        price["amountKrw"] = 0
        self.notice["quality"]["missingFields"] = []
        self.assert_valid()  # Explicit zero is representable; source verification must establish that it is truly zero.

    def test_lease_initial_term_cannot_exceed_maximum(self):
        self.notice["supplyGroups"][0]["leaseTerm"]["maxMonths"] = 12
        self.assert_invalid("LEASE_TERM_CONFLICT")

    def test_independent_numeric_total_catches_mismatch(self):
        total = self.notice["notice"]["counts"][0]
        total.update(value=2, aggregationRole="TOTAL", aggregationComplete=True)
        child = copy.deepcopy(total)
        child.update(
            id="component", value=1, aggregationRole="COMPONENT", aggregationComplete=False, parentCountId=total["id"]
        )
        self.notice["notice"]["counts"].append(child)
        self.assert_invalid("COUNT_SUM_MISMATCH")

    def test_schedule_order_uses_actual_timezone(self):
        schedule = self.notice["schedules"][0]
        schedule["startsAt"]["value"] = "2026-10-01T10:00:00+09:00"
        schedule["endsAt"]["value"] = "2026-10-01T00:59:00Z"
        self.assert_invalid("REVERSED_SCHEDULE")

    def test_unknown_application_status_does_not_become_rolling(self):
        self.notice["applicationSummary"].update(status="UNKNOWN", basisScheduleIds=[])
        self.assert_valid()
        self.notice["applicationSummary"]["status"] = "ROLLING"
        self.assert_invalid("APPLICATION_BASIS_REQUIRED")

    def test_no_explicit_rolling_mode_cannot_claim_rolling(self):
        self.notice["applicationSummary"]["status"] = "ROLLING"
        self.assert_invalid("ROLLING_WITHOUT_EXPLICIT_CONDITION")

    def test_explicit_rolling_can_also_have_fixed_end_date(self):
        self.notice["applicationSummary"]["status"] = "ROLLING"
        self.notice["schedules"][0]["applicationMode"] = "ROLLING"
        self.assert_valid()

    def test_region_code_is_10_digit_legal_dong_code(self):
        self.notice["regionCodes"] = ["11110"]
        self.assert_invalid("SCHEMA")

    def test_region_code_zero_fill_follows_hierarchy(self):
        location = self.notice["locations"][0]
        for code in ("0011000000", "1100010100", "1111000001"):
            with self.subTest(code=code):
                location["regionCode"] = code
                self.notice["regionCodes"] = [code]
                self.assert_invalid("REGION_CODE_HIERARCHY")

    def test_region_index_contains_location_codes(self):
        self.notice["regionCodes"] = ["4111000000"]
        self.assert_invalid("LOCATION_REGION_NOT_INDEXED")

    def test_region_index_may_use_ancestor_code(self):
        self.notice["locations"][0]["regionCode"] = "1111010100"
        for index in (["1111010100"], ["1111000000"], ["1100000000"]):
            with self.subTest(index=index):
                self.notice["regionCodes"] = index
                self.assert_valid()
        self.notice["regionCodes"] = ["1111010200"]
        self.assert_invalid("LOCATION_REGION_NOT_INDEXED")

    def test_correction_link_is_collector_supplied(self):
        source = self.notice["sourceReferences"][0]
        source["kind"] = "CORRECTION"
        self.assert_invalid("UNLINKED_RELATED_NOTICE")
        source["supersedesPostId"] = "00000000-0000-7000-8000-000000000099"
        self.assert_valid()

    def test_revision_requires_previous_snapshot_and_changed_paths(self):
        self.notice.update(noticeVersion=2, previousNoticeVersion=1, changedFields=["/schedules/0/endsAt"])
        self.assert_valid()
        self.notice["previousNoticeVersion"] = None
        self.assert_invalid("NOTICE_VERSION_CONFLICT")

    def test_removed_value_pointer_is_allowed_in_changed_fields(self):
        self.notice.update(noticeVersion=2, previousNoticeVersion=1, changedFields=["/schedules/1"])
        self.assert_valid()  # The previous persisted snapshot must resolve removals.

    def test_personal_inputs_and_ai_cost_are_not_notice_fields(self):
        for key in ("userIncome", "userAge", "userWorkplace", "aiCalls", "costUsd"):
            with self.subTest(key=key):
                self.notice[key] = 1
                self.assert_invalid("SCHEMA")
                del self.notice[key]

    def test_private_url_is_not_a_public_original_link(self):
        self.notice["documents"][0]["originalUrl"] += "?serviceKey=synthetic-only"
        self.assert_invalid("PRIVATE_URL")

    def test_missing_collector_detail_url_stays_null(self):
        self.notice["sourceReferences"][0]["detailUrl"] = None
        self.assert_valid()
        self.notice["sourceReferences"][0]["detailUrl"] = ""
        self.assert_invalid("SCHEMA")

    def test_event_envelope_version_is_integer_like_raw_event(self):
        schema = "events/extraction.completed.v1.json"
        self.assert_valid(self.event, schema)
        self.event["schemaVersion"] = "1"
        self.assert_invalid("SCHEMA", self.event, schema)

    def test_completed_event_outer_fields_match_detail_notice(self):
        schema = "events/extraction.completed.v1.json"
        payload = self.event["payload"]
        payload["rawVersion"] = 2
        self.assert_invalid("EVENT_NOTICE_IDENTITY_CONFLICT", self.event, schema)
        payload["rawVersion"] = 1
        payload["conditions"] = payload["conditions"][1:]
        self.assert_invalid("EVENT_CONDITIONS_CONFLICT", self.event, schema)

    def test_completed_event_region_is_five_digit_prefix_of_locations(self):
        schema = "events/extraction.completed.v1.json"
        self.event["payload"]["regionCode"] = "1111000000"
        self.assert_invalid("SCHEMA", self.event, schema)
        self.event["payload"]["regionCode"] = "41110"
        self.assert_invalid("EVENT_REGION_CONFLICT", self.event, schema)

    def test_condition_in_review_needs_reason_and_blocks_auto_pass(self):
        schema = "events/extraction.completed.v1.json"
        condition = self.event["payload"]["conditions"][0]
        condition["inReview"] = True
        self.assert_invalid("SCHEMA", self.event, schema)  # 사유 없음 + AUTO_PASSED
        condition["reviewReasons"] = ["EVIDENCE_AMBIGUOUS"]
        self.assert_invalid("SCHEMA", self.event, schema)  # AUTO_PASSED와 함께 쓸 수 없음
        self.event["payload"]["reviewStatus"] = "IN_REVIEW"
        self.assert_valid(self.event, schema)

    def test_completed_event_without_detail_notice_is_allowed(self):
        del self.event["payload"]["notice"]
        self.assert_valid(self.event, "events/extraction.completed.v1.json")

    def test_publication_version_and_timestamp_formats(self):
        self.notice["applicationSummary"]["calculatedAt"] = "2026-10-15T12:00:00"
        self.assert_invalid("SCHEMA")


    def test_area_and_price_given_only_as_ranges(self):
        # 실측: 대전도시공사 청년매입임대 정정공고 — 엑셀의 단지별 면적·임대료가 「26~45㎡」처럼 범위라 계약에 담지 못해 검수로 감
        group = self.notice["supplyGroups"][0]
        price = group["prices"][0]
        group.update(exclusiveAreaSqm=None, exclusiveAreaRange={"min": 26.0, "max": 45.5})
        price.update(amountKrw=None, amountRangeKrw={"min": 12_000_000, "max": 15_000_000}, sourceAmount="1,200만~1,500만")
        missing = self.notice["quality"]["missingFields"]
        missing[:] = [m for m in missing if m["target"] != "/supplyGroups/0/exclusiveAreaSqm"]
        errors = contract.validate(self.notice, "schemas/standard-notice.v1.json")
        self.assertFalse([e for e in errors if "RANGE" in e or "amountKrw" in e], errors)

        reversed_range = copy.deepcopy(self.notice)
        reversed_range["supplyGroups"][0]["exclusiveAreaRange"] = {"min": 45.5, "max": 26.0}
        self.assert_invalid("RANGE_REVERSED", reversed_range)
        both = copy.deepcopy(self.notice)
        both["supplyGroups"][0]["prices"][0]["amountKrw"] = 12_000_000
        self.assert_invalid("RANGE_WITH_SINGLE_VALUE", both)


    def test_partial_pass_publishes_with_held_items_only(self):
        # 부분 완료(2026-10-17): 공고는 게시하고, 검수 이슈가 있는 항목만 보류합니다(공고 수천 건을 전부 사람이 보지 않기 위해)
        event = copy.deepcopy(self.event)
        payload, notice = event["payload"], event["payload"]["notice"]
        issue = {"code": "EXCEPTION_SUSPECTED", "target": "/eligibility/0", "description": "가산 기준이 예외로 빠졌을 수 있습니다."}
        held = [{"target": "/eligibility/0", "extent": "ITEM", "codes": ["EXCEPTION_SUSPECTED"], "description": "확인 전까지 판정에 쓰지 않습니다."}]
        notice["quality"].update(reviewIssues=[issue], heldItems=held)
        notice["quality"]["verification"]["status"] = "PARTIALLY_PASSED"
        payload.update(reviewStatus="PARTIALLY_PASSED", heldItems=held)
        payload["conditions"][0].update(inReview=True, reviewReasons=["EXCEPTION_SUSPECTED"])
        self.assertEqual([], contract.validate(event, "events/extraction.completed.v1.json"))

        open_condition = copy.deepcopy(event)
        open_condition["payload"]["conditions"][0].update(inReview=False, reviewReasons=[])
        self.assertTrue(any("HELD_CONDITION_NOT_IN_REVIEW" in e for e in contract.validate(open_condition, "events/extraction.completed.v1.json")))
        identity = copy.deepcopy(event)  # 공고 정체성 문제는 보류할 수 없고 공고 전체가 검수입니다
        identity["payload"]["notice"]["quality"]["reviewIssues"].append({"code": "NOT_A_NOTICE_SUSPECTED", "target": "/notice", "description": "x"})
        self.assertTrue(any("REVIEW_ISSUE_NOT_HELD" in e for e in contract.validate(identity, "events/extraction.completed.v1.json")))
        notice_held = copy.deepcopy(event)
        notice_held["payload"]["notice"]["quality"]["heldItems"].append({"target": "/notice", "extent": "SECTION", "codes": ["X"], "description": "x"})
        notice_held["payload"]["heldItems"] = notice_held["payload"]["notice"]["quality"]["heldItems"]
        self.assertTrue(any("HELD_TARGET_NOT_ALLOWED" in e for e in contract.validate(notice_held, "events/extraction.completed.v1.json")))
        no_list = copy.deepcopy(event)
        del no_list["payload"]["heldItems"]
        self.assertTrue(contract.validate(no_list, "events/extraction.completed.v1.json"))

    def test_shared_allocation_pool_must_be_one_value(self):
        data = contract.load(ROOT / "examples/housing.extracted.json")
        group = data["supplyGroups"][0]
        base = {"kind": "OFFERED", "scope": group["scope"], "aggregationRole": "STANDALONE", "parentCountId": None, "aggregationComplete": False,
                "evidenceIds": group["evidenceIds"], "allocationPoolId": "pool-1"}
        group["counts"] = [{**base, "id": "c-pool-a", "value": 21}, {**base, "id": "c-pool-b", "value": 21}]
        self.assertEqual([], contract.validate(data, "schemas/extracted-notice.v1.json"))
        group["counts"][1]["value"] = 42
        self.assertTrue(any("ALLOCATION_POOL_CONFLICT" in e for e in contract.validate(data, "schemas/extracted-notice.v1.json")))

if __name__ == "__main__":
    unittest.main()
