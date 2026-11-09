import http from 'k6/http';
import exec from 'k6/execution';
import { sleep, check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const input = JSON.parse(open(__ENV.PERF_INPUT));
const vus = Number(__ENV.PERF_VUS), seconds = Number(__ENV.PERF_SECONDS);
const warmup = __ENV.PERF_PHASE === 'warmup';
const queue = __ENV.PERF_MODE === 'redis-queue';
const stride = warmup ? 5 : seconds + 20;
if (![1, 10, 50, 100].includes(vus)) throw new Error('이번 단계는 최대 100 VU입니다.');
const apiLatency = new Trend('api_latency', true);
const queueWait = new Trend('queue_wait', true);
const journey = new Trend('booking_journey', true);
const processing = new Trend('booking_processing', true);
const completed = new Counter('booking_created');
const inWindow = new Counter('window_booking_created');
const requests = new Counter('window_http_requests');
const businessRequests = new Counter('window_booking_requests');
const admitted = new Counter('admissions_observed');
const rejected = new Counter('business_rejections');
const timeout = new Counter('timeouts');
const failures = new Counter('http_5xx');
const networkFailures = new Counter('network_failures');
const success = new Rate('journey_success');
const count = new Counter('journey_started');
const thresholds = { journey_success: ['rate>=0.99'], http_5xx: ['count==0'], timeouts: ['count==0'] };
for (const api of ['hold', 'confirm', ...(queue ? ['queue_join', 'queue_leave'] : [])]) {
  thresholds[`api_latency{api:${api}}`] = ['p(95)>=0'];
}
export const options = {
  scenarios: { booking: warmup ? { executor: 'per-vu-iterations', vus, iterations: 5, maxDuration: '90s' } :
    { executor: 'constant-vus', vus, duration: `${seconds}s`, gracefulStop: '20s' } },
  thresholds, summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'count'],
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
};
function insideWindow() { return warmup || Date.now() < exec.scenario.startTime + seconds * 1000; }
function send(api, method, path, token, body) {
  const within = insideWindow();
  const res = http.request(method, input.base + '/api/v1' + path, body === null ? null : JSON.stringify(body), {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, timeout: '15s',
    tags: { name: api, api }, responseCallback: http.expectedStatuses(200, 201, 400, 403, 409, 410, 429),
  });
  apiLatency.add(res.timings.duration, { api });
  if (within) { requests.add(1); if (api === 'hold' || api === 'confirm') businessRequests.add(1); }
  failures.add(res.status >= 500 ? 1 : 0, { api });
  networkFailures.add(res.status === 0 && res.error_code !== 1050 ? 1 : 0, { api });
  timeout.add(res.error_code === 1050 ? 1 : 0, { api });
  if (res.status >= 400 && res.status < 500) {
    let code = 'UNKNOWN'; try { code = res.json('code'); } catch (_) {}
    rejected.add(1, { api, status: String(res.status), code });
  } else rejected.add(0, { api });
  return res;
}
export default function () {
  const index = (__VU - 1) * stride + __ITER + 1;
  if (__ITER >= stride) { check(false, { '좌석 데이터 미소진': x => x }); exec.test.abort('좌석 데이터 소진'); }
  const token = input.tokens[__VU - 1], concert = input.concert;
  const started = Date.now(); count.add(1);
  let hold = null, ok = false;
  try {
    if (queue) {
      let response = send('queue_join', 'POST', `/concerts/${concert}/queue`, token, null);
      if (response.status !== 200 && response.status !== 201) return;
      let state = response.json('data.status');
      while (state === 'WAITING' && Date.now() - started < 30000) {
        sleep(0.5);
        response = send('queue_status', 'GET', `/concerts/${concert}/queue/me`, token, null);
        if (response.status !== 200) return;
        state = response.json('data.status');
      }
      if (state !== 'ADMITTED') return;
      admitted.add(1); queueWait.add(Date.now() - started);
    } else queueWait.add(0);
    const businessStart = Date.now();
    const response = send('hold', 'POST', `/concerts/${concert}/seat-holds`, token,
      { seatIds: ['S' + String(index).padStart(6, '0')] });
    if (response.status !== 201) return;
    hold = response.json('data.holdId');
    const confirmed = send('confirm', 'POST', '/bookings', token, { holdId: hold, paymentIdentifier: 'test-pay-shared-01' });
    if (confirmed.status !== 201) return;
    ok = confirmed.json('success') === true;
    if (ok) {
      completed.add(1); if (insideWindow()) inWindow.add(1);
      processing.add(Date.now() - businessStart); journey.add(Date.now() - started);
    }
  } finally {
    success.add(ok);
    if (!ok && hold) send('hold_cleanup', 'DELETE', `/seat-holds/${hold}`, token, null);
    // 클라이언트가 기존 leave API를 명시적으로 호출한다. 서버 자동 반환이 아니다.
    if (queue) send('queue_leave', 'DELETE', `/concerts/${concert}/queue/me`, token, null);
    sleep(1);
  }
}
export function handleSummary(data) {
  data.experiment = { mode: __ENV.PERF_MODE, vus, phase: __ENV.PERF_PHASE, plannedSeconds: seconds,
    explicitClientLeave: queue, serverAutoRelease: false, uniqueSeatPerJourney: true, thinkSeconds: 1,
    metricsAttribution: '요청은 시작 시각, window_booking_created는 응답 수신 시각 기준. grace 완료는 별도 총계.' };
  return { [__ENV.PERF_SUMMARY]: JSON.stringify(data, null, 2) };
}
