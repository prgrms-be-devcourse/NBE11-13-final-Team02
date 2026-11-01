// k6 진입점. TEST(smoke·load·soak·stress·breakpoint·spike, 더미는 load200도)로 부하 모양을 고르고, 사용자 흐름 세 가지를 섞어 돌려요.
//   직접 부를 때: TEST=smoke k6 run perf/k6/main.js   (보통은 perf/run.sh <test>로 불러요: 결과를 폴더에 모아 줘요)
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';
import { SharedArray } from 'k6/data';
import { DATA, MIX, PROFILES, SEED_PROFILES, TEST, WARMUP } from './lib/config.js';
import { accessToken } from './lib/token.js';
import { browse, judge, member, single } from './lib/flows.js';

const profiles = DATA === 'seed' ? SEED_PROFILES : PROFILES;
const profile = TEST === 'warmup' ? WARMUP : profiles[TEST];
if (!profile) throw new Error(`TEST는 ${Object.keys(profiles).join('·')} 중 하나예요(DATA=${DATA}): ${TEST}`);

// 운영 기준: 테스트 계정 하나의 토큰을 setup에서 서명해요. 없으면 로그인 흐름(15%)은 판정 흐름으로 돌려요
// 더미 기준: make-tokens.sh로 미리 만든 더미 회원 토큰(TOKENS_FILE)을 VU마다 돌려 써요
// run.sh가 meta.json에 member 여부·토큰 수를 남겨요
const SECRET = __ENV.JWT_SECRET;
const USER_ID = __ENV.TEST_USER_ID;
const TOKENS = __ENV.TOKENS_FILE ? new SharedArray('tokens', () => JSON.parse(open(__ENV.TOKENS_FILE)).tokens) : [];
const withMember = TOKENS.length > 0 || Boolean(SECRET && USER_ID);

export const options = {
  scenarios: { [TEST]: { ...profile.scenario, exec: TEST === 'breakpoint' ? 'oneRequest' : 'userFlow' } },
  thresholds: profile.thresholds,
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  // url 태그를 빼야 Prometheus 시계열이 공고 id마다 늘지 않아요(name 태그로 묶어요)
  systemTags: ['status', 'method', 'name', 'group', 'check', 'scenario', 'expected_response', 'error_code'],
  discardResponseBodies: true,
  noConnectionReuse: false,
};

export function setup() {
  return { token: !TOKENS.length && withMember ? accessToken(SECRET, USER_ID) : null };
}

const tokenOf = (ctx) => (TOKENS.length ? TOKENS[(__VU - 1) % TOKENS.length] : ctx.token);

export function userFlow(ctx) {
  const token = tokenOf(ctx);
  const x = Math.random();
  if (x < MIX.browse) browse();
  else if (x < MIX.browse + MIX.judge || !token) judge();
  else member(token);
}

export function oneRequest(ctx) {
  single(tokenOf(ctx));
}

export function handleSummary(data) {
  const out = __ENV.OUT_DIR ? `${__ENV.OUT_DIR}/summary.json` : 'summary.json';
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
    [out]: JSON.stringify(data, null, 2),
  };
}
