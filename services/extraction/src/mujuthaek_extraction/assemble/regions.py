"""원문 지역 표현 → 법정동코드 10자리(모르는 하위 단계는 0).

코드표는 행정표준코드 법정동코드 전체 자료(코드, "서울특별시 종로구 청운동" 형태의 이름, 폐지 여부)를 기준일과 함께 주입합니다.
원문에서 확인한 가장 낮은 단계까지 코드로 만들고, 하나로 정해지지 않으면 상위 단계로 내려가거나 None을 돌려줍니다. 추정하지 않습니다.
"""

from __future__ import annotations

import csv
import re
import unicodedata
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

# 원문에서 흔한 시도 약칭. 코드표의 공식 이름과 함께 사용합니다.
SIDO_ALIASES = {
    "서울": "서울특별시",
    "서울시": "서울특별시",
    "부산": "부산광역시",
    "부산시": "부산광역시",
    "대구": "대구광역시",
    "대구시": "대구광역시",
    "인천": "인천광역시",
    "인천시": "인천광역시",
    "광주광역시": "광주광역시",
    "대전": "대전광역시",
    "대전시": "대전광역시",
    "울산": "울산광역시",
    "울산시": "울산광역시",
    "세종": "세종특별자치시",
    "세종시": "세종특별자치시",
    "경기": "경기도",
    "강원": "강원특별자치도",
    "강원도": "강원특별자치도",
    "충북": "충청북도",
    "충남": "충청남도",
    "전북": "전북특별자치도",
    "전라북도": "전북특별자치도",
    "전남": "전라남도",
    "경북": "경상북도",
    "경남": "경상남도",
    "제주": "제주특별자치도",
    "제주도": "제주특별자치도",
}
# "광주"만 있으면 광주광역시와 경기도 광주시를 구별할 수 없어 약칭으로 쓰지 않습니다.
# 통합으로 폐지된 시도 → 이어받은 시도. 코드표에 옛 이름이 현존하면(이전 기준일 코드표) 쓰지 않습니다.
# 실측: 코드표 2026-09-29 기준 광주광역시·전라남도 폐지 → 전남광주통합특별시. 공고문은 옛 이름을 그대로 써서 시군구 코드가 비었음.
SIDO_SUCCESSORS = {
    "광주광역시": "전남광주통합특별시",
    "전라남도": "전남광주통합특별시",
}
# 한 시도만 담당하는 공급 기관(collector 출처 코드 → 시도). 원문에 "강동구"처럼 시도 없이 적혔을 때만 씁니다.
# LH·마이홈처럼 전국 기관은 넣지 않습니다.
SOURCE_SIDO = {
    "sh": "서울특별시",
    "gh": "경기도",
    "ih": "인천광역시",
    "bmc": "부산광역시",
    "dudc": "대구광역시",
    "dcco": "대전광역시",
    "umca": "울산광역시",
    "gbdc": "경상북도",
    "gndc": "경상남도",
    "jbdc": "전북특별자치도",
}
SIGUNGU_SUFFIX = re.compile(r".+(시|군|구)$")
DONG_SUFFIX = re.compile(r".+(동|읍|면|가|리)$")  # "종로1가"처럼 가로 끝나는 법정동 포함


@dataclass(frozen=True)
class RegionMatch:
    code: str | None
    level: str | None  # SIDO / SIGUNGU / DONG
    note: str | None  # 상위 단계로 내려갔거나 정하지 못한 사유 (검수 메모)


def _clean(text: str) -> str:
    return unicodedata.normalize("NFKC", text).strip()


class RegionCodeTable:
    """현존 법정동코드를 이름으로 찾는 코드표입니다. 원문 지역 표현을 확인된 가장 낮은 단계의 코드로 바꿉니다."""

    def __init__(self, rows: Iterable[tuple[str, str, bool]], reference_date: str):
        """rows: (10자리 코드, 공백으로 나눈 전체 이름, 현존 여부). 폐지된 코드는 사용하지 않습니다."""
        self.reference_date = reference_date
        self._by_name: dict[tuple[str, ...], str] = {}
        self._sigungu_by_tail: dict[tuple[str, ...], set[tuple[str, ...]]] = {}
        for code, name, active in rows:
            if not active or not re.fullmatch(r"[0-9]{10}", code):
                continue
            parts = tuple(_clean(name).split())
            self._by_name[parts] = code
            if code[5:] == "00000" and len(parts) >= 2:  # 시군구 단위 (일반구는 "청주시 상당구"처럼 두 단어)
                for size in range(1, len(parts)):
                    self._sigungu_by_tail.setdefault(parts[-size:], set()).add(parts)

    @classmethod
    def from_csv(cls, path: Path, reference_date: str) -> RegionCodeTable:
        """공공데이터포털 법정동코드 CSV(법정동코드,법정동명,폐지여부 / 존재·폐지, UTF-8)를 읽습니다."""
        with path.open(encoding="utf-8", newline="") as handle:
            reader = csv.reader(handle)
            next(reader)  # 머리글
            return cls(((code, name, status == "존재") for code, name, status in reader), reference_date)

    def lookup(self, region: str | None, default_sido: str | None = None) -> RegionMatch:
        """default_sido: 출처가 한 시도뿐인 기관(예: SH → 서울특별시)일 때 원문에 시도가 없으면 사용합니다."""
        if not region or not _clean(region):
            return RegionMatch(None, None, "원문 지역 표현이 없습니다.")
        tokens = [t for t in re.split(r"[\s,()]+", _clean(region)) if t]
        sido = None
        if tokens:
            alias = SIDO_ALIASES.get(tokens[0])
            name = alias or tokens[0]
            successor = SIDO_SUCCESSORS.get(name)
            if (name,) not in self._by_name and successor and (successor,) in self._by_name:
                name = successor
            if alias or (name,) in self._by_name:
                sido = name
                tokens = tokens[1:]
        explicit = sido is not None  # 원문에 시도가 적혔는지(기관 기본 시도는 짐작일 뿐)
        sido = sido or default_sido

        sigungu_tokens: list[str] = []
        while tokens and SIGUNGU_SUFFIX.fullmatch(tokens[0]) and not DONG_SUFFIX.fullmatch(tokens[0]):
            sigungu_tokens.append(tokens.pop(0))
        dong = tokens[0] if tokens and DONG_SUFFIX.fullmatch(tokens[0]) else None

        if not sigungu_tokens:
            if sido and (sido,) in self._by_name:
                return RegionMatch(self._by_name[(sido,)], "SIDO", None)
            return RegionMatch(None, None, f"시도·시군구를 확인할 수 없습니다: {region!r}")

        everywhere = self._sigungu_by_tail.get(tuple(sigungu_tokens), set())
        options = {o for o in everywhere if o[0] == sido} if sido else everywhere
        if not explicit and sido and not options:
            # 기관 기본 시도 밖의 지역(SH 공고의 "하남시"): 기본 시도로 덮지 않고, 전국에서 하나뿐이면 그것을 씁니다(검토 2026-10-16)
            options = everywhere
            sido = None
        if len(options) != 1:
            reason = "같은 이름의 시군구가 여러 곳입니다" if options else "코드표에 없는 시군구입니다"
            if sido and (sido,) in self._by_name:
                return RegionMatch(self._by_name[(sido,)], "SIDO", f"{reason}: {region!r}. 시도 단위까지만 사용합니다.")
            return RegionMatch(None, None, f"{reason}: {region!r}")

        sigungu = next(iter(options))
        if dong and (*sigungu, dong) in self._by_name:
            return RegionMatch(self._by_name[(*sigungu, dong)], "DONG", None)
        note = f"법정동 '{dong}'을(를) 찾지 못해 시군구 단위까지만 사용합니다(행정동일 수 있음)." if dong else None
        return RegionMatch(self._by_name[sigungu], "SIGUNGU", note)


def from_collector_code(code: str | None) -> str | None:
    """collector raw 이벤트의 시군구 5자리 코드를 10자리로 바꿉니다."""
    return f"{code}00000" if code and re.fullmatch(r"[0-9]{5}", code) else None
