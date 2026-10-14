"""제목으로 글 종류를 나눠요. 기관 게시판에는 모집공고 말고도 후속 안내·정정공고·기존 입주자 안내·매입 공고가 섞여 있어요.

결과
  NOTICE      모집공고 → 상세를 연다
  CORRECTION  정정공고 → 상세를 열고 원래 공고에 연결
  FOLLOW_UP   후속 안내(당첨자 발표, 서류제출 대상자 등) → 원래 모집공고가 있을 때만 열고 연결
  EXCLUDED    그 밖의 글 → 열지 않음
"""

import re
from dataclasses import dataclass
from datetime import date

NOTICE, CORRECTION, FOLLOW_UP, EXCLUDED = "NOTICE", "CORRECTION", "FOLLOW_UP", "EXCLUDED"
OPEN_KINDS = {NOTICE, CORRECTION}

FOLLOWUP = re.compile(
    r"당첨자|계약\s*대상자|계약\s*안내|서류\s*심사|심사\s*대상자|대상자\s*발표|입주\s*대상자|예비자\s*발표|면접|교육\s*이수"
    r"|접수\s*결과|접수\s*마감|주택\s*공개|커트라인|추첨\s*결과|동\s*·?\s*호\s*추첨|결과\s*발표|모집\s*결과|선정\s*결과"
    r"|계약\s*체결|서류\s*제출\s*대상|대상자\s*안내|신규\s*계약|접수\s*번호|입주\s*순번|순번\s*(?:추첨|결정)"
)
EXISTING = re.compile(r"재계약|갱신\s*계약")  # 기존 입주자 안내
# 입주자 모집이 아닌 공고: 공사가 집을 사는 매입 공고, 사업자 공모, 토지·용지 공급, 보상, 평가위원
NOT_TENANT = re.compile(
    r"(?:기존\s*)?주택\s*매입\s*(?:재)?공고|매입\s*(?:재)?공고|사업자\s*(?:공모|모집)|택지|용지|수의\s*계약|낙찰"
    r"|보상\s*(?:계획|공고)|평가\s*위원|위원\s*(?:후보|모집|명단)"
)
BUSINESS_SPACE = re.compile(r"상가|점포|입점|사무실|창업|업무시설|근린생활|임차인")  # 이 수집기 범위 밖 (사업공간)
CORRECTION_MARK = re.compile(r"[\[(]\s*정정\s*(?:공고)?\s*[\])]|정정\s*공고")
RECRUIT = re.compile(r"모집\s*공고|입주자\s*모집|예비\s*입주자|추가\s*모집|잔여\s*세대.*모집")
REF = re.compile(r"(?P<name>.+?)\s*\(\s*(?:공고일?\s*:?\s*)?[‘'’]?(?P<y>\d{2,4})\s*[._]\s*(?P<m>\d{1,2})\s*[._]\s*(?P<d>\d{1,2})\s*\.?\s*(?:공고)?\s*\)")


def classify(title: str) -> str:
    if EXISTING.search(title) or NOT_TENANT.search(title) or BUSINESS_SPACE.search(title):
        return EXCLUDED
    ref = REF.search(title)
    if ref and re.search(r"모집\s*공고", ref.group("name")) and len(re.sub(r"[^가-힣]", "", title[ref.end() :])) >= 2:
        return FOLLOW_UP  # '…모집공고(2026. 6. 25.) 면접평가 대상자 발표'처럼 공고일 뒤에 글이 붙으면 후속 안내
    if FOLLOWUP.search(title):
        return FOLLOW_UP
    if CORRECTION_MARK.search(title) and RECRUIT.search(title):
        return CORRECTION
    if RECRUIT.search(title):
        return NOTICE
    return EXCLUDED


def norm_name(text: str) -> str:
    """공고 이름 비교용: 꾸밈말([당첨자발표], (수요자맞춤형))과 '모집공고' 같은 공통 단어를 빼요."""
    text = re.sub(r"\[[^\]]*\]|\([^)]*\)", " ", text)
    text = re.sub(r"입주자|모집\s*공고문?|모집|공고|잔여\s*세대|예비|정정", " ", text)
    return re.sub(r"[^0-9A-Za-z가-힣]", "", text)


@dataclass(frozen=True)
class TitleRef:
    name: str
    posted_on: date


def title_ref(title: str) -> TitleRef | None:
    """후속 안내 제목 안의 '(2026. 8. 21.)' 같은 공고일과 그 앞의 공고명. 원래 모집공고를 찾는 데 써요."""
    found = REF.search(title)
    if not found:
        return None
    year = int(found.group("y"))
    year = year + 2000 if year < 100 else year
    try:
        return TitleRef(norm_name(found.group("name")), date(year, int(found.group("m")), int(found.group("d"))))
    except ValueError:
        return None


def same_notice(ref: TitleRef, title: str, posted_on: date | None) -> bool:
    """후속 안내가 가리키는 (공고명, 공고일)과 모집공고가 같은지."""
    own = norm_name(title)
    same_name = bool(ref.name and own) and (ref.name in own or own in ref.name)
    return same_name and posted_on == ref.posted_on


def correction_base(title: str) -> str:
    """정정공고를 원래 공고와 비교할 이름 (정정 표시를 뺀 공고명)."""
    return norm_name(CORRECTION_MARK.sub("", title))
