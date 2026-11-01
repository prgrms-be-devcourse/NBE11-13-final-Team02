// 테스트 종류별 부하 모양과 합격 기준. 문서 「무주택자들 성능 테스트 계획」의 표와 같은 값이에요.
// 개선 전후를 비교하려면 이 파일을 바꾸지 않아야 해요 (바꾸면 run.sh가 meta.json에 git SHA로 남겨요)

export const BASE_URL = __ENV.BASE_URL || 'http://3.37.131.73';
export const TEST = __ENV.TEST || 'smoke';
// prod = 운영 데이터(data.json), seed = 더미(perf/seed, seed.json + 더미 회원 토큰)
export const DATA = __ENV.DATA || 'prod';

// API 종류별 응답 시간 목표(ms): 조회 lookup · 목록·검색 search · 판정 judge
const LATENCY = {
  lookup: ['p(95)<200', 'p(99)<500'],
  search: ['p(95)<500', 'p(99)<1000'],
  judge: ['p(95)<1000', 'p(99)<2000'],
};

// k6 기준(thresholds)은 기록만 해요(합격·불합격이 종료 코드와 요약에 남아요). 어떤 테스트도 k6 기준으로 멈추지 않아요(abortOnFail 없음):
// k6 기준은 시작부터의 누적이라 단계 시작의 흔들림에도 멈췄어요(2026-11-02 스트레스가 첫 단계에서 p99로 1·2분 만에 중단).
// 측정은 정해진 단계를 끝까지 돌고, 한계는 끝난 뒤 단계표(stages.csv)로 판정해요. 끊는 건 run.sh의 stagewatch 안전 장치 두 가지뿐이에요
function latency() {
  const t = {};
  for (const [type, rules] of Object.entries(LATENCY)) {
    t[`http_req_duration{type:${type}}`] = rules;
  }
  return t;
}

const errorRate = (rate) => [`rate<${rate}`];

const vus = (stages) => ({ executor: 'ramping-vus', startVUs: 0, stages, gracefulRampDown: '30s' });

// 50 → 100 → 200 → 400 → 700 → 1,000명, 단계마다 30초 올리고 2분 30초 유지. 첫 단계만 2분에 걸쳐 올려요(0에서 한꺼번에 올리면
// 사용자들이 같은 박자로 움직여 몰림이 오래 남아요. 2026-11-02 로컬: 30초에 100명 → 검색 p95 571ms, 부하 테스트처럼 2분 → 120ms 안팎)
const stepped = (targets) =>
  targets.flatMap((target, i) => [
    { duration: i === 0 ? '2m' : '30s', target },
    { duration: '2m30s', target },
  ]);
const stressStages = stepped([50, 100, 200, 400, 700, 1000]);

// 초당 20건에서 2분마다 20건씩, 최대 초당 1,000건(그 전에 기준을 넘어 멈춰요)
const breakpointStages = Array.from({ length: 50 }, (_, i) => ({ duration: '2m', target: 20 * (i + 1) }));

export const PROFILES = {
  smoke: {
    scenario: { executor: 'constant-vus', vus: 2, duration: '1m' },
    thresholds: { ...latency(), http_req_failed: ['rate==0'], server_errors: ['count==0'] },
  },
  load: {
    scenario: vus([
      { duration: '2m', target: 100 },
      { duration: '15m', target: 100 },
      { duration: '1m', target: 0 },
    ]),
    thresholds: { ...latency(), http_req_failed: errorRate(0.01), server_errors: ['count==0'] },
  },
  soak: {
    scenario: vus([
      { duration: '2m', target: 50 },
      { duration: '60m', target: 50 },
      { duration: '1m', target: 0 },
    ]),
    thresholds: { ...latency(), http_req_failed: errorRate(0.01), server_errors: ['count==0'] },
  },
  stress: {
    scenario: vus(stressStages),
    thresholds: { ...latency(), http_req_failed: errorRate(0.05) },
  },
  // 쉬는 시간 없이 요청 수로 밀어요. 반복 한 번 = 요청 한 건이라 초당 반복 수가 곧 RPS예요
  breakpoint: {
    scenario: {
      executor: 'ramping-arrival-rate',
      startRate: 20,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 1500,
      stages: breakpointStages,
    },
    thresholds: { ...latency(), http_req_failed: errorRate(0.05) },
  },
  spike: {
    scenario: vus([
      { duration: '30s', target: 20 },
      { duration: '10s', target: 500 },
      { duration: '1m', target: 500 },
      { duration: '10s', target: 20 },
      { duration: '2m', target: 20 },
    ]),
    thresholds: { ...latency(), http_req_failed: errorRate(0.05) }, // 종류별 p95·p99가 요약에 남게 (기록만)
  },
};

// 더미(회원 10만) 기준 프로필. 위 PROFILES(운영 기준)와 따로 둬요: 운영 기준 결과와 비교가 깨지지 않게
// 응답 시간·에러율 기준은 같아요
const hold = (target, minutes) => [
  { duration: '2m', target },
  { duration: `${minutes}m`, target },
  { duration: '1m', target: 0 },
];

// 100 → 200 → 400 → 600 → 800 → 1,000명, 단계마다 30초 올리고 2분 30초 유지 (첫 단계만 2분에 걸쳐 올려요)
const seedStressStages = stepped([100, 200, 400, 600, 800, 1000]);

export const SEED_PROFILES = {
  smoke: PROFILES.smoke,
  load: {
    scenario: vus(hold(100, 15)),
    thresholds: { ...latency(), http_req_failed: errorRate(0.01), server_errors: ['count==0'] },
  },
  load200: {
    scenario: vus(hold(200, 15)),
    thresholds: { ...latency(), http_req_failed: errorRate(0.01), server_errors: ['count==0'] },
  },
  stress: {
    scenario: vus(seedStressStages),
    thresholds: { ...latency(), http_req_failed: errorRate(0.05) },
  },
  breakpoint: PROFILES.breakpoint,
  spike: {
    scenario: vus([
      { duration: '30s', target: 20 },
      { duration: '10s', target: 600 },
      { duration: '1m', target: 600 },
      { duration: '10s', target: 20 },
      { duration: '2m', target: 20 },
    ]),
    thresholds: { ...latency(), http_req_failed: errorRate(0.05) }, // 종류별 p95·p99가 요약에 남게 (기록만)
  },
  soak: {
    scenario: vus(hold(100, 60)),
    thresholds: { ...latency(), http_req_failed: errorRate(0.01), server_errors: ['count==0'] },
  },
};

// 흐름 비중(둘러보기 · 판정, 나머지는 로그인). 운영 기준은 60 · 25 · 15, 더미는 로그인 회원이 많아 50 · 25 · 25
export const MIX = DATA === 'seed' ? { browse: 0.5, judge: 0.25 } : { browse: 0.6, judge: 0.25 };

// 본 측정 전 예열(run.sh --warmup): JVM·DB 캐시를 데워 첫 실행만 느린 것을 결과에서 빼요. 기준 없음, Prometheus로 보내지 않아요
export const WARMUP = {
  scenario: {
    executor: 'constant-vus',
    vus: Number(__ENV.WARMUP_VUS || 50),
    duration: `${Number(__ENV.WARMUP_SECONDS || 90)}s`,
  },
  thresholds: {},
};
