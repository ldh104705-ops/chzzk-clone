// k6 load test for the autoscaling demo (assignment 5). It hammers the public read endpoints and, for a share of the
// iterations, the CPU-heavy login (bcrypt) and an authenticated comment post, so the backend pods' CPU climbs above the
// HPA target (50% of the 250m request) and Kubernetes adds replicas. When the test ends the load stops and the HPA
// removes the extra replicas again.
//
// Run (no local k6 install needed):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8090 grafana/k6 run - < load/k6/scale-test.js
// Shorter / lighter runs:
//   ... -e PEAK_VUS=30 -e HOLD=2m ...
//
// Environment (all optional):
//   BASE_URL     where the app listens                       (default http://localhost:8090)
//   PEAK_VUS     virtual users at the plateau                (default 80)
//   RAMP_UP      time to reach PEAK_VUS                      (default 30s)
//   HOLD         time at PEAK_VUS                            (default 4m)
//   RAMP_DOWN    time to go back to 0                        (default 10s)
//   LOGIN_RATIO  share of iterations that log in (bcrypt)    (default 0.15)
//   POST_RATIO   share of iterations that post a comment     (default 0.10)
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8090').replace(/\/+$/, '');
const PEAK_VUS = parseInt(__ENV.PEAK_VUS || '80', 10);
const LOGIN_RATIO = parseFloat(__ENV.LOGIN_RATIO || '0.15');
const POST_RATIO = parseFloat(__ENV.POST_RATIO || '0.10');

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP_UP || '30s', target: PEAK_VUS },
        { duration: __ENV.HOLD || '4m', target: PEAK_VUS },
        { duration: __ENV.RAMP_DOWN || '10s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // Scaling must not cost correctness: errors stay rare even while pods are added.
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<3000'],
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };
const PASSWORD = 'k6-load-test-pw1';

/** Runs once before the load: creates a user and makes sure at least one broadcast (to read and comment on) exists. */
export function setup() {
  const username = 'k6_' + Math.random().toString(36).slice(2, 12);
  const signup = http.post(`${BASE_URL}/api/auth/signup`,
    JSON.stringify({ username, password: PASSWORD, nickname: 'k6' }), { headers: JSON_HEADERS });
  check(signup, { 'signup 201': (r) => r.status === 201 });

  const login = http.post(`${BASE_URL}/api/auth/login`,
    JSON.stringify({ username, password: PASSWORD }), { headers: JSON_HEADERS });
  check(login, { 'login 200': (r) => r.status === 200 });
  const token = login.json('token');
  const auth = { Authorization: `Bearer ${token}` };

  let list = http.get(`${BASE_URL}/api/broadcasts`).json();
  if (!Array.isArray(list) || list.length === 0) {
    const created = http.post(`${BASE_URL}/api/broadcasts/recorded`,
      { title: 'k6 load test broadcast', videoUrl: 'https://example.com/sample.mp4' }, { headers: auth });
    check(created, { 'broadcast created 201': (r) => r.status === 201 });
    list = http.get(`${BASE_URL}/api/broadcasts`).json();
  }
  return { token, username, broadcastId: list[0].id };
}

export default function (data) {
  const auth = { headers: { Authorization: `Bearer ${data.token}` } };

  // Public reads: the bulk of the traffic.
  check(http.get(`${BASE_URL}/api/broadcasts`), { 'list 200': (r) => r.status === 200 });
  check(http.get(`${BASE_URL}/api/broadcasts/${data.broadcastId}`), { 'detail 200': (r) => r.status === 200 });
  check(http.get(`${BASE_URL}/api/broadcasts/${data.broadcastId}/comments`), { 'comments 200': (r) => r.status === 200 });

  // The same JWT must be accepted by whichever pod answers (shared secret + shared denylist).
  check(http.get(`${BASE_URL}/api/auth/me`, auth), { 'me 200 (any pod)': (r) => r.status === 200 });

  if (Math.random() < LOGIN_RATIO) {
    // bcrypt makes login the most CPU-hungry endpoint: good for pushing the HPA.
    const r = http.post(`${BASE_URL}/api/auth/login`,
      JSON.stringify({ username: data.username, password: PASSWORD }), { headers: JSON_HEADERS });
    check(r, { 'login 200': (x) => x.status === 200 });
  }

  if (Math.random() < POST_RATIO) {
    const r = http.post(`${BASE_URL}/api/broadcasts/${data.broadcastId}/comments`,
      JSON.stringify({ content: 'k6 comment ' + Date.now() }),
      { headers: { ...JSON_HEADERS, Authorization: `Bearer ${data.token}` } });
    check(r, { 'comment 201': (x) => x.status === 201 });
  }

  sleep(0.05);
}
