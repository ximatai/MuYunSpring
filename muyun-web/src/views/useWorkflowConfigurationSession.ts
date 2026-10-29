import { computed, inject, onActivated, onBeforeUnmount, onDeactivated, ref } from 'vue';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import { assistantBusinessScopeKey } from '../dynamic-page-runtime/assistantBusinessScope';
import { useWorkflowDesignSession } from '../platform-components/useWorkflowDesignSession';

/** Workflow editor ownership is shared by manual and assistant entry points. */
export function useWorkflowConfigurationSession(options: { moduleAlias(): string; definitionId(): string }) {
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
  onActivated(() => {
    active.value = true;
  });
  onDeactivated(() => {
    active.value = false;
  });
  onBeforeUnmount(() => {
    active.value = false;
  });
  const submissionStatus = ref<'idle' | 'unknown' | 'conflict' | 'review'>('idle');
  const recoveryRequired = computed(() => submissionStatus.value !== 'idle');
  return { ...useWorkflowDesignSession(), scope, active, submissionStatus, recoveryRequired };
}
