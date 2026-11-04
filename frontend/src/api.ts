/**
 * The console's only network layer.
 *
 * HtmlUnit 5.7 can drive XMLHttpRequest reliably, but not fetch(), so every
 * read goes through this module. The tenant is sent only in X-Tenant-Id; the
 * server never accepts a query/body tenant.
 */

export const PAGE_SIZE = 100;

export interface JsonResponse<T> {
  status: number;
  body: T | null;
}

export interface JobDto {
  id: string;
  tenant_id: string;
  state: string;
}

export interface ReplayDto {
  input_sha: string;
  output_sha: string;
  match: boolean;
}

export interface AuditEventDto {
  id: string;
  tenant_id?: string;
  job_id?: string;
  seq: number;
  prev_hash: string;
  event_hash: string;
  type: string;
  payload: unknown;
  created_at?: string;
}

export interface InspectBundle {
  job: JsonResponse<JobDto>;
  events: JsonResponse<AuditEventDto[]>;
  replay: JsonResponse<ReplayDto>;
}

export function jobPath(jobId: string): string {
  return `/api/jobs/${encodeURIComponent(jobId)}`;
}

export function eventsPath(jobId: string, cursor?: string | number | null): string {
  const base = `/audit/events?job_id=${encodeURIComponent(jobId)}&limit=${PAGE_SIZE}`;
  return cursor == null ? base : `${base}&cursor=${encodeURIComponent(String(cursor))}`;
}

export function replayPath(jobId: string): string {
  return `/audit/replay?job_id=${encodeURIComponent(jobId)}`;
}

/**
 * GET a JSON resource. Every outcome, including transport failures, resolves
 * to a status/body pair so callers can keep the three-read verdict simple.
 */
export function requestJson<T>(path: string, tenant: string): Promise<JsonResponse<T>> {
  return new Promise((resolve) => {
    let settled = false;

    const finish = (status: number, body: T | null): void => {
      if (settled) {
        return;
      }
      settled = true;
      resolve({ status, body });
    };

    try {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', path, true);
      xhr.onreadystatechange = () => {
        if (xhr.readyState !== XMLHttpRequest.DONE) {
          return;
        }
        let body: T | null = null;
        if (xhr.responseText) {
          try {
            body = JSON.parse(xhr.responseText) as T;
          } catch {
            body = null;
          }
        }
        finish(xhr.status, body);
      };
      xhr.onerror = () => finish(0, null);
      xhr.ontimeout = () => finish(0, null);
      xhr.setRequestHeader('X-Tenant-Id', tenant);
      xhr.send();
    } catch {
      // A blocked or invalid request is not a job verdict.
      finish(0, null);
    }
  });
}

/**
 * Run the three reads concurrently. The console renders only after all three
 * settle, so a 401/404 verdict is the same regardless of response timing.
 *
 * Deliberately written with Promise.then rather than async/await: HtmlUnit's
 * Rhino-based parser (used by the Java acceptance test) does not accept async
 * function syntax in the production bundle.
 */
export function inspectJob(tenant: string, jobId: string): Promise<InspectBundle> {
  return Promise.all([
    requestJson<JobDto>(jobPath(jobId), tenant),
    requestJson<AuditEventDto[]>(eventsPath(jobId), tenant),
    requestJson<ReplayDto>(replayPath(jobId), tenant),
  ]).then(([job, events, replay]) => ({ job, events, replay }));
}

export function loadMoreEvents(
  tenant: string,
  jobId: string,
  cursor: string,
): Promise<JsonResponse<AuditEventDto[]>> {
  return requestJson<AuditEventDto[]>(eventsPath(jobId, cursor), tenant);
}
