"""첨부 고르기. 공고문은 하나만, 주택목록은 엑셀 우선으로 최대 2개, 명단·신청서·도면은 받지 않아요."""

import re
from dataclasses import dataclass

PERSONAL_FILE = re.compile(r"명부|명단|당첨자|예비자|계약\s*대상자|합격자|추첨\s*결과")  # 사람 이름이 담긴 파일: 받지도 열지도 않음
NOTICE_FILE_NAME = re.compile(r"공고문|모집\s*공고")
NOT_NOTICE_FILE = re.compile(r"안내문|커트라인|결과|양식|서약서|위임장|동의서|신청서|위치도|평면도|배치도|도면")
UNIT_LIST_FILE = re.compile(
    r"주택\s*목록|공급\s*(?:주택\s*)?목록|임대\s*조건|호별|대상\s*주택|주택\s*현황|단지\s*목록|모집\s*호수|공급\s*현황|주택\s*리스트"
    r"|공급\s*가능\s*주택|건물\s*목록"
)
FILE_EXT = re.compile(r"\.(hwpx?|pdf|zip|docx?|xlsx?|xlsm|jpe?g|png)\s*$", re.I)
DOC_EXTS = ("pdf", "hwp", "hwpx")
TABLE_EXTS = ("xlsx", "xlsm", "pdf", "hwpx", "hwp")


@dataclass
class FileRef:
    name: str
    url: str
    index: int
    size: int | None = None

    @property
    def ext(self) -> str:
        found = FILE_EXT.search(self.name)
        return found.group(1).lower() if found else ""

    @property
    def personal(self) -> bool:
        return bool(PERSONAL_FILE.search(self.name))

    @property
    def unit_list(self) -> bool:
        return bool(UNIT_LIST_FILE.search(self.name)) and not self.personal

    @property
    def notice_doc(self) -> bool:
        return not self.personal and not self.unit_list and not NOT_NOTICE_FILE.search(self.name)


def pick_notice_files(files: list[FileRef], prefer: str = "pdf") -> list[FileRef]:
    """공고문 후보를 순서대로 최대 2개: 이름에 '공고문·모집공고'가 든 파일 → 선호 형식(PDF) → 다른 형식(HWP·HWPX).
    첫 번째가 받기·읽기에 실패하면 두 번째를 시도해요."""
    usable = [f for f in files if f.notice_doc and f.ext in DOC_EXTS]
    order = {"pdf": 0, "hwp": 1, "hwpx": 1} if prefer == "pdf" else {"hwp": 0, "hwpx": 0, "pdf": 1}
    usable.sort(key=lambda f: (not NOTICE_FILE_NAME.search(f.name), order[f.ext], f.index))
    return usable[:2]


def pick_unit_lists(files: list[FileRef]) -> list[FileRef]:
    """주택목록 최대 2개: '상세'가 든 목록 먼저, 같은 목록이 엑셀·PDF로 있으면 엑셀 하나만."""
    lists = [f for f in files if f.unit_list and f.ext in TABLE_EXTS]
    lists.sort(key=lambda f: ("상세" not in f.name, f.ext not in ("xlsx", "xlsm"), f.name))
    picked, stems = [], set()
    for file in lists:
        stem = re.sub(r"^\s*[\d\-.]+\s*|\.[A-Za-z0-9]+$", "", file.name).strip()
        if stem not in stems:
            stems.add(stem)
            picked.append(file)
    return picked[:2]
