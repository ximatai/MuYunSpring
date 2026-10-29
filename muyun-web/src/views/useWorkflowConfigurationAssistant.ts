import { onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue';
import {
  createAssistantTurnRequester,
  createModuleContext,
  useAssistantSurfaceHost,
  type HttpClient,
} from '@muyun/web-core';
import {
  createWorkflowConfigurationAssistantSurface,
  type WorkflowConfigurationAssistantAdapter,
} from './workflowConfigurationAssistantSurface';
import type { useWorkflowConfigurationSession } from './useWorkflowConfigurationSession';
import { waitForConfigurationEditor } from './configurationEditorNavigation';

/** Page-instance lifecycle and fresh server action facts; never conversation history authority. */
export function useWorkflowConfigurationAssistant(options: {
  session: Pick<ReturnType<typeof useWorkflowConfigurationSession>, 'scope' | 'active'>;
  http: HttpClient;
  definitionId(): string;
  adapter: WorkflowConfigurationAssistantAdapter;
  revision(): string;
  interactionRevision(): string;
}) {
  const host = useAssistantSurfaceHost();
  const { scope, active } = options.session;
  const accessLoading = ref(false);
  const readable = ref(false);
  const writable = ref(false);
  let generation = 0;
  let unregister: (() => void) | undefined;
  let pageInstanceKey: string | undefined;
  function clear() {
    generation++;
    readable.value = writable.value = false;
    accessLoading.value = false;
    unregister?.();
    unregister = undefined;
  }
  async function authorize() {
    clear();
    if (!active.value || !host) return;
    const expected = scope.value;
    const sequence = generation;
    accessLoading.value = true;
    try {
      // A new context prevents cached permissions from a previous authenticated scope being reused.
      const context = createModuleContext({
        http: options.http,
        moduleAlias: 'platform.workflow.definition',
        runtimeAccess: 'VIEW',
      });
      const runtime = await context.runtime.ready;
      if (!active.value || sequence !== generation || scope.value !== expected) return;
      const recordId = options.definitionId();
      const decisions = await context.recordActions(recordId);
      if (!active.value || sequence !== generation || scope.value !== expected) return;
      const allowed = (code: string) =>
        runtime.actions.some((action) => action.actionCode === code && action.authorized === true) &&
        decisions.recordId === recordId &&
        decisions.actions.some((action) => action.actionCode === code && action.available === true);
      readable.value = allowed('viewWorkflowDesign');
      writable.value = readable.value && allowed('editWorkflowDesign');
    } catch {
      // Missing or failed authorization is unavailable, never inherited from the previous page.
    } finally {
      if (sequence === generation) accessLoading.value = false;
    }
  }
  function register() {
    if (!host || unregister || !active.value || !pageInstanceKey) return;
    unregister = host.registry.register({
      pageInstanceKey,
      executionScopeKey: () => scope.value,
      contextRevision: options.revision,
      interactionRevision: options.interactionRevision,
      settle: (signal) =>
        waitForConfigurationEditor(() => !accessLoading.value && !options.adapter.state().busy, signal),
      surface: createWorkflowConfigurationAssistantSurface(
        options.adapter,
        createAssistantTurnRequester(options.http),
        () => host.capabilities?.() ?? [],
      ),
    });
  }
  function activate() {
    if (!active.value) return;
    if (!pageInstanceKey) pageInstanceKey = host?.activePageInstanceKey();
    if (unregister || accessLoading.value) return;
    register();
    void authorize().then(register);
  }
  watch(
    scope,
    () => {
      clear();
      if (active.value) void authorize().then(register);
    },
    { flush: 'sync' },
  );
  onMounted(activate);
  onActivated(activate);
  onDeactivated(() => {
    clear();
  });
  onBeforeUnmount(() => {
    clear();
  });
  return { readable, writable, accessLoading };
}
