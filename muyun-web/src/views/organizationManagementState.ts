import { computed, ref } from 'vue';
import type { Organization } from '@muyun/web-contracts';
import { normalizeError, type ModuleContext } from '@muyun/web-core';
import type { UiConfirmOptions } from '@muyun/vue-ui-antdv';
import {
  createPlatformActionResultReactionHandlers,
  handlePlatformActionSuccess,
  mergePlatformActionResultReactionHandlers,
  platformActionResultReactions,
  presentPlatformError,
  presentPlatformMessage,
  type PlatformActionResultReaction,
  type PlatformActionResultReactionHandler,
  withPlatformActionResultReactions,
} from '@muyun/platform-components';

type CardMode = 'view' | 'edit' | 'create';
type ConfirmAction = (options: UiConfirmOptions) => Promise<boolean>;

export interface OrganizationManagementStateOptions {
  actionResultReactionHandlers?: Record<string, PlatformActionResultReactionHandler | undefined>;
}

export function createOrganizationManagementState(
  organizationContext: ModuleContext<Organization>,
  confirmAction: ConfirmAction,
  options: OrganizationManagementStateOptions = {},
) {
  const selected = ref<Organization>();
  const draft = ref<Organization>(emptyDraft());
  const mode = ref<CardMode>('view');
  const reloadKey = ref(0);
  const saving = ref(false);
  const actionError = ref<string>();
  const actionResultReactionHandlers = createOrganizationActionReactionHandlers();

  const cardTitle = computed(() => {
    if (mode.value === 'create') {
      return draft.value.parentId ? '新建下级机构' : '新建根机构';
    }
    return selected.value?.title ?? '机构详情';
  });
  const readonly = computed(() => mode.value === 'view');
  const canCreate = computed(() => organizationContext.can('create') === true);
  const canUpdate = computed(() => Boolean(selected.value?.id) && organizationContext.can('update') === true);
  const canDelete = computed(() => Boolean(selected.value?.id) && organizationContext.can('delete') === true);
  const canEnable = computed(() => {
    const actionCode = selected.value?.enabled === false ? 'enable' : 'disable';
    return Boolean(selected.value?.id) && organizationContext.can(actionCode) === true;
  });
  const canMutate = computed(() => canUpdate.value || canDelete.value || canEnable.value);

  function handleTreeLoaded(records: Organization[]) {
    if (selected.value?.id && records.some((item) => item.id === selected.value?.id)) {
      return;
    }
    const first = records[0];
    selected.value = first;
    draft.value = first ? copyRecord(first) : emptyDraft();
    mode.value = 'view';
  }

  function handleSelect(organization: Organization) {
    selected.value = organization;
    draft.value = copyRecord(organization);
    mode.value = 'view';
    clearFeedback();
  }

  function startCreateRoot() {
    draft.value = emptyDraft();
    mode.value = 'create';
    clearFeedback();
  }

  function startCreateChild() {
    draft.value = emptyDraft(selected.value?.id);
    mode.value = 'create';
    clearFeedback();
  }

  function startEdit() {
    if (!selected.value) {
      return;
    }
    draft.value = copyRecord(selected.value);
    mode.value = 'edit';
    clearFeedback();
  }

  function cancelEdit() {
    draft.value = selected.value ? copyRecord(selected.value) : emptyDraft();
    mode.value = 'view';
    clearFeedback();
  }

  async function save() {
    if (mode.value === 'view') {
      return;
    }
    if (mode.value === 'create' ? !canCreate.value : !canUpdate.value) {
      presentActionMessage('当前用户无权保存机构');
      return;
    }
    clearFeedback();
    const validDraft = normalizedDraft(draft.value);
    if (!validDraft.title || !validDraft.code) {
      presentActionMessage('机构名称和机构编码不能为空');
      return;
    }

    saving.value = true;
    try {
      await organizationContext.runtime.ready;
      const crud = organizationContext.abilities.crud();
      const result =
        mode.value === 'create'
          ? await crud.insert(validDraft)
          : await crud.update(requiredId(validDraft), validDraft);
      const saved = result.record;
      selected.value = saved;
      draft.value = copyRecord(saved);
      await presentActionSuccess(result, [
        platformActionResultReactions.closeEditor(),
        platformActionResultReactions.refreshList(),
      ]);
    } catch (cause) {
      presentActionCause(cause);
    } finally {
      saving.value = false;
    }
  }

  async function toggleEnabled() {
    if (!selected.value?.id) {
      return;
    }
    if (!canEnable.value) {
      presentActionMessage('当前用户无权变更机构启停状态');
      return;
    }
    clearFeedback();
    saving.value = true;
    try {
      await organizationContext.runtime.ready;
      const crud = organizationContext.abilities.crud();
      const enable = organizationContext.abilities.enable();
      const result =
        selected.value.enabled === false
          ? await enable.enable(selected.value.id, { version: selected.value.version! })
          : await enable.disable(selected.value.id, { version: selected.value.version! });
      const refreshed = await crud.view(selected.value.id);
      selected.value = refreshed;
      draft.value = copyRecord(refreshed);
      await presentActionSuccess(result, [platformActionResultReactions.refreshList()]);
    } catch (cause) {
      presentActionCause(cause);
    } finally {
      saving.value = false;
    }
  }

  async function removeSelected() {
    if (!selected.value?.id) {
      return;
    }
    if (!canDelete.value) {
      presentActionMessage('当前用户无权删除机构');
      return;
    }
    const confirmed = await confirmAction({
      title: '删除机构',
      content: `确认删除机构「${selected.value.title ?? selected.value.code ?? selected.value.id}」？`,
      okText: '删除',
      danger: true,
    });
    if (!confirmed) {
      return;
    }
    clearFeedback();
    saving.value = true;
    try {
      await organizationContext.runtime.ready;
      const crud = organizationContext.abilities.crud();
      const result = await crud.delete(selected.value.id, { version: selected.value.version! });
      await presentActionSuccess(result, [
        platformActionResultReactions.clearSelection(),
        platformActionResultReactions.refreshList(),
      ]);
    } catch (cause) {
      presentActionCause(cause);
    } finally {
      saving.value = false;
    }
  }

  function clearFeedback() {
    actionError.value = undefined;
  }

  function presentActionCause(cause: unknown) {
    const error = normalizeError(cause);
    actionError.value = error.message;
    presentPlatformError(error, { source: 'organization-management-action', phase: 'action' });
  }

  function presentActionMessage(message: string) {
    actionError.value = message;
    presentPlatformMessage(message, { source: 'organization-management-action', phase: 'action' });
  }

  function presentActionSuccess(result: unknown, defaultReactions: PlatformActionResultReaction[]) {
    return handlePlatformActionSuccess(withPlatformActionResultReactions(result, defaultReactions), {
      source: 'organization-management-action',
      phase: 'action',
      reactionHandlers: actionResultReactionHandlers,
    });
  }

  function createOrganizationActionReactionHandlers() {
    const defaultHandlers = createPlatformActionResultReactionHandlers({
      refreshList: () => {
        reloadKey.value += 1;
      },
      closeEditor: () => {
        mode.value = 'view';
      },
      clearSelection: () => {
        selected.value = undefined;
        draft.value = emptyDraft();
        mode.value = 'view';
      },
    });
    return mergePlatformActionResultReactionHandlers(defaultHandlers, options.actionResultReactionHandlers);
  }

  return {
    selected,
    draft,
    mode,
    reloadKey,
    saving,
    actionError,
    cardTitle,
    readonly,
    canCreate,
    canUpdate,
    canDelete,
    canEnable,
    canMutate,
    handleTreeLoaded,
    handleSelect,
    startCreateRoot,
    startCreateChild,
    startEdit,
    cancelEdit,
    save,
    toggleEnabled,
    removeSelected,
  };
}

function copyRecord(record: Organization): Organization {
  return { ...record };
}

function emptyDraft(parentId?: string): Organization {
  return {
    parentId,
    enabled: true,
    title: '',
    code: '',
  };
}

function normalizedDraft(record: Organization): Organization {
  return {
    ...record,
    title: record.title?.trim(),
    code: record.code?.trim(),
    parentId: record.parentId?.trim() || undefined,
  };
}

function requiredId(record: Organization) {
  if (!record.id) {
    throw new Error('机构 ID 不能为空');
  }
  return record.id;
}
