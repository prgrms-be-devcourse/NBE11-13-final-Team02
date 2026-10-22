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
        # 인천·울산 실제 제목 (2026-10-20): 첨부 없는 안내·알림은 후속 안내, 심사·동호배정 결과는 그대로 제외
        ("2026년 기존주택 매입임대(청년형) 예비입주자 선정 안내", FOLLOW_UP),
        ("2026년 매입임대주택(청년형) 예비입주자 선정 발표 지연 안내", FOLLOW_UP),
        ("2026년 매입임대주택(일반형) 2차 예비입주자 입주안내", FOLLOW_UP),
        ("2026년 매입임대주택(청년형) 예비입주자 일정 안내", FOLLOW_UP),
        ("유홈(U home) 신정 청년주택 자격완화 예비입주자 모집 종료 알림", FOLLOW_UP),
        ("문수데시앙3단지 국민임대주택(46㎡) 예비입주자 2차 모집 중단 안내", FOLLOW_UP),
        ("성안동 청년희망주택 예비입주자 확정 공고", FOLLOW_UP),
        ("율동위드유아파트 주거약자용 예비입주자 순번 결과 공고", FOLLOW_UP),
        ("대전평촌일반산업단지 입주협약기업 입주계약심사결과 공고", EXCLUDED),
        ("동인천역 파크푸르지오 영구임대주택 예비자 동호배정 결과 공고(26.1.30.)", EXCLUDED),
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
