import type { WebBusinessNotification } from '@muyun/web-contracts';
import { businessNotificationExpired } from './businessNotifications';

/** A complete authorized snapshot for one business-owned subset, not a page of results. */
export interface BusinessNotificationRecoverySource {
  id: string;
  owns(notification: WebBusinessNotification): boolean;
  load(signal: AbortSignal): Promise<readonly WebBusinessNotification[]>;
}

export interface BusinessNotificationSession {
  register(source: BusinessNotificationRecoverySource): () => void;
  receive(notification: WebBusinessNotification): void;
  dismiss(notificationId: string): void;
  recover(sourceId?: string): Promise<void>;
  snapshot(): WebBusinessNotification[];
  /** Cancel in-flight snapshots while retaining the current list, e.g. on transport loss. */
  cancelRecovery(): void;
  dispose(): void;
}

/**
 * One instance per authenticated identity/tenant session. No persistence, timers or action execution.
 * Source loaders capture that identity's client; recreate the session when the identity changes.
 */
export function createBusinessNotificationSession(options: {
  isCurrent(): boolean;
  onChange(notifications: WebBusinessNotification[]): void;
  onRecoveryError?(sourceId: string, error: unknown): void;
  now?: () => number;
}): BusinessNotificationSession {
  type Flight = {
    controller: AbortController;
    updates: Map<string, WebBusinessNotification>;
    promise: Promise<void>;
  };
  type Source = { definition: BusinessNotificationRecoverySource; flight?: Flight };
  const sources = new Map<string, Source>();
  let records = new Map<string, { sourceId?: string; notification: WebBusinessNotification }>();
  const dismissed = new Set<string>();
  const now = options.now ?? Date.now;
  let disposed = false;
  const current = () => !disposed && options.isCurrent();

  function owner(notification: WebBusinessNotification) {
    if (!notification.id) throw new Error('Notification identity is required');
    const matches = [...sources.values()].filter((source) => source.definition.owns(notification));
    if (matches.length > 1) throw new Error('Notification belongs to multiple recovery sources');
    return matches[0]?.definition.id;
  }
  function snapshot() {
    if (!current()) return [];
    return [...records.values()]
      .map((item) => item.notification)
      .filter((item) => !dismissed.has(item.id) && !businessNotificationExpired(item, now()))
      .map((item) => structuredClone(item));
  }
  function emit() {
    if (current()) options.onChange(snapshot());
  }
  function sourceRecovery(entry: Source): Promise<void> {
    if (!current()) return Promise.resolve();
    if (entry.flight) return entry.flight.promise;
    const flight: Flight = {
      controller: new AbortController(),
      updates: new Map(),
      promise: Promise.resolve(),
    };
    entry.flight = flight;
    const sourceId = entry.definition.id;
    const valid = () => current() && sources.get(sourceId) === entry && !flight.controller.signal.aborted;
    flight.promise = Promise.resolve().then(async () => {
      try {
        if (!valid()) return;
        const found = await entry.definition.load(flight.controller.signal);
        if (!valid()) return;
        // Validate the entire snapshot before replacing this source's subset.
        const incoming = new Map<string, WebBusinessNotification>();
        for (const value of found) {
          const notification = structuredClone(value);
          if (owner(notification) !== sourceId || incoming.has(notification.id))
            throw new Error('Invalid notification recovery snapshot');
          const existing = records.get(notification.id);
          if (existing && existing.sourceId !== sourceId)
            throw new Error('Notification identity collides with another source');
          incoming.set(notification.id, notification);
        }
        // Live arrivals, including expired withdrawals, win over an earlier query.
        for (const [id, notification] of flight.updates) {
          incoming.delete(id);
          incoming.set(id, notification);
        }
        const merged = new Map([...records].filter(([, item]) => item.sourceId !== sourceId));
        for (const [id, notification] of incoming) {
          if (!dismissed.has(id) && !businessNotificationExpired(notification, now()))
            merged.set(id, { sourceId, notification });
        }
        records = merged;
        emit();
      } catch (error) {
        if (valid()) options.onRecoveryError?.(sourceId, error);
      } finally {
        if (entry.flight === flight) entry.flight = undefined;
      }
    });
    return flight.promise;
  }

  return {
    register(source) {
      const sourceId = source.id;
      if (!current()) throw new Error('Notification session is no longer current');
      if (!sourceId || sources.has(sourceId)) throw new Error('Duplicate or empty notification source');
      const entry: Source = { definition: { ...source } };
      sources.set(sourceId, entry);
      try {
        const reassigned = new Map(
          [...records].map(([id, value]) => [id, { ...value, sourceId: owner(value.notification) }] as const),
        );
        records = reassigned;
      } catch (error) {
        sources.delete(sourceId);
        throw error;
      }
      return () => {
        if (sources.get(sourceId) !== entry) return;
        sources.delete(sourceId);
        entry.flight?.controller.abort();
        records = new Map([...records].filter(([, item]) => item.sourceId !== sourceId));
        emit();
      };
    },
    receive(value) {
      if (!current()) return;
      const notification = structuredClone(value);
      const sourceId = owner(notification);
      const existing = records.get(notification.id);
      if (existing && existing.sourceId !== sourceId)
        throw new Error('Notification identity collides with another source');
      if (sourceId) sources.get(sourceId)?.flight?.updates.set(notification.id, notification);
      records.delete(notification.id);
      if (!dismissed.has(notification.id) && !businessNotificationExpired(notification, now()))
        records.set(notification.id, { sourceId, notification });
      for (const [id, item] of records) {
        if (businessNotificationExpired(item.notification, now())) records.delete(id);
      }
      emit();
    },
    dismiss(id) {
      if (!current()) return;
      dismissed.add(id);
      records.delete(id);
      emit();
    },
    async recover(sourceId) {
      if (!current()) return;
      if (sourceId !== undefined) {
        const source = sources.get(sourceId);
        if (!source) throw new Error('Unknown notification source');
        await sourceRecovery(source);
      } else await Promise.all([...sources.values()].map(sourceRecovery));
    },
    snapshot,
    cancelRecovery() {
      for (const entry of sources.values()) {
        entry.flight?.controller.abort();
        entry.flight = undefined;
      }
    },
    dispose() {
      if (disposed) return;
      const notify = options.isCurrent();
      disposed = true;
      for (const entry of sources.values()) entry.flight?.controller.abort();
      sources.clear();
      records.clear();
      dismissed.clear();
      if (notify) options.onChange([]);
    },
  };
}
