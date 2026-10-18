"""정책 API 파서 테스트. 응답 모양(필드 이름)을 작은 예시로 고정해요."""

import pytest

from mujuthaek_collector.core.files import FileRef
from mujuthaek_collector.errors import BlockedError, ParseError
from mujuthaek_collector.sources.policy.attachments import explains, file_links, sniff_ext, text_order, with_ext
from mujuthaek_collector.sources.policy.base import normalize_key, xml_to_dict
from mujuthaek_collector.sources.policy.bizinfo import BizinfoSource
from mujuthaek_collector.sources.policy.bizinfo import region_of as bizinfo_region
from mujuthaek_collector.sources.policy.bokjiro import BokjiroCentralSource, BokjiroLocalSource
from mujuthaek_collector.sources.policy.gov24 import Gov24Source
from mujuthaek_collector.sources.policy.kstartup import KStartupSource
from mujuthaek_collector.sources.policy.youth import YouthSource, region_codes
from tests import policy_samples as s
from tests.policy_fakes import detail_urls, list_url


def test_gov24_list_detail_and_conditions():
    source = Gov24Source("KEY")
    assert list_url(source, 2) == "https://api.odcloud.kr/api/gov24/v3/serviceList?page=2&perPage=1000&serviceKey=KEY"
    page = source.parse_list(s.GOV24_LIST)
    assert page.total == 2 and [i.policy_id for i in page.items] == ["000000465790", "641000000123"]
    item = page.items[0]
    assert item.marker == "20260901093011"
    detail_url, conditions_url = detail_urls(source, item)
    assert "serviceDetail" in detail_url and "cond%5B%EC%84%9C%EB%B9%84%EC%8A%A4ID%3A%3AEQ%5D=000000465790" in detail_url
    detail = source.parse_detail(item, [s.gov24_detail(item.policy_id), s.gov24_conditions(item.policy_id)])
    assert detail.record["구비서류"] == "신분증, 임대차계약서" and detail.record["지원대상"].startswith("만 19~34세")
    assert detail.record["지원조건"] == {"대상": ["1인가구", "무주택세대"], "대상연령": "19~34"}
    assert detail.categories == ["주거·자립"] and detail.files == []
    assert (detail.region_scope, detail.region_names) == ("NATIONWIDE", [])  # 국토교통부 (중앙행정기관)
    assert detail.origin_urls == ["https://www.gov.kr/portal/rcvfvrSvc/dtlEx/000000465790"]
    local = source.parse_detail(page.items[1], [s.gov24_detail("641000000123"), s.gov24_conditions("641000000123")])
    assert (local.region_scope, local.region_names) == ("SIGUNGU", ["서울특별시 강남구"])
    assert detail.modified_at == "20260901093011"  # 상세의 '2026-01-29' 같은 날짜가 아니라 목록의 정확한 값


@pytest.mark.parametrize(
    "kind, agency, expected",
    [
        ("시군구", "경상북도 성주군", ("SIGUNGU", ["경상북도 성주군"])),
        ("광역시도", "부산광역시", ("SIDO", ["부산광역시"])),
        ("교육청", "경상북도교육청", ("SIDO", ["경상북도"])),
        ("중앙행정기관", "보건복지부", ("NATIONWIDE", [])),
        ("공공기관", "한국장학재단", ("NATIONWIDE", [])),
        ("지방공기업", "천안도시공사", ("UNKNOWN", [])),
        ("지방출자_출연기관", "재단법인안산인재육성재단", ("UNKNOWN", [])),
        (None, None, ("UNKNOWN", [])),
    ],
)
def test_gov24_region_scope_from_agency_type(kind, agency, expected):
    from mujuthaek_collector.sources.policy.gov24 import region_of

    assert region_of(kind, agency) == expected


def test_gov24_view_count_is_not_part_of_the_content():
    source = Gov24Source("KEY")
    record = source.parse_list(s.GOV24_LIST).items[0].record
    assert "15321" not in source.body_text(record)
    assert source.body_text(record) == source.body_text({**record, "조회수": 99999})


def test_gov24_key_error_blocks():
    with pytest.raises(BlockedError):
        Gov24Source("KEY").parse_list(s.GOV24_KEY_ERROR)
    with pytest.raises(ParseError):
        Gov24Source("KEY").parse_list("<html>점검 중</html>")


def test_bokjiro_central_reads_forms_from_api():
    source = BokjiroCentralSource("KEY")
    assert "callTp=L" in list_url(source, 1) and "srchKeyCode=003" in list_url(source, 1)
    page = source.parse_list(s.BOKJIRO_CENTRAL_LIST)
    assert page.total == 1 and page.items[0].policy_id == "WLF00001188"
    detail = source.parse_detail(page.items[0], [s.BOKJIRO_CENTRAL_DETAIL])
    assert [(f.name, f.url) for f in detail.files] == [
        ("청년전세임대 안내문", "https://www.bokjiro.go.kr/files/form/청년전세임대_안내문.pdf"),
        ("입주 신청서", "https://www.bokjiro.go.kr/files/form/download.do?id=77"),
    ]
    assert detail.record["slctCritCn"] == "소득·자산 기준 충족" and detail.categories == ["주거", "생활지원"]
    assert (detail.region_scope, detail.region_names) == ("NATIONWIDE", [])
    assert detail.detail_url.endswith("wlfareInfoId=WLF00001188&wlfareInfoReldBztpCd=01")
    assert "선정 기준: 소득·자산 기준 충족" in source.body_text(detail.record)
    assert "4021" not in source.body_text(detail.record)  # 조회수


def test_bokjiro_agency_without_repeated_names():
    from mujuthaek_collector.sources.policy.bokjiro import join_names

    assert join_names("교육부 학생지원총괄과", "학생지원총괄과") == "교육부 학생지원총괄과"
    assert join_names(None, "") is None


def test_bokjiro_local_region_and_homepage_origin():
    source = BokjiroLocalSource("KEY")
    item = source.parse_list(s.BOKJIRO_LOCAL_LIST).items[0]
    assert item.marker == "20260903"
    detail = source.parse_detail(item, [s.BOKJIRO_LOCAL_DETAIL])
    assert (detail.region_scope, detail.region_names) == ("SIGUNGU", ["인천광역시 연수구"])
    assert detail.agency == "인천광역시 연수구 청년정책과"
    assert detail.files == [] and detail.origin_urls[0] == "https://www.yeonsu.go.kr/notice/view.do?id=901"


def test_bokjiro_local_forms_use_wlfare_names():
    source = BokjiroLocalSource("KEY")
    item = source.parse_list(s.BOKJIRO_LOCAL_LIST).items[0]
    detail = source.parse_detail(item, [s.BOKJIRO_LOCAL_DETAIL_WITH_FORM])
    assert [f.name for f in detail.files] == ["★ 2026년 충청남도 청년 주택임차보증금 이자 지원사업 공고문 (1).hwpx"]
    assert detail.files[0].url.endswith("atcflId=20261013UUWBM1731290211628410&atcflSn=1")
    assert detail.origin_urls[0] == "http://www.chungnam.go.kr/cnnet/content.do?mnu_cd=CNNMENU02391"


def test_bokjiro_local_sido_only_is_sido_scope():
    source = BokjiroLocalSource("KEY")
    record = {"servId": "WLF00006794", "ctpvNm": "충청남도"}
    assert (source.scope(record), source.regions(record)) == ("SIDO", ["충청남도"])
    record = {"servId": "W", "ctpvNm": "부산광역시", "sggNm": "해운대구, 수영구"}
    assert (source.scope(record), source.regions(record)) == ("SIGUNGU", ["부산광역시 해운대구", "부산광역시 수영구"])


def test_data_go_kr_key_error_blocks():
    with pytest.raises(BlockedError):
        BokjiroCentralSource("KEY").parse_list(s.DATA_GO_KR_KEY_ERROR)


def test_youth_list_by_category():
    source = YouthSource("KEY", ["주거", "복지문화"])
    assert source.segments() == ["주거", "복지문화"]
    url = list_url(source, 1, "주거")
    assert url.startswith("https://www.youthcenter.go.kr/go/ythip/getPlcy?apiKeyNm=KEY&rtnType=json") and "lclsfNm=%EC%A3%BC%EA%B1%B0" in url
    page = source.parse_list(s.youth_list([s.YOUTH_POLICY], total=31))
    assert page.total == 31 and page.items[0].marker == "2026-09-01 10:00:00"
    detail = source.parse_detail(page.items[0], [])
    assert detail.region_codes == ["28177", "28185"] and detail.region_scope == "SIGUNGU"
    assert detail.categories == ["주거", "주거비 지원"]
    many = ",".join(f"{11000 + i:05d}00000" for i in range(230))
    nationwide = source.parse_detail(source.parse_list(s.youth_list([{**s.YOUTH_POLICY, "zipCd": many}])).items[0], [])
    assert (nationwide.region_scope, nationwide.region_codes) == ("NATIONWIDE", [])
    assert detail.origin_urls == ["https://www.incheon.go.kr/board/view.do?id=3301"]
    assert YouthSource("KEY", []).segments() == [None]


def test_youth_error_code():
    with pytest.raises(BlockedError):
        YouthSource("KEY").parse_list('{"resultCode": 401, "resultMessage": "인증키가 유효하지 않습니다"}')
    with pytest.raises(ParseError):
        YouthSource("KEY").parse_list('{"resultCode": 500, "resultMessage": "서버 오류"}')


def test_kstartup_open_announcements_only():
    source = KStartupSource("KEY")
    url = list_url(source)
    assert url.startswith("https://apis.data.go.kr/B552735/kisedKstartupService01/getAnnouncementInformation01?serviceKey=KEY")
    assert "returnType=json" in url and "cond%5Brcrt_prgs_yn%3A%3AEQ%5D=Y" in url
    page = source.parse_list(s.kstartup_list([s.KSTARTUP_POLICY], total=228))
    item = page.items[0]
    assert page.total == 228 and item.policy_id == "179540" and "id" not in item.record  # 응답 안 순번은 버려요
    assert item.title == "[ 모 집 ] GVC AI Platform & Startup Enabling Program"  # HTML 문자 참조 풀기
    assert source.wanted(item) and detail_urls(source, item) == []
    moved = source.parse_list(s.kstartup_list([{**s.KSTARTUP_POLICY, "id": 7}])).items[0]
    assert moved.marker == item.marker  # 순번만 바뀌면 같은 공고
    detail = source.parse_detail(item, [])
    assert (detail.region_scope, detail.region_names, detail.categories) == ("NATIONWIDE", [], ["사업화"])
    assert detail.apply_period == "20261016 ~ 20261022" and detail.apply_url == "https://global.venture.center/launchpad"
    assert detail.how_to_apply == "온라인: https://global.venture.center/launchpad"
    assert "창업 기간: 예비창업자,1년미만,3년미만" in detail.target and detail.origin_urls == [] and detail.files == []
    assert source.field_of(detail.record) == "STARTUP_SUPPORT"
    seoul = source.parse_detail(source.parse_list(s.kstartup_list([{**s.KSTARTUP_POLICY, "supt_regin": "서울"}])).items[0], [])
    assert (seoul.region_scope, seoul.region_names) == ("SIDO", ["서울"])


@pytest.mark.parametrize(
    ("title", "field", "expected"),
    [
        ("2026년 서울창업센터 동작 입주기업 모집", "시설ㆍ공간ㆍ보육", True),
        ("영도 창업오피스 크립톤 1:1 멘토링 입주기업 모집", "시설ㆍ공간ㆍ보육", True),  # 멘토링이 있어도 입주 공고
        ("[서초창업스테이션] 10월 창업 교육 프로그램", "시설ㆍ공간ㆍ보육", False),
        ("2026 스타트업 언론홍보 지원사업 모집 공고", "사업화", False),
        ("2026년 용산구 청년기업 융자지원사업", "융자ㆍ보증", True),
        ("2026년 중동상황 대응 「수출위기 극복 물류지원금」 지원사업 모집", "판로ㆍ해외진출", True),  # 분야가 달라도 지원금
        ("창업 초보를 위한 창업 A-Z 교육", "멘토링ㆍ컨설팅ㆍ교육", False),
        ("2026 대경권 엔젤투자 피칭룸 참여기업 모집 공고", "멘토링ㆍ컨설팅ㆍ교육", False),
    ],
)
def test_kstartup_scope_is_space_and_funds(title, field, expected):
    source = KStartupSource("KEY")
    item = source.parse_list(s.kstartup_list([{**s.KSTARTUP_POLICY, "biz_pbanc_nm": title, "supt_biz_clsfc": field}])).items[0]
    assert source.wanted(item) is expected


def test_youth_startup_scope_reads_support_text():
    source = YouthSource("KEY")

    def item(name, support):
        policy = {**s.YOUTH_POLICY, "lclsfNm": "일자리", "mclsfNm": "창업", "plcyNm": name, "plcySprtCn": support}
        return source.parse_list(s.youth_list([policy])).items[0]

    fund = item("삼척형 청년스타트업 창업지원사업", "창업간접비 1,200만 원 지원")
    assert source.wanted(fund) and source.field_of(fund.record) == "STARTUP_SUPPORT"
    assert not source.wanted(item("2026년 중랑구 청년 창업 아카데미", "사업기간 5~6월"))
    assert not source.wanted(item("청년 창업통계 공개", "청년 창업 통계 발굴·공개"))


def test_kstartup_key_error_blocks():
    with pytest.raises(BlockedError):
        KStartupSource("KEY").parse_list('{"code": -401, "msg": "인증키는 필수 항목 입니다."}')


def test_bizinfo_startup_filter_files_and_html():
    source = BizinfoSource("KEY")
    assert list_url(source) == "https://www.bizinfo.go.kr/uss/rss/bizinfoApi.do?crtfcKey=KEY&dataType=json&searchCnt=0"
    page = source.parse_list(s.bizinfo_list([s.BIZINFO_STARTUP, s.BIZINFO_SME]))
    startup, sme = page.items
    assert page.total == 2 and startup.marker == "2026-10-16 15:23:38"
    assert source.wanted(startup) and not source.wanted(sme)
    assert source.wanted(replace_record(sme, pblancNm="[서울] 스타트업 사무공간 입주기업 모집"))  # 분야가 달라도 창업 단어 + 공간
    assert source.wanted(replace_record(sme, pldirSportRealmLclasCodeNm="금융", pldirSportRealmMlsfcCodeNm="보증", pblancNm="[광주] 청년창업특례보증 공고"))
    assert not source.wanted(replace_record(startup, pblancNm="[경북] 2026 창업 아이디어 경진대회"))  # 창업 분야라도 행사
    detail = source.parse_detail(startup, [])
    assert [(f.name, f.index) for f in detail.files] == [
        ("2026년 청년농업인 기술창업 지원사업 공고문.pdf", 0),
        ("참가 신청서.hwp", 1),
        ("공고문 원본.hwpx", 2),
    ]
    assert (detail.region_scope, detail.region_names) == ("SIDO", ["경북"])
    assert detail.benefit.splitlines() == ["청년농업인의 기술창업을 지원합니다.", "☞ 1인당 최대 3천만원 지원", "※ 자세한 지원대상 공고문 참조"]
    body = source.body_text(detail.record)
    assert "사업 개요: 청년농업인의 기술창업을 지원합니다." in body and "<p>" not in body and "조회수" not in body
    assert detail.record["bsnsSumryCn"].startswith("<p>")  # 원본은 그대로


def replace_record(item, **changes):
    from mujuthaek_collector.sources.policy.base import PolicyItem

    return PolicyItem(item.policy_id, changes.get("pblancNm", item.title), item.marker, {**item.record, **changes})


@pytest.mark.parametrize(
    ("title", "agency", "expected"),
    [
        ("[대구ㆍ경북] 창업 공고", "대구광역시", ("SIDO", ["대구", "경북"])),
        ("[전국] 창업 공고", "중소벤처기업부", ("NATIONWIDE", [])),
        ("[비수도권] 창업 공고", "중소벤처기업부", ("UNKNOWN", [])),
        ("2026 창업 공고", "중소벤처기업부", ("NATIONWIDE", [])),
        ("2026 창업 공고", "창업진흥원", ("UNKNOWN", [])),
    ],
)
def test_bizinfo_region(title, agency, expected):
    assert bizinfo_region(title, agency) == expected


def test_bizinfo_error_response():
    with pytest.raises(BlockedError):
        BizinfoSource("KEY").parse_list('{"reqErr": "등록되지 않은 인증키입니다"}')
    with pytest.raises(ParseError):
        BizinfoSource("KEY").parse_list('{"result": "점검 중"}')


def test_region_codes_from_zip():
    assert region_codes("1100000000, 1168000000") == ["11000", "11680"]
    assert region_codes(None) == []


def test_encoded_key_is_decoded_once():
    assert normalize_key("abc%2Bdef%3D%3D") == "abc+def=="
    assert normalize_key(" plain ") == "plain" and normalize_key("") is None


def test_xml_lists_stay_lists():
    from lxml import etree

    root = etree.fromstring("<r><a>1</a><basfrmList><x>1</x></basfrmList><b>2</b><b>3</b></r>")
    assert xml_to_dict(root) == {"a": "1", "basfrmList": [{"x": "1"}], "b": ["2", "3"]}


def test_file_links_on_origin_page():
    links = file_links(s.ORIGIN_PAGE, "https://www.incheon.go.kr/board/view.do?id=3301")
    assert [f.name for f in links] == ["2026년 청년월세지원 모집공고문.hwp", "신청서 서식.hwp", "선정자 명단.xlsx"]
    assert links[0].url == "https://www.incheon.go.kr/cmm/fms/FileDown.do?atchFileId=F1&fileSn=0"
    assert links[2].personal  # 명단은 파이프라인이 받지 않아요


def test_file_links_ignore_plain_links_and_viewers():
    html = """<a href="/files/guide.pdf">안내</a> <a href="/view.do?id=1">사업계획.pdf 보기</a>
      <a href="/viewer.do?file=a.pdf">a.pdf</a> <a href="javascript:down('a.hwp')">a.hwp</a>"""
    links = file_links(html, "https://a.go.kr/board/")
    assert [(f.name, f.url) for f in links] == [("guide.pdf", "https://a.go.kr/files/guide.pdf")]


def test_download_link_without_file_name_uses_nearby_text():
    links = file_links(s.BOARD_PAGE, "https://www.chungnam.go.kr/cnportal/bbs/B0000238/view.do?nttId=1")
    assert [(f.name, f.url) for f in links] == [
        ("충청남도 청년농업인 지원정책(pdf) - 배포.pdf", "https://www.chungnam.go.kr/cnportal/cmmn/file/fileDown.do?atchFileId=abc&fileSn=1")
    ]


def test_main_text_keeps_content_inside_lnb_wrapper():
    from mujuthaek_collector.sources.policy.attachments import main_text, relevant

    text = main_text(s.BOARD_PAGE)
    assert "영농정착지원금 월 최대 110만원" in text and "지원대상: 도내 거주 만 18세 이상" in text
    assert "도정소식" not in text and "개인정보처리방침" not in text  # 메뉴·하단은 빠져요
    assert relevant("청년 농촌보금자리 조성사업", text) and relevant("2026년 충청남도 청년농업인 지원정책", text)


def test_main_text_of_home_page_is_not_relevant():
    from mujuthaek_collector.sources.policy.attachments import main_text, relevant

    title = "2026년 전주 청년만원주택 청춘★별채 예비입주자 모집"
    text = main_text(s.HOME_PAGE.replace("</dd>", " 채용 박람회는 매달 열려요." * 10 + "</dd>"))
    assert "구직자" in text and not relevant(title, text)  # 글은 잡히지만 정책과 상관없어서 쓰지 않아요
    assert main_text("<html><body><div id='app'></div><script>render()</script></body></html>") is None  # 스크립트로 그리는 화면


@pytest.mark.parametrize(
    ("name", "expected"),
    [
        ("2026년 청년월세 지원 모집공고문.hwp", True),
        ("사업 안내.pdf", True),
        ("청년전세임대 안내문", True),  # 확장자가 없으면 받아서 형식을 확인해요
        ("참가 신청서.hwp", False),
        ("개인정보 수집 동의서.hwpx", False),
        ("모집 포스터(최종).pdf", False),
        ("홍보물.pdf", False),
        ("포스터.png", False),
        ("선정자 명단.pdf", False),
        ("참고자료.xlsx", False),
    ],
)
def test_only_explanatory_documents_are_kept(name, expected):
    assert explains(FileRef(name, "https://example.go.kr/f", 0)) is expected


def test_sniff_and_text_order():
    assert sniff_ext(b"%PDF-1.7") == "pdf" and sniff_ext(b"PK\x03\x04....mimetypeapplication/hwp+zip") == "hwpx"
    named = with_ext(FileRef("입주 신청서", "https://a.kr/download.do?id=7", 0), b"%PDF-1.4")
    assert named.name == "입주 신청서.pdf"
    files = [
        FileRef("신청서.hwp", "u1", 0),
        FileRef("참고자료.pdf", "u2", 1),
        FileRef("사업 안내.hwp", "u3", 2),
        FileRef("[별지 1] 사회보장급여 신청(변경)서.hwp", "u4", 3),
    ]
    assert [f.name for f in text_order(files)] == ["사업 안내.hwp", "참고자료.pdf"]
