// 동시성 시나리오 1~3: VU 100명이 모두 준비된 뒤 같은 시각에 요청 한 번씩 (perf/concurrency/run.sh가 회마다 불러요)
//   SCENARIO=1  같은 회원·같은 공고 관심 등록(PUT) 100번
//   SCENARIO=2  같은 회원·같은 공고 관심 등록·해제 섞기 (짝수 VU PUT, 홀수 VU DELETE)
//   SCENARIO=3  같은 회원 알림 설정·관심 조건 저장을 VU마다 다른 값으로 (값에 VU 번호를 새겨 섞였는지 볼 수 있게)
// 응답 코드는 code_* 카운터로 summary.json에 남아요. DB 확인은 run.sh가 해요
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL;
const TOKEN = __ENV.TOKEN;
const SCENARIO = __ENV.SCENARIO;
const NOTICE_ID = __ENV.NOTICE_ID;
const REGIONS = JSON.parse(__ENV.REGIONS || '[]');
const VUS = Number(__ENV.VUS || 100);

// 관심 조건 공고 종류 4개: VU i는 지역 REGIONS[i % 40], 종류 TYPES[i % 4] → 저장된 (지역, 종류)가 한 VU의 짝이어야 해요
export const TYPES = ['HAPPY_HOUSING', 'NATIONAL_RENTAL', 'PUBLIC_SALE', 'PERMANENT_RENTAL'];

const CODES = [200, 201, 204, 400, 401, 403, 404, 409, 422, 429, 500, 502, 503, 504];
const counters = Object.fromEntries(CODES.map((c) => [c, new Counter(`code_${c}`)]));
const other = new Counter('code_other');

export const options = {
  scenarios: { at_once: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '60s' } },
  summaryTrendStats: ['avg', 'p(95)', 'max'],
};

export function setup() {
  return { startAt: Date.now() + 3000 }; // 모든 VU가 이 시각까지 기다렸다 같이 출발
}

function count(res) {
  (counters[res.status] || other).add(1);
}

export default function (ctx) {
  const wait = (ctx.startAt - Date.now()) / 1000;
  if (wait > 0) sleep(wait);
  const i = exec.vu.idInTest - 1;
  const auth = { headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' } };
  const favorite = `${BASE_URL}/api/users/me/favorites/NOTICE/${NOTICE_ID}`;
  if (SCENARIO === '1') {
    count(http.put(favorite, null, auth));
  } else if (SCENARIO === '2') {
    count(i % 2 === 0 ? http.put(favorite, null, auth) : http.del(favorite, null, auth));
  } else if (SCENARIO === '3') {
    const settings = {
      newNotice: i % 2 === 0,
      newSupport: i % 2 === 0,
      deadline: i % 3 === 0,
      correction: i % 3 === 0,
      followUp: i % 5 === 0,
      quietEnabled: i % 5 === 0,
    };
    const housing = { regionCodes: [REGIONS[i % REGIONS.length]], includeNationwide: i % 2 === 0, noticeTypes: [TYPES[i % 4]] };
    const res = http.batch([
      ['PUT', `${BASE_URL}/api/users/me/notification-settings`, JSON.stringify(settings), auth],
      ['PUT', `${BASE_URL}/api/users/me/interests/housing`, JSON.stringify(housing), auth],
    ]);
    res.forEach(count);
  } else {
    throw new Error(`SCENARIO는 1·2·3: ${SCENARIO}`);
  }
}

export function handleSummary(data) {
  const codes = {};
  for (const [name, m] of Object.entries(data.metrics)) {
    if (name.startsWith('code_') && m.values.count > 0) codes[name.slice(5)] = m.values.count;
  }
  return { [__ENV.OUT]: JSON.stringify({ codes, duration: data.metrics.http_req_duration?.values }, null, 2) };
}
