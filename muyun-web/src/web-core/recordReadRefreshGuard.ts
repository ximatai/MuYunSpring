import {
  computed,
  hasInjectionContext,
  inject,
  provide,
  ref,
  watch,
  onBeforeUnmount,
  type InjectionKey,
  type Ref,
} from 'vue';

interface RecordReadSession {
  holds: Ref<number>;
  revision: Ref<number>;
}
const key: InjectionKey<RecordReadSession> = Symbol('record-read-refresh-guard');

/** Nested record hosts share a guard so a read refresh cannot destroy an open maintenance session. */
export function provideRecordReadRefreshGuard() {
  const session = inject(key, undefined) ?? { holds: ref(0), revision: ref(0) };
  provide(key, session);
  return {
    blocked: computed(() => session.holds.value > 0),
    invalidate: () => {
      session.revision.value++;
    },
  };
}

export function useRecordReadRefreshRevision() {
  const session = hasInjectionContext() ? inject(key, undefined) : undefined;
  return computed(() => session?.revision.value ?? 0);
}

export function useRecordReadRefreshGuard(held: () => boolean) {
  const session = inject(key, undefined);
  let registered = false;
  function update(value: boolean) {
    if (!session || value === registered) return;
    session.holds.value += value ? 1 : -1;
    registered = value;
  }
  watch(held, update, { immediate: true, flush: 'sync' });
  onBeforeUnmount(() => update(false));
}
