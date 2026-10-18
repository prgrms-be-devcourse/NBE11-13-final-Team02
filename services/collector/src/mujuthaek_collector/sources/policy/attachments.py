"""정책 첨부 고르기와 원문 화면 읽기. API가 첨부를 주지 않으면 원문 공고 화면에서 첨부 링크와 본문 글자를 찾아요.

원문은 기관마다 화면이 달라서 선택자 대신 모양으로 찾아요.
첨부 (file_links)
  - 링크 글자·download 속성·주소 끝이 파일 이름(.pdf .hwp .hwpx .docx .xlsx .zip …)
  - 주소에 download·fileDown·atchFile 같은 다운로드 표시가 있고 글자에 파일 이름이 있는 링크
  - 링크 글자가 '내려받기'뿐이면 바로 바깥 칸(li·dd·td …)의 글자에서 파일 이름을 찾아요 (충남도청 게시판)
  - 미리보기·뷰어 링크와 명단 파일(당첨자·명부 …)은 빼요
본문 (main_text)
  - 링크가 적은 글 덩어리 중 링크 아닌 글자가 가장 많은 곳을 본문으로 봐요
  - 메뉴·하단(gnb, lnb, footer …)은 링크가 대부분일 때만 지워요. 본문을 감싼 틀에 'lnb' 같은 이름이 붙기도 해서요
  - 정책 이름의 핵심 단어가 절반 이상 들어 있을 때만 써요 (relevant). 참고 주소가 사이트 첫 화면인 경우가 많아서요
"""

from __future__ import annotations

import re
import urllib.parse

from mujuthaek_collector.core.files import DOC_EXTS, FileRef
from mujuthaek_collector.core.html import abs_url, body_text, soup, text_of

FILE_NAME = re.compile(r"[^\\/:*?\"<>|\r\n]+?\.(pdf|hwpx?|docx?|xlsx?|xlsm|zip)\b", re.I)
DOWNLOAD_HREF = re.compile(r"download|filedown|file_down|atchfile|attach|fileid|getfile|fn_?down", re.I)
VIEWER = re.compile(r"미리\s*보기|바로\s*보기|뷰어|viewer|preview", re.I)
SIZE_SUFFIX = re.compile(r"\s*[\[(]\s*[\d.,]+\s*(?:[KMG]?B|bytes?|byte)\s*[\])]\s*$", re.I)
FORM_FILE = re.compile(r"신청\s*(?:\([^)]*\))?\s*서|별지|서식|양식|동의서|위임장|서약서|확인서|계획서|신고서|명세서|증명")  # 신청(변경)서, [별지 1] …
MAIN_DOC = re.compile(r"공고|안내|지침|시행\s*계획|사업\s*계획|요강|모집|가이드|매뉴얼")
POSTER_FILE = re.compile(r"포스터|홍보물|홍보\s*자료|리플[렛릿]|전단|카드\s*뉴스|배너|썸네일")  # 그림으로 만든 홍보물이라 설명 글자가 거의 없어요
MAX_LINKS = 20
GENERIC_LABEL = re.compile(r"^(?:내려\s*받기|다운\s*로드|download|파일\s*받기|첨부\s*파일|받기)$", re.I)
NAME_SCOPE = ["li", "dd", "td", "p", "span", "div", "tr"]  # '내려받기' 링크의 파일 이름을 찾을 바깥 칸

NOISE_TAGS = ["nav", "header", "footer", "aside", "select", "button"]
NOISE_ATTR = re.compile(r"(?:^|[\s_-])(?:gnb|lnb|snb|footer|breadcrumb|sitemap|skip|quick|copyright|family)", re.I)
TEXT_BLOCKS = ["div", "section", "article", "main", "td", "dd", "li"]
MIN_TEXT_CHARS = 200
MAX_TEXT_CHARS = 20_000  # 원문 화면 하나에서 쓰는 최대 글자
GENERIC_WORDS = {"청년", "지원", "사업", "지원사업", "운영", "추진", "모집", "공고", "안내", "계획", "정책", "프로그램"}


def file_links(html: str, page_url: str) -> list[FileRef]:
    """원문 화면 → 첨부 후보. 같은 주소는 한 번만."""
    doc = soup(html)
    found: list[FileRef] = []
    seen: set[str] = set()
    for anchor in doc.find_all("a", href=True):
        href = anchor["href"].strip()
        if not href or href.startswith(("#", "mailto:", "tel:")) or href.lower().startswith("javascript"):
            continue
        label = SIZE_SUFFIX.sub("", text_of(anchor) or anchor.get("title") or "").strip()
        if VIEWER.search(label) or VIEWER.search(href):
            continue
        url = abs_url(page_url, href)
        path_name = urllib.parse.unquote(urllib.parse.urlsplit(url).path.rsplit("/", 1)[-1])
        name = next((m.group(0).strip() for m in (FILE_NAME.search(t) for t in (label, anchor.get("download") or "", path_name)) if m), None)
        if name is None and DOWNLOAD_HREF.search(href) and GENERIC_LABEL.match(label or ""):
            name = name_near(anchor)
        if name is None:
            continue  # 파일 이름이 안 보이는 다운로드 링크는 무엇인지 몰라서 받지 않아요
        if not (FILE_NAME.search(path_name) or DOWNLOAD_HREF.search(href) or FILE_NAME.fullmatch(label)):
            continue  # 글자에 파일 이름만 적힌 일반 링크 (예: 게시판 글 제목)
        if url in seen:
            continue
        seen.add(url)
        found.append(FileRef(name, url, len(found)))
        if len(found) >= MAX_LINKS:
            break
    return found


def name_near(anchor) -> str | None:
    """'충청남도 청년농업인 지원정책(pdf) - 배포.pdf [3,345,415byte] 내려받기 미리보기'처럼 바깥 칸에 적힌 파일 이름."""
    scope = anchor.find_parent(NAME_SCOPE)
    if scope is None:
        return None
    text = text_of(scope)
    if len(text) > 300:
        return None  # 칸이 너무 크면 다른 파일 이름을 잘못 잡을 수 있어요
    found = FILE_NAME.search(text)
    return found.group(0).strip() if found else None


def _chars(text: str) -> int:
    return len(re.sub(r"\s", "", text))


def _plain_chars(node) -> tuple[int, int]:
    """(전체 글자, 링크가 아닌 글자)"""
    total = _chars(node.get_text())
    return total, total - sum(_chars(a.get_text()) for a in node.find_all("a"))


def main_text(html: str) -> str | None:
    """원문 화면의 본문 글자. 본문다운 곳을 못 찾으면 None (사이트 첫 화면, 스크립트로 그리는 화면 등)."""
    doc = soup(html)
    for tag in doc.find_all(["script", "style", "noscript", "iframe"]):
        tag.decompose()
    noise = [t for t in doc.find_all(True) if t.name in NOISE_TAGS or NOISE_ATTR.search(" ".join([t.get("id") or "", *(t.get("class") or [])]))]
    for tag in noise:
        if tag.attrs is None:
            continue  # 바깥이 이미 지워짐
        total, plain = _plain_chars(tag)
        if total == 0 or plain < 0.5 * total:
            tag.decompose()  # 링크가 대부분이면 메뉴
    candidates = []
    for block in doc.find_all(TEXT_BLOCKS):
        total, plain = _plain_chars(block)
        if total >= MIN_TEXT_CHARS and plain >= 0.7 * total:
            candidates.append((plain, block))
    if not candidates:
        return None
    best_plain, best = max(candidates, key=lambda c: c[0])
    while True:  # 글자의 85% 이상을 가진 안쪽 덩어리로 내려가서 바깥 틀의 잡음을 줄여요
        inner = [(p, b) for p, b in candidates if b is not best and best in b.parents and p >= 0.85 * best_plain]
        if not inner:
            break
        best_plain, best = max(inner, key=lambda c: len(list(c[1].parents)))
    text = body_text(best)
    return text[:MAX_TEXT_CHARS] if _chars(text) >= MIN_TEXT_CHARS else None


def keywords(title: str) -> list[str]:
    """정책 이름의 핵심 단어: 두 글자 이상, 연도·흔한 단어(청년, 지원, 사업 …) 빼고."""
    words = re.findall(r"[가-힣A-Za-z]{2,}", re.sub(r"\d{2,4}\s*년", " ", title))
    return [w for w in dict.fromkeys(words) if w not in GENERIC_WORDS]


def relevant(title: str, text: str) -> bool:
    """본문에 정책 이름의 핵심 단어가 절반 이상 있는지. 핵심 단어가 없는 이름이면 판단하지 않고 통과."""
    words = keywords(title)
    if not words:
        return True
    compact = re.sub(r"\s", "", text)
    return sum(1 for w in words if w in compact) >= (len(words) + 1) // 2


def sniff_ext(data: bytes) -> str:
    """이름에 확장자가 없는 첨부(복지로 서식 등)는 내용 앞부분으로 형식을 알아내요."""
    if data.startswith(b"%PDF"):
        return "pdf"
    if data.startswith(b"\xd0\xcf\x11\xe0"):  # OLE 복합 문서: 한글(HWP) 또는 예전 오피스
        return "hwp" if b"H\x00W\x00P\x00" in data[:4096] or b"HWP Document" in data[:4096] else "doc"
    if data.startswith(b"PK"):
        head = data[:4096]
        if b"hwpml" in head or b"Contents/" in head or b"mimetypeapplication/hwp" in head:
            return "hwpx"
        if b"word/" in head:
            return "docx"
        if b"xl/" in head:
            return "xlsx"
        return "zip"
    return ""


def with_ext(file: FileRef, data: bytes) -> FileRef:
    if file.ext:
        return file
    ext = sniff_ext(data) or urllib.parse.urlsplit(file.url).path.rsplit(".", 1)[-1].lower()
    return FileRef(f"{file.name}.{ext}", file.url, file.index, file.size) if re.fullmatch(r"[a-z]{2,5}", ext or "") else file


def is_form(file: FileRef) -> bool:
    return bool(FORM_FILE.search(file.name)) and not MAIN_DOC.search(file.name)


def explains(file: FileRef) -> bool:
    """AI 추출에 넘길 만한 첨부인지: 공고문·안내문·지침 같은 설명 문서(pdf·hwp·hwpx).
    신청서·서식·동의서, 포스터·홍보물, 명단, 그림·압축·엑셀 파일은 받지 않아요. 확장자가 없으면 받아서 내용으로 형식을 확인해요."""
    if file.personal or is_form(file) or POSTER_FILE.search(file.name):
        return False
    return file.ext in DOC_EXTS or not file.ext


def text_order(files: list[FileRef]) -> list[FileRef]:
    """글자를 뽑을 문서 순서: 공고·안내·지침 → 그 밖의 문서."""
    docs = [f for f in files if f.ext in DOC_EXTS and explains(f)]
    return sorted(docs, key=lambda f: (not MAIN_DOC.search(f.name), f.ext != "pdf", f.index))
