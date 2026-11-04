import { useRef, useState, type FormEvent } from 'react';
import {
  PAGE_SIZE,
  inspectJob,
  loadMoreEvents,
  type AuditEventDto,
  type InspectBundle,
  type JobDto,
  type ReplayDto,
} from './api';
import {
  eventClass,
  eventCountLabel,
  formatPayload,
  mergeEvents,
  nextCursor,
  shortHash,
  stateClass,
} from './chain';
import { CopyButton } from './components/CopyButton';
import {
  AlertIcon,
  AnvilMark,
  ChevronIcon,
  KeyIcon,
  LinkIcon,
  SearchIcon,
  ShieldIcon,
  SpinnerIcon,
} from './icons';

const TENANT_NOT_FOUND = 'Tenant not found.';
const JOB_NOT_FOUND = 'Job not found.';
const LOAD_FAILED = 'The job could not be loaded.';
const LOAD_MORE_FAILED = 'The rest of the chain could not be loaded.';
const MISSING_INPUT = 'Enter a tenant and a job ID.';

interface SuccessView {
  kind: 'success';
  tenant: string;
  jobId: string;
  job: JobDto;
  replay: ReplayDto;
  events: AuditEventDto[];
  /** Size of the most recently returned page, used to decide "Load more". */
  pageSize: number;
  loadingMore: boolean;
  notice: string;
}

type View =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | SuccessView
  | { kind: 'not-found' }
  | { kind: 'error'; message: string };

function verdict(bundle: InspectBundle, tenant: string, jobId: string): View {
  const { job, events, replay } = bundle;

  // An unknown tenant is a session-level refusal and wins over any 404.
  if (job.status === 401 || events.status === 401 || replay.status === 401) {
    return { kind: 'error', message: TENANT_NOT_FOUND };
  }

  // Missing, malformed and cross-tenant job IDs all arrive as the same 404.
  if (job.status === 404) {
    return { kind: 'not-found' };
  }

  const jobBody = job.body;
  const replayBody = replay.body;
  const eventBody = events.body;

  if (
    job.status !== 200 ||
    events.status !== 200 ||
    replay.status !== 200 ||
    jobBody === null ||
    replayBody === null ||
    !Array.isArray(eventBody)
  ) {
    return { kind: 'error', message: LOAD_FAILED };
  }

  return {
    kind: 'success',
    tenant,
    jobId,
    job: jobBody,
    replay: replayBody,
    events: eventBody,
    pageSize: eventBody.length,
    loadingMore: false,
    notice: '',
  };
}

export function App() {
  const tenantRef = useRef<HTMLInputElement>(null);
  const jobIdRef = useRef<HTMLInputElement>(null);
  const requestSequence = useRef(0);
  const [view, setView] = useState<View>({ kind: 'idle' });

  const loading = view.kind === 'loading';
  const success = view.kind === 'success' ? view : null;
  const job = success?.job;
  const replay = success?.replay;
  const events = success?.events ?? [];
  const total = events.length;
  const lastEvent = events[events.length - 1];
  const hasMore = success !== null && success.pageSize === PAGE_SIZE;

  const status =
    view.kind === 'loading'
      ? 'Inspecting...'
      : view.kind === 'error'
        ? view.message
        : view.kind === 'success'
          ? view.notice
          : '';
  const statusTone =
    view.kind === 'loading'
      ? ' is-loading'
      : view.kind === 'error'
        ? ' is-error'
        : view.kind === 'success' && view.notice
          ? ' is-notice'
          : '';

  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();

    const tenant = tenantRef.current?.value.trim() ?? '';
    const jobId = jobIdRef.current?.value.trim() ?? '';
    const sequence = requestSequence.current + 1;
    requestSequence.current = sequence;

    if (!tenant || !jobId) {
      setView({ kind: 'error', message: MISSING_INPUT });
      return;
    }

    setView({ kind: 'loading' });
    void inspectJob(tenant, jobId)
      .then((bundle) => {
        if (requestSequence.current !== sequence) {
          return;
        }
        setView(verdict(bundle, tenant, jobId));
      })
      .catch(() => {
        if (requestSequence.current === sequence) {
          setView({ kind: 'error', message: LOAD_FAILED });
        }
      });
  };

  const handleLoadMore = (): void => {
    if (success === null || success.loadingMore) {
      return;
    }
    const cursor = nextCursor(success.events);
    if (cursor === null) {
      return;
    }

    const sequence = requestSequence.current;
    setView({ ...success, loadingMore: true, notice: '' });

    void loadMoreEvents(success.tenant, success.jobId, cursor)
      .then((response) => {
        if (requestSequence.current !== sequence) {
          return;
        }
        setView((current) => {
          if (current.kind !== 'success' || current.jobId !== success.jobId) {
            return current;
          }
          if (response.status === 401) {
            return { kind: 'error', message: TENANT_NOT_FOUND };
          }
          if (response.status === 404) {
            return { kind: 'not-found' };
          }
          if (response.status !== 200 || !Array.isArray(response.body)) {
            return { ...current, loadingMore: false, notice: LOAD_MORE_FAILED };
          }
          return {
            ...current,
            events: mergeEvents(current.events, response.body),
            pageSize: response.body.length,
            loadingMore: false,
            notice: '',
          };
        });
      })
      .catch(() => {
        if (requestSequence.current === sequence) {
          setView((current) =>
            current.kind === 'success'
              ? { ...current, loadingMore: false, notice: LOAD_MORE_FAILED }
              : current,
          );
        }
      });
  };

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            <AnvilMark className="brand-icon" />
          </span>
          <div className="brand-copy">
            <p className="eyebrow">ANVIL / OPERATIONS</p>
            <h1>Operator console</h1>
          </div>
        </div>
        <div className="topbar-meta" aria-label="Console properties">
          <span className="badge">
            <ShieldIcon className="icon icon-sm" />
            Tenant-scoped
          </span>
          <span className="badge">
            <KeyIcon className="icon icon-sm" />
            Read-only
          </span>
        </div>
      </header>

      <main id="main">
        <p className="intro">
          Inspect a job&apos;s state, verify its replay verdict and walk its append-only
          audit chain. Every read is scoped by the tenant you enter.
        </p>

        <section className="panel inspect-panel" aria-labelledby="inspect-heading">
          <div className="panel-head">
            <div>
              <p className="panel-kicker">Lookup</p>
              <h2 id="inspect-heading">Open a job</h2>
            </div>
            <span className="panel-hint">Three concurrent tenant-scoped reads</span>
          </div>

          <form id="inspect-form" className="inspect-form" onSubmit={handleSubmit} noValidate>
            <div className="field">
              <label htmlFor="tenant">Tenant</label>
              <div className="input-shell">
                <KeyIcon className="icon input-icon" />
                <input
                  ref={tenantRef}
                  id="tenant"
                  name="tenant"
                  type="text"
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="t_single"
                  aria-describedby="tenant-hint"
                  required
                />
              </div>
              <p id="tenant-hint" className="field-hint">
                Sent as <code>X-Tenant-Id</code>; never taken from the URL.
              </p>
            </div>

            <div className="field">
              <label htmlFor="job-id">Job ID</label>
              <div className="input-shell">
                <SearchIcon className="icon input-icon" />
                <input
                  ref={jobIdRef}
                  id="job-id"
                  name="job_id"
                  type="text"
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="00000000-0000-0000-0000-000000000000"
                  aria-describedby="job-id-hint"
                  required
                />
              </div>
              <p id="job-id-hint" className="field-hint">
                A second tenant&apos;s job looks exactly like a missing job.
              </p>
            </div>

            <button id="inspect" className="button button-primary" type="submit" disabled={loading}>
              {loading ? (
                <SpinnerIcon className="icon spinner" />
              ) : (
                <SearchIcon className="icon" />
              )}
              <span>{loading ? 'Inspecting' : 'Inspect job'}</span>
            </button>
          </form>

          <p
            id="status"
            className={`status${statusTone}`}
            role="status"
            aria-live="polite"
            aria-atomic="true"
            aria-busy={loading}
          >
            {loading && <SpinnerIcon className="icon status-icon spinner" />}
            {view.kind === 'error' && <AlertIcon className="icon status-icon" />}
            <span>{status}</span>
          </p>
        </section>

        <section
          id="not-found"
          className="panel not-found"
          hidden={view.kind !== 'not-found'}
          aria-labelledby="not-found-heading"
        >
          <span className="state-icon state-icon-bad" aria-hidden="true">
            <AlertIcon className="icon" />
          </span>
          <div>
            <h2 id="not-found-heading">Not found</h2>
            <p>{JOB_NOT_FOUND}</p>
          </div>
        </section>

        <section id="result" className="result" hidden={view.kind !== 'success'} aria-label="Job inspection result">
          <div className="summary-grid">
            <article className="panel summary-card" aria-labelledby="job-heading">
              <div className="panel-head">
                <div>
                  <p className="panel-kicker">Job record</p>
                  <h2 id="job-heading">Job</h2>
                </div>
                <span id="job-state" className={`pill ${stateClass(job?.state)}`}>
                  <span className="pill-dot" aria-hidden="true" />
                  {job?.state ?? ''}
                </span>
              </div>

              <dl className="facts">
                <div className="fact">
                  <dt>Job ID</dt>
                  <dd className="fact-mono">
                    <span id="job-id-value" className="mono fact-value">
                      {job?.id ?? ''}
                    </span>
                    <CopyButton value={job?.id ?? ''} label="Copy job ID" />
                  </dd>
                </div>
                <div className="fact">
                  <dt>Tenant</dt>
                  <dd className="fact-mono">
                    <span id="tenant-value" className="mono fact-value">
                      {job?.tenant_id ?? ''}
                    </span>
                  </dd>
                </div>
              </dl>

              <p className="card-note">
                The API returns this record only when the job belongs to the tenant
                authorized above.
              </p>
            </article>

            <article className="panel summary-card" aria-labelledby="replay-heading">
              <div className="panel-head">
                <div>
                  <p className="panel-kicker">Deterministic verification</p>
                  <h2 id="replay-heading">Replay</h2>
                </div>
                <span
                  id="replay-match"
                  className={`pill ${replay?.match ? 'pill-good' : replay ? 'pill-bad' : 'pill-neutral'}`}
                >
                  <span className="pill-dot" aria-hidden="true" />
                  {replay ? String(replay.match) : ''}
                </span>
              </div>

              <dl className="facts">
                <div className="fact fact-hash">
                  <dt>Input SHA-256</dt>
                  <dd>
                    <div className="hash-line">
                      <code id="replay-input-sha" className="mono hash-value">
                        {replay?.input_sha ?? ''}
                      </code>
                      <CopyButton value={replay?.input_sha ?? ''} label="Copy input SHA-256" />
                    </div>
                  </dd>
                </div>
                <div className="fact fact-hash">
                  <dt>Output SHA-256</dt>
                  <dd>
                    <div className="hash-line">
                      <code id="replay-output-sha" className="mono hash-value">
                        {replay?.output_sha ?? ''}
                      </code>
                      <CopyButton value={replay?.output_sha ?? ''} label="Copy output SHA-256" />
                    </div>
                  </dd>
                </div>
              </dl>

              <p className="card-note">
                <span className="inline-mark" aria-hidden="true" />
                A match means the stored chain replays to the committed artifact.
              </p>
            </article>
          </div>

          <article className="panel chain-panel" aria-labelledby="chain-heading">
            <div className="panel-head chain-head">
              <div>
                <p className="panel-kicker">Append-only log</p>
                <h2 id="chain-heading">Audit chain</h2>
              </div>
              <div className="chain-stats" aria-label="Chain summary">
                <div className="stat">
                  <span className="stat-label">Events</span>
                  <span className="stat-value">{total}</span>
                </div>
                <div className="stat">
                  <span className="stat-label">Newest seq</span>
                  <span className="stat-value">{lastEvent ? lastEvent.seq : '—'}</span>
                </div>
                <div className="stat stat-wide">
                  <span className="stat-label">Head</span>
                  <code className="stat-code">{shortHash(lastEvent?.event_hash)}</code>
                </div>
              </div>
            </div>

            <p id="chain-note" className="chain-note" hidden={total === 0}>
              {total > 0 ? eventCountLabel(total) : ''}
            </p>

            <div className="table-wrap">
              <table id="audit-events">
                <caption className="sr-only">
                  Audit events for the selected job, ordered by sequence
                </caption>
                <thead>
                  <tr>
                    <th scope="col" className="col-seq">
                      Seq
                    </th>
                    <th scope="col" className="col-type">
                      Type
                    </th>
                    <th scope="col" className="col-hash">
                      Event hash
                    </th>
                    <th scope="col" className="col-hash">
                      Prev hash
                    </th>
                    <th scope="col" className="col-payload">
                      Payload
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {events.map((event) => (
                    <tr key={`${event.id}-${event.seq}`}>
                      <td className="cell-seq">{event.seq}</td>
                      <td>
                        <span className={`event-badge ${eventClass(event.type)}`}>
                          {event.type}
                        </span>
                      </td>
                      <td className="cell-hash">
                        <div className="hash-line">
                          <code className="mono hash-value">{event.event_hash}</code>
                          <CopyButton value={event.event_hash} label="Copy event hash" />
                        </div>
                      </td>
                      <td className="cell-hash">
                        <div className="hash-line">
                          <code className="mono hash-value">{event.prev_hash}</code>
                          <CopyButton value={event.prev_hash} label="Copy previous hash" />
                        </div>
                      </td>
                      <td className="cell-payload">
                        <details className="payload">
                          <summary>
                            <span>Payload</span>
                            <ChevronIcon className="icon icon-xs payload-chevron" />
                          </summary>
                          <pre>{formatPayload(event.payload)}</pre>
                        </details>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {success !== null && total === 0 && (
              <div className="empty-state">
                <span className="state-icon" aria-hidden="true">
                  <LinkIcon className="icon" />
                </span>
                <p>No audit events were returned for this job.</p>
              </div>
            )}

            <div className="chain-footer">
              <p className="chain-hint">
                Rows are ordered by sequence. The page cursor is the last event&apos;s ID.
              </p>
              <button
                id="load-more"
                className="button button-secondary"
                type="button"
                hidden={!hasMore}
                disabled={success?.loadingMore}
                onClick={handleLoadMore}
              >
                {success?.loadingMore ? (
                  <SpinnerIcon className="icon spinner" />
                ) : (
                  <ChevronIcon className="icon" />
                )}
                <span>{success?.loadingMore ? 'Loading events' : 'Load more events'}</span>
              </button>
            </div>
          </article>
        </section>
      </main>

      <footer className="footer">
        <span className="footer-brand">ANVIL operator console</span>
        <span className="footer-sep" aria-hidden="true" />
        <span>Tenant-scoped, read-only inspection</span>
      </footer>
    </div>
  );
}
