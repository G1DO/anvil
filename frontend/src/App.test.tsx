import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import {
  PAGE_SIZE,
  inspectJob,
  loadMoreEvents,
  type AuditEventDto,
  type InspectBundle,
  type JobDto,
  type JsonResponse,
  type ReplayDto,
} from './api';

vi.mock('./api', () => ({
  PAGE_SIZE: 100,
  inspectJob: vi.fn(),
  loadMoreEvents: vi.fn(),
}));

const inspectMock = vi.mocked(inspectJob);
const loadMoreMock = vi.mocked(loadMoreEvents);

function byId<T extends HTMLElement>(id: string): T {
  const element = document.getElementById(id);
  if (element === null) {
    throw new Error(`Missing #${id}`);
  }
  return element as T;
}

function job(overrides: Partial<JobDto> = {}): JobDto {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    tenant_id: 't_console_a',
    state: 'SUCCEEDED',
    ...overrides,
  };
}

function replay(overrides: Partial<ReplayDto> = {}): ReplayDto {
  return {
    input_sha: 'a'.repeat(64),
    output_sha: 'b'.repeat(64),
    match: true,
    ...overrides,
  };
}

function auditEvent(seq: number, overrides: Partial<AuditEventDto> = {}): AuditEventDto {
  return {
    id: `event-${seq}`,
    seq,
    type: seq === 0 ? 'submit' : 'commit',
    event_hash: `event-hash-${seq}`,
    prev_hash: seq === 0 ? 'GENESIS' : `event-hash-${seq - 1}`,
    payload: { seq, kind: seq === 0 ? 'submit' : 'commit' },
    ...overrides,
  };
}

function bundle(overrides: {
  job?: JsonResponse<JobDto>;
  events?: JsonResponse<AuditEventDto[]>;
  replay?: JsonResponse<ReplayDto>;
} = {}): InspectBundle {
  return {
    job: overrides.job ?? { status: 200, body: job() },
    events:
      overrides.events ?? {
        status: 200,
        body: [auditEvent(0), auditEvent(1)],
      },
    replay: overrides.replay ?? { status: 200, body: replay() },
  };
}

function fillAndInspect(tenant = 't_console_a', jobId = job().id): void {
  byId<HTMLInputElement>('tenant').value = tenant;
  byId<HTMLInputElement>('job-id').value = jobId;
  fireEvent.click(byId('inspect'));
}

function statusText(): string {
  return byId('status').textContent?.trim() ?? '';
}

beforeEach(() => {
  inspectMock.mockReset();
  loadMoreMock.mockReset();
});

describe('App', () => {
  it('renders the job, replay verdict and audit rows after three reads', async () => {
    inspectMock.mockResolvedValue(bundle());

    render(<App />);
    fillAndInspect();

    expect(inspectMock).toHaveBeenCalledWith('t_console_a', job().id);
    expect(await screen.findByText('SUCCEEDED')).toBeInTheDocument();

    expect(byId('result').hasAttribute('hidden')).toBe(false);
    expect(byId('not-found').hasAttribute('hidden')).toBe(true);
    expect(statusText()).toBe('');
    expect(byId('job-id-value').textContent?.trim()).toBe(job().id);
    expect(byId('tenant-value').textContent?.trim()).toBe('t_console_a');
    expect(byId('replay-match').textContent?.trim()).toBe('true');
    expect(byId('replay-input-sha').textContent?.trim()).toBe('a'.repeat(64));
    expect(byId('replay-output-sha').textContent?.trim()).toBe('b'.repeat(64));

    const rows = document.querySelectorAll('#audit-events tbody tr');
    expect(rows).toHaveLength(2);
    const firstCells = rows[0]?.querySelectorAll('td');
    expect(firstCells?.[0]?.textContent?.trim()).toBe('0');
    expect(firstCells?.[1]?.textContent?.trim()).toBe('submit');
    expect(firstCells?.[2]?.textContent?.trim()).toBe('event-hash-0');
    expect(firstCells?.[3]?.textContent?.trim()).toBe('GENESIS');
    expect(firstCells?.[4]?.textContent).toContain('"kind": "submit"');
    expect(byId('chain-note').textContent).toBe('Showing 2 events.');
  });

  it('shows Inspecting... while all three reads are in flight', async () => {
    let resolveBundle: ((value: InspectBundle) => void) | undefined;
    inspectMock.mockReturnValue(
      new Promise((resolve) => {
        resolveBundle = resolve;
      }),
    );

    render(<App />);
    fillAndInspect();

    expect(statusText()).toBe('Inspecting...');
    expect(byId('result').hasAttribute('hidden')).toBe(true);
    expect(byId('not-found').hasAttribute('hidden')).toBe(true);

    resolveBundle?.(bundle());
    expect(await screen.findByText('SUCCEEDED')).toBeInTheDocument();
    expect(statusText()).toBe('');
  });

  it('turns a 401 into the exact tenant-not-found verdict', async () => {
    inspectMock.mockResolvedValue(
      bundle({
        job: { status: 401, body: null },
        events: { status: 401, body: null },
        replay: { status: 401, body: null },
      }),
    );

    render(<App />);
    fillAndInspect('t_console_nobody');

    await waitFor(() => expect(statusText()).toBe('Tenant not found.'));
    expect(byId('result').hasAttribute('hidden')).toBe(true);
    expect(byId('not-found').hasAttribute('hidden')).toBe(true);
    expect(document.querySelectorAll('#audit-events tbody tr')).toHaveLength(0);
  });

  it('shows the generic missing-job panel for a 404 with no chain data', async () => {
    inspectMock.mockResolvedValue(
      bundle({
        job: { status: 404, body: null },
        events: { status: 404, body: null },
        replay: { status: 404, body: null },
      }),
    );

    render(<App />);
    fillAndInspect('t_console_a', '99999999-9999-9999-9999-999999999999');

    await waitFor(() => expect(byId('not-found').hasAttribute('hidden')).toBe(false));
    expect(byId('result').hasAttribute('hidden')).toBe(true);
    expect(byId('not-found-heading').textContent).toBe('Not found');
    expect(byId('not-found').textContent).toContain('Job not found.');
    expect(statusText()).toBe('');
    expect(document.querySelectorAll('#audit-events tbody tr')).toHaveLength(0);
    expect(byId('job-id-value').textContent).toBe('');
    expect(byId('replay-input-sha').textContent).toBe('');
  });

  it('uses the exact generic failure text for a non-401, non-404 error', async () => {
    inspectMock.mockResolvedValue(
      bundle({
        events: { status: 500, body: null },
      }),
    );

    render(<App />);
    fillAndInspect();

    await waitFor(() => expect(statusText()).toBe('The job could not be loaded.'));
    expect(byId('result').hasAttribute('hidden')).toBe(true);
    expect(byId('not-found').hasAttribute('hidden')).toBe(true);
  });

  it('appends a page with the last event id as cursor and hides Load more on a short page', async () => {
    const firstPage = Array.from({ length: PAGE_SIZE }, (_, index) => auditEvent(index));
    inspectMock.mockResolvedValue(
      bundle({
        events: { status: 200, body: firstPage },
      }),
    );
    loadMoreMock.mockResolvedValue({
      status: 200,
      body: [auditEvent(PAGE_SIZE), auditEvent(PAGE_SIZE + 1)],
    });

    render(<App />);
    fillAndInspect();

    await waitFor(() => expect(byId('chain-note').textContent).toBe('Showing 100 events.'));
    expect(byId('load-more').hasAttribute('hidden')).toBe(false);
    expect(document.querySelectorAll('#audit-events tbody tr')).toHaveLength(100);

    fireEvent.click(byId('load-more'));

    await waitFor(() =>
      expect(byId('chain-note').textContent).toBe('Showing 102 events.'),
    );
    expect(loadMoreMock).toHaveBeenCalledWith('t_console_a', job().id, 'event-99');
    expect(document.querySelectorAll('#audit-events tbody tr')).toHaveLength(102);
    expect(byId('load-more').hasAttribute('hidden')).toBe(true);
    expect(byId('result').hasAttribute('hidden')).toBe(false);
  });
});
