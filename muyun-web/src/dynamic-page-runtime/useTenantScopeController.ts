import { computed, ref, shallowRef, watch, type Ref } from 'vue';
import { createModuleContext, type ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';

/** Page-lifetime control state; business sessions only receive an immutable selection. */
export function useTenantScopeController(
  context: ModuleContext<QueryListRecord>,
  recordOnly: boolean,
  blocked: Ref<boolean>,
) {
  const currentUser = useCurrentUserContext();
  const selected = shallowRef<QueryListRecord>();
  const selectedId = computed(() => (selected.value?.id == null ? '' : String(selected.value.id)));
  const tenantScopeContext = shallowRef<ModuleContext<QueryListRecord>>();
  const tenantScopeReloadKey = ref(0);
  // The control context discovers the initial policy, while each frozen business
  // session confirms it with its own runtime response. This lets a retry recover
  // from a failed initial discovery and lets preview reloads adopt a new policy.
  const businessRequired = ref<boolean>();
  const required = computed(
    () => !recordOnly && (businessRequired.value ?? context.runtime.snapshot()?.tenantRequired) === true,
  );
  const tenantScopeFixedByIdentity = computed(
    () => required.value && currentUser?.value?.system === false && Boolean(currentUser?.value?.tenantId),
  );
  const tenantScopeExplorerVisible = computed(() => required.value && currentUser?.value?.system !== false);
  const tenantScopeExplorerCount = computed(() => (tenantScopeExplorerVisible.value ? 1 : 0));
  let initialized = false;
  function changeTenantScope(record: QueryListRecord | undefined) {
    if (blocked.value || recordOnly) return;
    if (String(record?.id ?? '') === selectedId.value) return;
    selected.value = record;
  }
  function handleTenantScopeLoaded(records: QueryListRecord[], initialFullResult = false, total?: number) {
    if (!initialFullResult || total !== 1 || initialized || selectedId.value) return;
    initialized = true;
    if (records.length === 1 && records[0]?.id != null) changeTenantScope(records[0]);
  }
  function setRequired(next: boolean) {
    businessRequired.value = next;
  }
  watch(
    [required, () => currentUser?.value],
    ([needsTenant, user]) => {
      if (!needsTenant) {
        selected.value = undefined;
        return;
      }
      if (user?.system === false && user.tenantId)
        changeTenantScope({ id: user.tenantId, title: String(user.tenantId) });
      else if (user?.system !== false && !tenantScopeContext.value)
        tenantScopeContext.value = createModuleContext<QueryListRecord>({
          moduleAlias: 'iam.tenant',
          http: context.http,
          runtimeAccess: 'REFERENCE',
        });
    },
    { immediate: true },
  );
  return {
    selected,
    selectedId,
    blocked,
    tenantScopeContext,
    tenantScopeReloadKey,
    tenantScopeFixedByIdentity,
    tenantScopeExplorerVisible,
    tenantScopeExplorerCount,
    changeTenantScope,
    handleTenantScopeLoaded,
    setRequired,
  };
}
export type TenantScopeController = ReturnType<typeof useTenantScopeController>;
