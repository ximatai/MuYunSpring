import {
  createDataChangeDispatcher,
  createRealtimeClient,
  connectRealtimeBusinessEvents,
  connectRealtimeBusinessNotifications,
  connectRealtimeDataChanges,
  connectRealtimeUserNotifications,
  sessionActivityCommand,
  type RealtimeClient,
  type RealtimeConnectionState,
} from '@muyun/web-core';
import type {
  WebBusinessRealtimeEvent,
  WebBusinessNotification,
  WebCommittedChangeSet,
  WebUserNotification,
} from '@muyun/web-contracts';
import { effectiveAuthToken } from './authSession';

export const appDataChangeDispatcher = createDataChangeDispatcher();
const businessEventHandlers = new Set<(event: WebBusinessRealtimeEvent) => void | Promise<void>>();
let activeAppRealtimeConnection: AppRealtimeConnection | undefined;
const ACTIVITY_REPORT_INTERVAL_MS = 30_000;

export interface AppRealtimeOptions {
  /** Backend origin supplied by the consuming App at runtime. */
  baseUrl?: string;
  /** Current authentication token supplied by the consuming App at runtime. */
  token?: string;
  onUnauthorized?: () => void;
  onUserNotification?: (notification: WebUserNotification) => void;
  onBusinessNotification?: (notification: WebBusinessNotification) => void;
  onStateChange?: (state: RealtimeConnectionState) => void;
}

export interface AppRealtimeConnection {
  disconnect(): Promise<void>;
}

function createAppRealtimeClient(options: AppRealtimeOptions = {}) {
  return createRealtimeClient({
    baseUrl: options.baseUrl ?? import.meta.env.VITE_MUYUN_API_BASE_URL,
    token: options.token ?? effectiveAuthToken(import.meta.env.VITE_MUYUN_AUTH_TOKEN),
    onStateChange: (state) => {
      options.onStateChange?.(state);
      if (state === 'unauthorized') {
        options.onUnauthorized?.();
      }
    },
  });
}

export function connectAppRealtime(options: AppRealtimeOptions = {}) {
  if (activeAppRealtimeConnection) {
    throw new Error(
      'App realtime is already connected. Disconnect the active connection before reconnecting.',
    );
  }
  const realtime = createAppRealtimeClient(options);
  const dataChangeSubscription = connectRealtimeDataChanges(realtime, appDataChangeDispatcher);
  const userNotificationSubscription = connectRealtimeUserNotifications(realtime, (notification) => {
    options.onUserNotification?.(notification);
  });
  const businessNotificationSubscription = connectRealtimeBusinessNotifications(realtime, (notification) => {
    options.onBusinessNotification?.(notification);
  });
  const businessEventSubscription = connectRealtimeBusinessEvents(realtime, (event) => {
    for (const handler of businessEventHandlers) {
      void handler(event);
    }
  });
  const activityReporter = createSessionActivityReporter(realtime);
  activityReporter.start();
  void realtime.connect();
  let disconnected = false;
  const connection: AppRealtimeConnection = {
    async disconnect() {
      if (disconnected) {
        return;
      }
      disconnected = true;
      dataChangeSubscription.unsubscribe();
      userNotificationSubscription.unsubscribe();
      businessNotificationSubscription.unsubscribe();
      businessEventSubscription.unsubscribe();
      activityReporter.stop();
      if (activeAppRealtimeConnection === connection) {
        activeAppRealtimeConnection = undefined;
      }
      await realtime.disconnect();
    },
  };
  activeAppRealtimeConnection = connection;
  return connection;
}

export async function disconnectAppRealtime() {
  await activeAppRealtimeConnection?.disconnect();
}

function createSessionActivityReporter(realtime: RealtimeClient) {
  let lastReportedAt = 0;
  const activityEvents = ['pointermove', 'pointerdown', 'keydown', 'scroll'] as const;
  const handleActivity = () => reportActivity(false);
  const handleVisibilityChange = () => {
    if (document.visibilityState === 'visible') {
      reportActivity(true);
    }
  };
  return {
    start() {
      if (typeof window === 'undefined' || typeof document === 'undefined') {
        return;
      }
      for (const event of activityEvents) {
        window.addEventListener(event, handleActivity, { passive: true });
      }
      document.addEventListener('visibilitychange', handleVisibilityChange);
    },
    stop() {
      if (typeof window === 'undefined' || typeof document === 'undefined') {
        return;
      }
      for (const event of activityEvents) {
        window.removeEventListener(event, handleActivity);
      }
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    },
  };

  function reportActivity(force: boolean) {
    if (realtime.state() !== 'connected') {
      return;
    }
    const now = Date.now();
    if (!force && now - lastReportedAt < ACTIVITY_REPORT_INTERVAL_MS) {
      return;
    }
    lastReportedAt = now;
    try {
      realtime.publish(sessionActivityCommand, { timestamp: new Date(now).toISOString() });
    } catch {
      // Activity reporting is best-effort; normal realtime state handling covers connection failures.
    }
  }
}

export function subscribeAppDataChanges(handler: (changeSet: WebCommittedChangeSet) => void | Promise<void>) {
  return appDataChangeDispatcher.subscribe(handler);
}

export function subscribeAppBusinessEvents(
  handler: (event: WebBusinessRealtimeEvent) => void | Promise<void>,
) {
  businessEventHandlers.add(handler);
  return {
    unsubscribe() {
      businessEventHandlers.delete(handler);
    },
  };
}
