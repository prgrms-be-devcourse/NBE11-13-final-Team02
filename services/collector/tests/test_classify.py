from datetime import date

import pytest

from mujuthaek_collector.core.classify import CORRECTION, EXCLUDED, FOLLOW_UP, NOTICE, classify, correction_base, norm_name, same_notice, title_ref


@pytest.mark.parametrize(
    ("title", "kind"),
    [
        ("2026년 하반기 신혼·신생아 매입임대주택Ⅱ 입주자 모집공고", NOTICE),
        ("2026년 행복주택 입주자 모집공고 [정정공고]", CORRECTION),
        ("성북구 연극인주택 잔여세대 입주자 모집공고(26. 7. 8.) 당첨자발표 및 계약안내", FOLLOW_UP),
        ("2026년 기존주택 매입 공고", EXCLUDED),
        ("○○지구 공동주택용지 공급 공고", EXCLUDED),
        ("임대아파트 단지 내 상가 입점자 모집 공고", EXCLUDED),
        ("임대주택 재계약 안내", EXCLUDED),
        ("2026년 국민임대 예비입주자 모집공고", NOTICE),
        # 대전 실제 제목 (2026-10-15)
        ("2026년 2차 영구임대아파트 예비입주자 모집공고", NOTICE),
        ("낭월 다가온 청년주택 추가모집 공고", NOTICE),
        ("2026년 기존주택(청년임대) 3차 매입공고", EXCLUDED),
        ("2026년 기존주택(청년) 추가 매입 공고", EXCLUDED),
        ("갑천2 트리풀시티 엘리프 단지 내 상가 수의계약 공고", EXCLUDED),
        ("대전평촌일반산업단지 산업ㆍ복합시설용지 일반공급 3차 공고", EXCLUDED),
        # 인천·서울 실제 제목 (2026-10-19): 특화시설 운영자 모집은 제외, '(운영기관 : ○○)'은 입주자 모집
        ("2026년 우리집 특화시설 운영자 모집공고(수시)", EXCLUDED),
        ("2026년 공모형 매입임대주택 특화시설 운영자 모집공고(정기)", EXCLUDED),
        ("[청년형] 특화형 매입임대주택(금천구) 입주자 모집 공고(운영기관 : 한지붕협동조합)", NOTICE),
    ],
)
def test_classify(title, kind):
    assert classify(title) == kind


def test_follow_up_points_to_original_notice():
    ref = title_ref("성북구 연극인주택 잔여세대 입주자 모집공고(26. 7. 8.) 당첨자발표 및 계약안내")
    assert ref is not None and ref.posted_on == date(2026, 7, 8)
    assert same_notice(ref, "성북구 연극인주택 잔여세대 입주자 모집공고", date(2026, 7, 8))
    assert not same_notice(ref, "성북구 연극인주택 잔여세대 입주자 모집공고", date(2026, 7, 9))


def test_correction_base_matches_original_name():
    assert correction_base("2026년 행복주택 입주자 모집공고 [정정공고]") == norm_name("2026년 행복주택 입주자 모집공고")
