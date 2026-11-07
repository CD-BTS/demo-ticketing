import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const base = __ENV.BASE_URL || 'http://127.0.0.1:18086';
const concert = __ENV.TEST_CONCERT;
const run = __ENV.TEST_RUN;
const userPrefix = __ENV.TEST_USER_PREFIX;
const mode = __ENV.TEST_MODE || 'same';
const n = Number(__ENV.TEST_VUS || 2);
if (!/^[a-z0-9_-]+$/.test(concert || '') || !/^[a-z0-9_-]+$/.test(run || '') ||
    !/^k23_[a-f0-9]{12}_[sdouc](2|10|100)_$/.test(userPrefix || '') ||
    !['same', 'different', 'overlap', 'same-user', 'confirm'].includes(mode) || ![2, 10, 100].includes(n)) {
  throw new Error('전용 실행기로 생성한 공연·실행 ID와 유효한 모드/VU가 필요합니다.');
}
const created = new Counter('race_created');
const conflicts = new Counter('race_conflicts');
const reused = new Counter('race_reused');
const attempts = new Counter('race_attempts');
const duration = new Trend('race_duration', true);
const expectedCreated = mode === 'different' || mode === 'same-user' ? n : 1;
export const options = {
  scenarios: { seats: { executor: 'per-vu-iterations', vus: n, iterations: 1, maxDuration: '60s' } },
  setupTimeout: '180s',
  thresholds: {
    checks: ['rate==1'],
    race_attempts: [`count==${n}`],
    race_created: [`count==${expectedCreated}`],
    race_conflicts: [`count==${mode === 'same' || mode === 'overlap' ? n - 1 : 0}`],
    race_reused: [`count==${mode === 'confirm' ? n - 1 : 0}`],
    'http_req_failed{phase:race}': ['rate==0'],
  },
};
function request(path, body, token, phase = 'setup') {
  return http.post(`${base}/api/v1${path}`, body === null ? null : JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    timeout: '30s', tags: { phase }, responseCallback: http.expectedStatuses(200, 201, 409),
  });
}
function successful(response) {
  if (response.status !== 200 && response.status !== 201) fail(`테스트 준비 실패: HTTP ${response.status}`);
  return response.json('data');
}
export function setup() {
  const tokens = [];
  const accounts = mode === 'same-user' || mode === 'confirm' ? 1 : n;
  for (let i = 0; i < accounts; i++) {
    const login = successful(request('/users', { userId: `${userPrefix}${i}` }));
    const admission = successful(request(`/concerts/${concert}/queue`, null, login.token));
    if (admission.status !== 'ADMITTED') fail('대기열 정원이 부족합니다. 전용 서버의 QUEUE_CAPACITY=150을 확인하세요.');
    tokens.push(login.token);
  }
  const hold = mode === 'confirm' ? successful(request(`/concerts/${concert}/seat-holds`, { seatIds: ['S1'] }, tokens[0])) : null;
  // 로그인·대기열 등록은 경쟁 구간에서 제외한다. 모든 VU는 같은 시각까지 대기한다.
  return { tokens, hold, startAt: Date.now() + 1500 };
}
export default function (data) {
  sleep(Math.max(0, data.startAt - Date.now()) / 1000);
  const token = data.tokens.length === 1 ? data.tokens[0] : data.tokens[__VU - 1];
  const seats = mode === 'overlap' ? (__VU % 2 ? ['S1', 'S2'] : ['S2', 'S3']) :
    [mode === 'different' || mode === 'same-user' ? `S${__VU}` : 'S1'];
  const response = mode === 'confirm' ? request('/bookings', {
    holdId: data.hold.holdId, paymentIdentifier: 'test-pay-shared-01',
  }, token, 'race') : request(`/concerts/${concert}/seat-holds`, { seatIds: seats }, token, 'race');
  attempts.add(1); duration.add(response.timings.duration);
  created.add(response.status === 201 ? 1 : 0);
  conflicts.add(response.status === 409 ? 1 : 0);
  reused.add(response.status === 200 ? 1 : 0);
  let body; try { body = response.json(); } catch (_) { body = {}; }
  check(response, {
    '예상한 HTTP 응답과 업무 코드': () =>
      (response.status === 201 && body.success === true && body.code === 'COMMON_201') ||
      (mode === 'confirm' && response.status === 200 && body.success === true && body.code === 'COMMON_200') ||
      (['same', 'overlap'].includes(mode) && response.status === 409 && body.code === 'SEAT_ALREADY_TAKEN'),
  });
}
export function handleSummary(data) {
  // 세션 토큰과 setup 데이터는 결과 파일에 저장하지 않는다.
  return { [__ENV.SUMMARY_PATH || 'summary.json']: JSON.stringify(data, null, 2) };
}
