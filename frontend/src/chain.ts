import { PAGE_SIZE, type AuditEventDto } from './api';

/** The keyset cursor is the last rendered event's database id. */
export function nextCursor(events: readonly AuditEventDto[]): string | null {
  const last = events[events.length - 1];
  return last ? String(last.id) : null;
}

/** The server returns at most one page; only a full page can have a successor. */
export function isFullPage(page: readonly unknown[], pageSize = PAGE_SIZE): boolean {
  return page.length === pageSize;
}

export function mergeEvents(
  current: readonly AuditEventDto[],
  page: readonly AuditEventDto[],
): AuditEventDto[] {
  return [...current, ...page];
}

export function eventCountLabel(count: number): string {
  return `Showing ${count} event${count === 1 ? '' : 's'}.`;
}

export function formatPayload(payload: unknown): string {
  if (payload == null) {
    return '{}';
  }
  try {
    return JSON.stringify(payload, null, 2);
  } catch {
    return String(payload);
  }
}

export function shortHash(hash: string | undefined, length = 12): string {
  if (!hash) {
    return '—';
  }
  return hash.length <= length ? hash : `${hash.slice(0, length)}…`;
}

export function eventClass(type: string): string {
  switch (type) {
    case 'submit':
      return 'event-submit';
    case 'claim':
      return 'event-claim';
    case 'cap.issue':
      return 'event-cap-issue';
    case 'cap.revoke':
      return 'event-cap-revoke';
    case 'cas.put':
      return 'event-cas-put';
    case 'commit':
      return 'event-commit';
    default:
      return 'event-other';
  }
}

export function stateClass(state: string | undefined): string {
  const value = (state ?? '').toUpperCase();
  if (value.includes('SUCCEED')) {
    return 'state-good';
  }
  if (value.includes('FAIL') || value.includes('DEAD') || value.includes('CANCEL')) {
    return 'state-bad';
  }
  if (value.includes('RUN') || value.includes('CLAIM') || value.includes('PEND')) {
    return 'state-active';
  }
  return 'state-neutral';
}
