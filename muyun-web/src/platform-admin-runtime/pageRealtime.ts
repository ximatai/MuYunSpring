import { computed, onMounted, onUnmounted, ref } from 'vue';
import {
  webDataChangeTypes,
  type WebBusinessRealtimeEvent,
  type WebCommittedChangeSet,
  type WebDataChange,
} from '@muyun/web-contracts';
import { subscribeAppBusinessEvents, subscribeAppDataChanges } from './realtime';

export interface PageRealtimeSubscription {
  unsubscribe(): void;
}

export interface PageBusinessEventOptions {
  type?: string | string[];
  moduleAlias?: string;
  recordId?: string | (() => string | undefined);
  handler: (event: WebBusinessRealtimeEvent) => void | Promise<void>;
  predicate?: (event: WebBusinessRealtimeEvent) => boolean;
}

export interface PageDataChangeOptions {
  moduleAlias?: string;
  recordId?: string | (() => string | undefined);
  handler: (changeSet: WebCommittedChangeSet, changes: WebDataChange[]) => void | Promise<void>;
  predicate?: (change: WebDataChange) => boolean;
}

export interface PageRecordExternalChangeOptions {
  moduleAlias: string;
  recordId: string | (() => string | undefined);
  editing: boolean | (() => boolean);
  saving?: boolean | (() => boolean);
  changeTypes?: string[];
}

export interface PageRecordExternalChangeState {
  externalChangedRecordId: Readonly<{ value: string | undefined }>;
  externallyChanged: Readonly<{ value: boolean }>;
  markExternalRecordChanged(recordId: string | undefined): boolean;
  clearExternalChanged(): void;
  handleDataChanges(changes: WebDataChange[]): void;
}

export interface RealtimeRefreshQueueOptions<TKey extends string> {
  delay?: number;
  load: (run: RealtimeRefreshRun<TKey>) => void | Promise<void>;
}

export interface RealtimeRefreshRun<TKey extends string> {
  keys: TKey[];
  isLatest(key: TKey): boolean;
  active(): boolean;
}

export interface RealtimeRefreshQueue<TKey extends string> {
  enqueue(key: TKey | TKey[] | undefined): void;
  reset(): void;
  dispose(): void;
}

export function createPageBusinessEventHandler(options: PageBusinessEventOptions) {
  return (event: WebBusinessRealtimeEvent) => {
    if (matchesBusinessEvent(event, options)) {
      void options.handler(event);
    }
  };
}

export function createPageDataChangeHandler(options: PageDataChangeOptions) {
  return (changeSet: WebCommittedChangeSet) => {
    const changes = changeSet.changes.filter((change) => matchesDataChange(change, options));
    if (changes.length > 0) {
      void options.handler(changeSet, changes);
    }
  };
}

export function usePageDataChangeHandler(
  handler: (changeSet: WebCommittedChangeSet) => void | Promise<void>,
) {
  usePageRealtimeSubscription(() => subscribeAppDataChanges(handler));
}

export function usePageBusinessEventHandler(
  handler: (event: WebBusinessRealtimeEvent) => void | Promise<void>,
) {
  usePageRealtimeSubscription(() => subscribeAppBusinessEvents(handler));
}

export function usePageBusinessEvent(options: PageBusinessEventOptions) {
  usePageRealtimeSubscription(() => subscribeAppBusinessEvents(createPageBusinessEventHandler(options)));
}

export function usePageDataChange(options: PageDataChangeOptions) {
  usePageRealtimeSubscription(() => subscribeAppDataChanges(createPageDataChangeHandler(options)));
}

export function usePageRecordExternalChange(
  options: PageRecordExternalChangeOptions,
): PageRecordExternalChangeState {
  const state = createPageRecordExternalChangeState(options);
  usePageDataChange({
    moduleAlias: options.moduleAlias,
    handler: (_changeSet, changes) => state.handleDataChanges(changes),
  });
  return state;
}

export function createPageRecordExternalChangeState(
  options: PageRecordExternalChangeOptions,
): PageRecordExternalChangeState {
  const externalChangedRecordId = ref<string>();
  const externallyChanged = computed(() => Boolean(externalChangedRecordId.value));

  function markExternalRecordChanged(recordId: string | undefined) {
    const currentRecordId = valueOf(options.recordId);
    if (
      valueOf(options.saving) === true ||
      valueOf(options.editing) !== true ||
      !recordId ||
      !currentRecordId ||
      recordId !== currentRecordId
    ) {
      return false;
    }
    externalChangedRecordId.value = recordId;
    return true;
  }

  function clearExternalChanged() {
    externalChangedRecordId.value = undefined;
  }

  function handleDataChanges(changes: WebDataChange[]) {
    const changeTypes = options.changeTypes ?? [
      webDataChangeTypes.recordUpdated,
      webDataChangeTypes.recordDeleted,
    ];
    for (const change of changes) {
      if (change.moduleAlias === options.moduleAlias && changeTypes.includes(change.type)) {
        markExternalRecordChanged(change.recordId);
      }
    }
  }

  return {
    externalChangedRecordId,
    externallyChanged,
    markExternalRecordChanged,
    clearExternalChanged,
    handleDataChanges,
  };
}

export function useRealtimeRefreshQueue<TKey extends string>(
  options: RealtimeRefreshQueueOptions<TKey>,
): RealtimeRefreshQueue<TKey> {
  const queue = createRealtimeRefreshQueue(options);
  onUnmounted(() => queue.dispose());
  return queue;
}

export function createRealtimeRefreshQueue<TKey extends string>(
  options: RealtimeRefreshQueueOptions<TKey>,
): RealtimeRefreshQueue<TKey> {
  const pendingKeys = new Set<TKey>();
  const keyVersions = new Map<TKey, number>();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let version = 0;
  let active = true;

  function enqueue(key: TKey | TKey[] | undefined) {
    if (!active) {
      return;
    }
    const keys = Array.isArray(key) ? key : [key];
    for (const item of keys) {
      if (item) {
        pendingKeys.add(item);
      }
    }
    if (pendingKeys.size === 0 || timer) {
      return;
    }
    timer = setTimeout(flush, options.delay ?? 0);
  }

  function flush() {
    timer = undefined;
    if (!active || pendingKeys.size === 0) {
      return;
    }
    const keys = Array.from(pendingKeys);
    pendingKeys.clear();
    version += 1;
    const runVersion = version;
    keys.forEach((key) => keyVersions.set(key, runVersion));
    void options.load({
      keys,
      isLatest: (key) => active && keyVersions.get(key) === runVersion,
      active: () => active,
    });
  }

  function dispose() {
    active = false;
    reset();
  }

  function reset() {
    if (timer) {
      clearTimeout(timer);
      timer = undefined;
    }
    pendingKeys.clear();
    keyVersions.clear();
  }

  return { enqueue, reset, dispose };
}

export function usePageRealtimeSubscription(factory: () => PageRealtimeSubscription) {
  let subscription: PageRealtimeSubscription | undefined;
  onMounted(() => {
    subscription = factory();
  });
  onUnmounted(() => {
    subscription?.unsubscribe();
    subscription = undefined;
  });
}

function matchesBusinessEvent(event: WebBusinessRealtimeEvent, options: PageBusinessEventOptions) {
  const types = Array.isArray(options.type) ? options.type : options.type ? [options.type] : [];
  if (types.length > 0 && !types.includes(event.type)) {
    return false;
  }
  if (options.moduleAlias && event.moduleAlias !== options.moduleAlias) {
    return false;
  }
  const recordId = valueOf(options.recordId);
  if (recordId && event.recordId !== recordId) {
    return false;
  }
  return options.predicate?.(event) ?? true;
}

function matchesDataChange(change: WebDataChange, options: PageDataChangeOptions) {
  if (options.moduleAlias && change.moduleAlias !== options.moduleAlias) {
    return false;
  }
  const recordId = valueOf(options.recordId);
  if (recordId && change.recordId !== recordId) {
    return false;
  }
  return options.predicate?.(change) ?? true;
}

function valueOf<T>(value: T | (() => T | undefined) | undefined) {
  return typeof value === 'function' ? (value as () => T | undefined)() : value;
}
