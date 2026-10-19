import { onMounted, onScopeDispose, ref, watch } from 'vue';
import { usePageDataChange } from '../platform-admin-runtime/pageRealtime';

/** Fail closed until the current authenticated identity has a confirmed backend decision. */
export function useAssistantAvailability(options: {
  identity: () => string | undefined;
  load: (signal: AbortSignal) => Promise<boolean>;
}) {
  const available = ref(false);
  let revision = 0;
  let disposed = false;
  let controller: AbortController | undefined;
  async function refresh() {
    const owner = options.identity();
    const run = ++revision;
    controller?.abort();
    if (!owner || disposed) {
      available.value = false;
      return;
    }
    controller = new AbortController();
    const signal = controller.signal;
    const active = () => !disposed && !signal.aborted && revision === run && options.identity() === owner;
    try {
      const result = await options.load(signal);
      if (active()) available.value = result === true;
    } catch {
      if (active()) available.value = false;
    }
  }
  watch(
    options.identity,
    () => {
      available.value = false;
      void refresh();
    },
    { immediate: true, flush: 'sync' },
  );
  const onFocus = () => {
    void refresh();
  };
  onMounted(() => window.addEventListener('focus', onFocus));
  for (const moduleAlias of ['platform.ai_model_configuration', 'platform.ai_model_provider']) {
    usePageDataChange({ moduleAlias, handler: refresh });
  }
  onScopeDispose(() => {
    disposed = true;
    revision++;
    controller?.abort();
    window.removeEventListener('focus', onFocus);
  });
  return { available, refresh };
}
