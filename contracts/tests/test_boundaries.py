"""Java 수신부와 공유하는 정상·오류 입력의 동일한 판정 및 오류 출력 보호."""
import copy
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import validate_contracts as contract

CASES = json.loads((ROOT / "tests/fixtures/extraction-boundaries.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("case", CASES["cases"], ids=lambda c: c["name"])
def test_shared_boundary(case):
    value = copy.deepcopy(contract.load(ROOT / "examples" / CASES["base"]))
    for op in case["operations"]:
        parent, _, key = op["path"].rpartition("/")
        node = contract.resolve(value, parent)
        key = int(key) if isinstance(node, list) else key
        if op.get("remove"):
            del node[key]
        else:
            node[key] = op["value"]
    errors = contract.validate(value, "events/extraction.completed.v1.json")
    assert (not errors) == case["valid"], errors
    if case["errorCode"]:
        assert any(error.startswith(case["errorCode"] + " ") for error in errors), errors
    assert "review-placeholder-not-a-real-key" not in json.dumps(errors)
