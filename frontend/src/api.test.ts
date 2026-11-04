import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  PAGE_SIZE,
  eventsPath,
  jobPath,
  replayPath,
  requestJson,
} from './api';
import {
  eventClass,
  eventCountLabel,
  formatPayload,
  isFullPage,
  mergeEvents,
  nextCursor,
  shortHash,
  stateClass,
} from './chain';
import type { AuditEventDto } from './api';

function auditEvent(seq: number): AuditEventDto {
  return {
    id: `event-${seq}`,
    seq,
    type: 'commit',
    event_hash: `hash-${seq}`,
    prev_hash: `hash-${seq - 1}`,
    payload: { seq },
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('API paths', () => {
  it('builds tenant-independent, encoded resource paths', () => {
    expect(jobPath('job/with space')).toBe('/api/jobs/job%2Fwith%20space');
    expect(replayPath('job/with space')).toBe('/audit/replay?job_id=job%2Fwith%20space');
    expect(eventsPath('job/with space')).toBe(
      `/audit/events?job_id=job%2Fwith%20space&limit=${PAGE_SIZE}`,
    );
    expect(eventsPath('job-1', 'cursor/1')).toBe(
      `/audit/events?job_id=job-1&limit=${PAGE_SIZE}&cursor=cursor%2F1`,
    );
  });

  it('sends X-Tenant-Id and resolves parsed JSON through XMLHttpRequest', async () => {
    class FakeXhr {
      static DONE = 4;
      static last: FakeXhr | undefined;

      readyState = 0;
      status = 0;
      responseText = '';
      onreadystatechange: (() => void) | null = null;
      onerror: (() => void) | null = null;
      ontimeout: (() => void) | null = null;
      headers: Record<string, string> = {};

      constructor() {
        FakeXhr.last = this;
      }

      open = vi.fn();

      setRequestHeader = vi.fn((name: string, value: string) => {
        this.headers[name] = value;
      });

      send = vi.fn(() => {
        queueMicrotask(() => {
          this.readyState = 4;
          this.status = 200;
          this.responseText = '{"ok":true}';
          this.onreadystatechange?.();
        });
      });
    }

    vi.stubGlobal('XMLHttpRequest', FakeXhr);

    const response = await requestJson<{ ok: boolean }>('/some/path', 't_console_a');

    expect(response).toEqual({ status: 200, body: { ok: true } });
    expect(FakeXhr.last?.headers['X-Tenant-Id']).toBe('t_console_a');
    expect(FakeXhr.last?.open).toHaveBeenCalledWith('GET', '/some/path', true);
    expect(FakeXhr.last?.send).toHaveBeenCalledOnce();
  });
});

describe('chain helpers', () => {
  it('uses the last event id as the next cursor', () => {
    expect(nextCursor([])).toBeNull();
    expect(nextCursor([auditEvent(0)])).toBe('event-0');
    expect(nextCursor([auditEvent(0), auditEvent(1)])).toBe('event-1');
  });

  it('only offers another page after a full page', () => {
    const full = Array.from({ length: PAGE_SIZE }, (_, index) => auditEvent(index));
    expect(isFullPage(full)).toBe(true);
    expect(isFullPage(full.slice(0, PAGE_SIZE - 1))).toBe(false);
    expect(isFullPage([], 0)).toBe(true);
  });

  it('merges pages without mutating or dropping rows', () => {
    const first = [auditEvent(0)];
    const second = [auditEvent(1), auditEvent(2)];
    expect(mergeEvents(first, second).map((event) => event.seq)).toEqual([0, 1, 2]);
    expect(first).toHaveLength(1);
  });

  it('formats counts, payloads and hashes for the UI', () => {
    expect(eventCountLabel(0)).toBe('Showing 0 events.');
    expect(eventCountLabel(1)).toBe('Showing 1 event.');
    expect(eventCountLabel(100)).toBe('Showing 100 events.');
    expect(formatPayload(null)).toBe('{}');
    expect(formatPayload({ a: 1 })).toBe('{\n  "a": 1\n}');
    expect(shortHash('abcdefghijklmnop', 4)).toBe('abcd…');
    expect(shortHash(undefined)).toBe('—');
  });

  it('maps audit event and job state names to presentation classes', () => {
    expect(eventClass('submit')).toBe('event-submit');
    expect(eventClass('cap.revoke')).toBe('event-cap-revoke');
    expect(eventClass('unknown')).toBe('event-other');
    expect(stateClass('SUCCEEDED')).toBe('state-good');
    expect(stateClass('FAILED')).toBe('state-bad');
    expect(stateClass('RUNNING')).toBe('state-active');
    expect(stateClass('CREATED')).toBe('state-neutral');
  });
});
