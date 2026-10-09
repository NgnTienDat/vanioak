import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

function required(name) {
  const value = __ENV[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function positiveInteger(name, fallback) {
  const value = Number(__ENV[name] === undefined ? fallback : __ENV[name]);
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new Error(`${name} must be a positive integer`);
  }
  return value;
}

if (__ENV.TEST_ENVIRONMENT_APPROVED !== 'true') {
  throw new Error('Set TEST_ENVIRONMENT_APPROVED=true only for an explicitly approved test environment');
}
const baseUrl = required('BASE_URL').replace(/\/+$/, '');
// Keep credentials out of URL tags and exported summaries.
if (!/^https?:\/\/[^\s/?#@]+(?::\d+)?$/.test(baseUrl)) {
  throw new Error('BASE_URL must be an HTTP(S) origin without credentials, path, query or fragment');
}
const apiKey = required('API_KEY');
const application = required('APPLICATION');
const environment = required('ENVIRONMENT');
if (application.length > 100 || !/^(DEV|TEST|STAGING|dev|test|staging)$/.test(environment)) {
  throw new Error('APPLICATION or ENVIRONMENT does not match the ingestion contract');
}
const mode = __ENV.MODE || 'single';
if (mode !== 'single' && mode !== 'batch') throw new Error('MODE must be single or batch');
const batchSize = positiveInteger('BATCH_SIZE', 100);
if (batchSize > 1000) throw new Error('BATCH_SIZE must not exceed 1000');
const logsPerRequest = mode === 'single' ? 1 : batchSize;
const targetLogsPerSecond = positiveInteger('TARGET_LOGS_PER_SEC', 1000);
const duration = __ENV.DURATION || '30s';
if (!/^[1-9]\d*(s|m|h)$/.test(duration)) throw new Error('DURATION must use positive whole seconds, minutes or hours');
const preAllocatedVUs = positiveInteger('PRE_ALLOCATED_VUS', mode === 'single' ? 200 : 20);
const maxVUs = positiveInteger('MAX_VUS', mode === 'single' ? 1000 : 200);
if (maxVUs < preAllocatedVUs) throw new Error('MAX_VUS must be at least PRE_ALLOCATED_VUS');
const endpoint = `/api/v1/logs${mode === 'batch' ? '/batch' : ''}`;
const runId = Math.floor(Date.now() / 1000).toString(16).padStart(8, '0');
const successfulRequests = new Counter('successful_requests');
const failedRequests = new Counter('failed_requests');
const acceptedLogs = new Counter('accepted_logs');
const errorRate = new Rate('ingestion_error_rate');
const completionTime = new Trend('http_completion_ms', true);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const options = {
  scenarios: {
    ingestion: {
      executor: 'constant-arrival-rate',
      // Using logsPerRequest seconds also supports non-integer requests/second.
      rate: targetLogsPerSecond,
      timeUnit: `${logsPerRequest}s`,
      duration,
      preAllocatedVUs,
      maxVUs,
      gracefulStop: '30s',
    },
  },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)'],
  thresholds: {
    accepted_logs: ['rate>=1000'],
    http_completion_ms: ['p(95)<100'],
  },
};

export default function () {
  const timestamp = new Date().toISOString();
  const logs = Array.from({ length: logsPerRequest }, (_, index) => ({
    application,
    environment,
    host_ip: '192.0.2.10',
    level: 'INFO',
    message: 'Order request completed successfully',
    timestamp,
    trace_id: runId + __VU.toString(16).padStart(8, '0')
      + __ITER.toString(16).padStart(12, '0') + index.toString(16).padStart(4, '0'),
    metadata: { method: 'POST', route: '/orders', status_code: 200, duration_ms: 42 },
  }));
  const payload = JSON.stringify(mode === 'single' ? logs[0] : { logs });
  const startedAt = Date.now();
  const response = http.post(`${baseUrl}${endpoint}`, payload, {
    headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
    timeout: '10s',
    redirects: 0,
    tags: { name: endpoint },
    responseCallback: http.expectedStatuses(202),
  });
  const elapsed = Date.now() - startedAt;
  let accepted = false;
  if (response.status === 202) {
    try {
      const body = response.json();
      accepted = body !== null && body.success === true && typeof body.message === 'string'
        && body.data !== null && typeof body.data === 'object' && body.data.accepted === true
        && typeof body.data.request_id === 'string' && uuid.test(body.data.request_id)
        && (mode !== 'batch' || body.data.accepted_count === logsPerRequest);
    } catch (_) {
      // Invalid JSON or acceptance fields must never count as accepted logs.
    }
  }
  completionTime.add(elapsed);
  check(response, { '202 with valid acceptance envelope': () => accepted });
  successfulRequests.add(accepted ? 1 : 0);
  failedRequests.add(accepted ? 0 : 1);
  acceptedLogs.add(accepted ? logsPerRequest : 0);
  errorRate.add(!accepted);
}

export function handleSummary(data) {
  const count = (name) => data.metrics[name] ? data.metrics[name].values.count : 0;
  const latency = (name) => data.metrics[name] ? data.metrics[name].values : null;
  const elapsedSeconds = data.state.testRunDurationMs / 1000;
  const throughput = elapsedSeconds > 0 ? count('accepted_logs') / elapsedSeconds : 0;
  const totalRequests = count('http_reqs');
  const p95 = latency('http_completion_ms') && latency('http_completion_ms')['p(95)'];
  const report = {
    configuration: {
      base_url: baseUrl,
      application,
      environment,
      mode,
      logs_per_request: logsPerRequest,
      target_logs_per_second: targetLogsPerSecond,
      configured_requests_per_second: targetLogsPerSecond / logsPerRequest,
      arrival_rate: targetLogsPerSecond,
      arrival_time_unit: `${logsPerRequest}s`,
      scheduled_duration: duration,
      pre_allocated_vus: preAllocatedVUs,
      max_vus: maxVUs,
      request_timeout: '10s',
      graceful_stop: '30s',
    },
    measurements: {
      elapsed_seconds_including_drain: elapsedSeconds,
      total_requests: totalRequests,
      successful_202_requests: count('successful_requests'),
      failed_requests: count('failed_requests'),
      unclassified_requests: totalRequests - count('successful_requests') - count('failed_requests'),
      accepted_logs: count('accepted_logs'),
      actual_accepted_logs_per_second: throughput,
      actual_successful_requests_per_second: elapsedSeconds > 0 ? count('successful_requests') / elapsedSeconds : 0,
      error_rate: data.metrics.ingestion_error_rate ? data.metrics.ingestion_error_rate.values.rate : null,
      dropped_iterations: count('dropped_iterations'),
      http_completion_ms: latency('http_completion_ms'),
      http_req_duration_ms: latency('http_req_duration'),
    },
    assessment: {
      nfr01_1000_logs_per_second: totalRequests > 0 ? (throughput >= 1000 ? 'PASS' : 'FAIL') : 'NOT RUN',
      nfr02_p95_below_100_ms: typeof p95 === 'number' ? (p95 < 100 ? 'PASS' : 'FAIL') : 'NOT RUN',
      sustained_capacity: 'NOT ESTABLISHED by aggregate results alone; review duration, time series and resource usage',
    },
    k6: data,
  };
  const json = JSON.stringify(report, null, 2);
  const output = { stdout: `${json}\n` };
  if (__ENV.SUMMARY_JSON) output[__ENV.SUMMARY_JSON] = `${json}\n`;
  return output;
}
