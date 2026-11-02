// Load on AI extraction (#79). Each iteration submits one document and
// extracts it: POST /api/documents, then POST /api/documents/{id}/extract.
// Iterations start at a constant arrival rate (RATE per TIME_UNIT for
// DURATION), whatever the server's latency, so a slow server shows up as
// latency, errors and dropped iterations rather than as a lower offered load.
//
// The documents are the frozen full-v1 quality texts, each with a unique
// reference appended, so every extraction is a new version and a model call:
// the cache never answers. Run by LoadRig (task load:*), which starts the app
// with a stub model and writes the summary next to its report.
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:18080';
const TENANTS = parseInt(__ENV.TENANTS || '20', 10);
const RUN = __ENV.RUN || 'run';

const docs = new SharedArray('docs', () =>
  open('/sets/full-v1/quality.jsonl').split('\n').filter((l) => l.trim() !== '').map((l) => JSON.parse(l).text));

const scenarios = {
  main: {
    executor: 'constant-arrival-rate',
    rate: parseInt(__ENV.RATE, 10),
    timeUnit: __ENV.TIME_UNIT || '1s',
    duration: __ENV.DURATION,
    preAllocatedVUs: parseInt(__ENV.PRE_VUS || '100', 10),
    maxVUs: parseInt(__ENV.MAX_VUS || '1000', 10),
    gracefulStop: '35s',
    tags: { phase: 'main' },
  },
};
if (__ENV.RECOVERY_RATE) {
  // After the main phase: a light load to show whether latency returns to normal.
  scenarios.recovery = {
    executor: 'constant-arrival-rate',
    rate: parseInt(__ENV.RECOVERY_RATE, 10),
    timeUnit: '1s',
    duration: __ENV.RECOVERY_DURATION,
    startTime: __ENV.RECOVERY_START,
    preAllocatedVUs: 50,
    maxVUs: 200,
    tags: { phase: 'recovery' },
  };
}

export const options = {
  scenarios,
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // Declared so the summary carries each phase's own numbers; they never fail the run.
    'extract_ms{phase:main}': ['max>=0'],
    'extract_ms{phase:recovery}': ['max>=0'],
    'iteration_errors{phase:main}': ['rate>=0'],
    'iteration_errors{phase:recovery}': ['rate>=0'],
  },
};

const submitMs = new Trend('submit_ms', true);
const extractMs = new Trend('extract_ms', true);
const iterationErrors = new Rate('iteration_errors');
const timeouts = new Counter('timeouts');
const serverErrors = new Counter('server_errors');
const clientErrors = new Counter('client_errors');
const fallbacks = new Counter('fallbacks');

function count(res) {
  if (res.status === 0) {
    timeouts.add(1);
  } else if (res.status >= 500) {
    serverErrors.add(1);
  } else if (res.status >= 400) {
    clientErrors.add(1);
  }
}

export default function () {
  const i = exec.scenario.iterationInTest;
  const phase = exec.scenario.name;
  const tenant = 't_load_' + String(i % TENANTS).padStart(2, '0');
  const body = docs[i % docs.length] + ' Load reference ' + RUN + '-' + phase + '-' + i + '.';
  const submit = http.post(`${BASE}/api/documents`,
    JSON.stringify({ title: 'load ' + i, mime_type: 'text/plain', body }),
    {
      headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': tenant,
        'Idempotency-Key': RUN + '-' + phase + '-' + i },
      tags: { name: 'submit' },
      timeout: '30s',
    });
  submitMs.add(submit.timings.duration);
  if (submit.status !== 202) {
    count(submit);
    iterationErrors.add(true);
    return;
  }
  const id = submit.json('doc_id');
  const extract = http.post(`${BASE}/api/documents/${id}/extract`, null,
    { headers: { 'X-Tenant-Id': tenant }, tags: { name: 'extract' }, timeout: '30s' });
  extractMs.add(extract.timings.duration);
  if (extract.status !== 200) {
    count(extract);
    iterationErrors.add(true);
    return;
  }
  if (extract.json('fallback') === true) {
    fallbacks.add(1);
  }
  iterationErrors.add(false);
}

export function handleSummary(data) {
  return { '/out/k6-summary.json': JSON.stringify(data, null, 2) };
}
