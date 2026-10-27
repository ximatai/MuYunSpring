import { computed, inject, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue';
import {
  createAssistantTurnRequester,
  createModuleContext,
  useAssistantSurfaceHost,
  type HttpClient,
} from '@muyun/web-core';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import { assistantBusinessScopeKey } from '../dynamic-page-runtime/assistantBusinessScope';
import {
  createWorkflowConfigurationAssistantSurface,
  type WorkflowConfigurationAssistantAdapter,
} from './workflowConfigurationAssistantSurface';
import { waitForConfigurationEditor } from './configurationEditorNavigation';

/** Page-instance lifecycle and fresh server action facts; never conversation history authority. */
export function useWorkflowConfigurationAssistant(options: {
  http: HttpClient;
  moduleAlias(): string;
  definitionId(): string;
  adapter: WorkflowConfigurationAssistantAdapter;
  revision(): string;
  interactionRevision(): string;
}) {
  const host = useAssistantSurfaceHost();
  const user = useCurrentUserContext();
  const businessScope = inject(assistantBusinessScopeKey, undefined);
  const scope = computed(() =>
    JSON.stringify([
      user?.value?.userId,
      user?.value?.tenantId,
      user?.value?.organizationId,
      user?.value?.system,
      businessScope?.value,
      options.moduleAlias(),
      options.definitionId(),
    ]),
  );
  const active = ref(true);
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
    if (!active.value) active.value = true;
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
    active.value = false;
    clear();
  });
  onBeforeUnmount(() => {
    active.value = false;
    clear();
  });
  return { scope, active, readable, writable, accessLoading };
}
