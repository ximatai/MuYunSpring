import {
  createDataChangeDispatcher,
  createRealtimeClient,
  connectRealtimeBusinessEvents,
  connectRealtimeDataChanges,
  connectRealtimeUserNotifications,
  moduleDataChangeChannel,
  sessionActivityCommand,
  type RealtimeClient,
  type RealtimeConnectionState,
  type RealtimeSubscription,
} from '@muyun/web-core';
import type {
  WebBusinessRealtimeEvent,
  WebCommittedChangeSet,
  WebUserNotification,
} from '@muyun/web-contracts';
import { effectiveAuthToken } from './authSession';

export const appDataChangeDispatcher = createDataChangeDispatcher();
const moduleDataChangeSubscriptions = new Map<string, DataChangeTopicSubscription>();
const businessEventHandlers = new Set<(event: WebBusinessRealtimeEvent) => void | Promise<void>>();
let activeRealtime: RealtimeClient | undefined;
let activeAppRealtimeConnection: AppRealtimeConnection | undefined;
const ACTIVITY_REPORT_INTERVAL_MS = 30_000;

export interface AppRealtimeOptions {
  /** Backend origin supplied by the consuming App at runtime. */
  baseUrl?: string;
  /** Current authentication token supplied by the consuming App at runtime. */
  token?: string;
  onUnauthorized?: () => void;
  onUserNotification?: (notification: WebUserNotification) => void;
  onStateChange?: (state: RealtimeConnectionState) => void;
}

export interface AppRealtimeConnection {
  disconnect(): Promise<void>;
}

interface DataChangeTopicSubscription {
  moduleAlias: string;
  references: number;
  active?: RealtimeSubscription;
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
  activeRealtime = realtime;
  const dataChangeSubscription = connectRealtimeDataChanges(realtime, appDataChangeDispatcher);
  const userNotificationSubscription = connectRealtimeUserNotifications(realtime, (notification) => {
    options.onUserNotification?.(notification);
  });
  const businessEventSubscription = connectRealtimeBusinessEvents(realtime, (event) => {
    for (const handler of businessEventHandlers) {
      void handler(event);
    }
  });
  const activityReporter = createSessionActivityReporter(realtime);
  activityReporter.start();
  bindPageRealtimeSubscriptions(realtime);
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
      businessEventSubscription.unsubscribe();
      activityReporter.stop();
      unbindPageRealtimeSubscriptions();
      if (activeAppRealtimeConnection === connection) {
        activeAppRealtimeConnection = undefined;
      }
      if (activeRealtime === realtime) {
        activeRealtime = undefined;
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

export function subscribeAppModuleDataChanges(moduleAlias: string) {
  const normalized = moduleAlias.trim();
  if (!normalized) {
    throw new Error('Module data change subscription requires a moduleAlias');
  }
  const existing = moduleDataChangeSubscriptions.get(normalized);
  const topic = existing ?? { moduleAlias: normalized, references: 0 };
  topic.references += 1;
  moduleDataChangeSubscriptions.set(normalized, topic);
  if (!topic.active && activeRealtime) {
    topic.active = bindModuleDataChangeTopic(activeRealtime, normalized);
  }
  return {
    unsubscribe() {
      topic.references -= 1;
      if (topic.references > 0) {
        return;
      }
      topic.active?.unsubscribe();
      topic.active = undefined;
      moduleDataChangeSubscriptions.delete(normalized);
    },
  };
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

function bindPageRealtimeSubscriptions(realtime: RealtimeClient) {
  for (const subscription of moduleDataChangeSubscriptions.values()) {
    subscription.active?.unsubscribe();
    subscription.active = bindModuleDataChangeTopic(realtime, subscription.moduleAlias);
  }
}

function unbindPageRealtimeSubscriptions() {
  for (const subscription of moduleDataChangeSubscriptions.values()) {
    subscription.active?.unsubscribe();
    subscription.active = undefined;
  }
}

function bindModuleDataChangeTopic(realtime: RealtimeClient, moduleAlias: string) {
  return realtime.subscribe(moduleDataChangeChannel(moduleAlias), (changeSet) => {
    void appDataChangeDispatcher.dispatch(changeSet);
  });
}
