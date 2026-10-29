import { onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue';
import {
  createAssistantTurnRequester,
  createModuleContext,
  useAssistantSurfaceHost,
  type HttpClient,
} from '@muyun/web-core';
import {
  createCodeConfigurationAssistantSurface,
  type CodeConfigurationAssistantAdapter,
} from './codeConfigurationAssistantSurface';
import { waitForConfigurationEditor } from './configurationEditorNavigation';

export function useCodeConfigurationAssistant(options: {
  http: HttpClient;
  scopedHttp(): HttpClient;
  scope(): string;
  revision(): string;
  active(): boolean;
  adapter: CodeConfigurationAssistantAdapter;
}) {
  const host = useAssistantSurfaceHost();
  const previewable = ref(false);
  const readable = ref(false),
    writable = ref(false),
    loading = ref(false);
  let generation = 0;
  let unregister: (() => void) | undefined;
  let pageKey: string | undefined;
  function clear() {
    generation++;
    previewable.value = readable.value = writable.value = loading.value = false;
    unregister?.();
    unregister = undefined;
  }
  async function authorize() {
    clear();
    if (!host || !options.active()) return;
    const sequence = generation,
      scope = options.scope();
    loading.value = true;
    try {
      const context = createModuleContext({
        http: options.scopedHttp(),
        moduleAlias: 'platform.code_rule',
        runtimeAccess: 'VIEW',
      });
      const runtime = await context.runtime.ready;
      if (sequence !== generation || scope !== options.scope() || !options.active()) return;
      const allowed = (code: string) =>
        runtime.actions.some((action) => action.actionCode === code && action.authorized === true);
      readable.value = allowed('configurationQuery') && allowed('viewTree');
      writable.value = readable.value && allowed('saveTree');
      previewable.value = readable.value && allowed('preview');
    } catch {
      /* Failed authorization never inherits prior scope decisions. */
    } finally {
      if (sequence === generation) {
        loading.value = false;
        register();
      }
    }
  }
  function register() {
    if (!host || unregister || !options.active()) return;
    pageKey ??= host.activePageInstanceKey();
    if (!pageKey) return;
    const adapter: CodeConfigurationAssistantAdapter = {
      ...options.adapter,
      state: () => {
        const state = options.adapter.state();
        return {
          ...state,
          ready: state.ready && readable.value,
          previewable: previewable.value,
          editable: state.editable && writable.value,
          busy: state.busy || loading.value,
        };
      },
    };
    unregister = host.registry.register({
      pageInstanceKey: pageKey,
      executionScopeKey: options.scope,
      contextRevision: () =>
        JSON.stringify([
          options.revision(),
          readable.value,
          writable.value,
          previewable.value,
          loading.value,
        ]),
      interactionRevision: options.revision,
      settle: (signal) => waitForConfigurationEditor(() => !adapter.state().busy, signal),
      surface: createCodeConfigurationAssistantSurface(adapter, createAssistantTurnRequester(options.http)),
    });
  }
  watch(
    options.scope,
    () => {
      clear();
      if (options.active()) void authorize();
    },
    { flush: 'sync' },
  );
  onMounted(() => {
    void authorize();
  });
  onActivated(() => {
    void authorize();
  });
  onDeactivated(clear);
  onBeforeUnmount(clear);
}
