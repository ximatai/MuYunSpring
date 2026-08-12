import { computed, ref } from 'vue';
import { normalizeError, type AppError, type ModuleContext } from '@muyun/web-core';
import type { UiConfirmOptions } from '@muyun/vue-ui-antdv';
import {
  createPlatformActionResultReactionHandlers,
  handlePlatformActionSuccess,
  mergePlatformActionResultReactionHandlers,
  platformActionResultReactions,
  withPlatformActionResultReactions,
  type PlatformActionResultReaction,
  type PlatformActionResultReactionHandler,
} from './platformActionResultFeedback';
import {
  matchesPlatformActionErrorHandler,
  presentPlatformError,
  presentPlatformMessage,
  type PlatformActionErrorHandler,
} from './platformErrorFeedback';
import { actionConfirmationRequiredText } from './actionConfirmation';

export type StaticCrudCardMode = 'view' | 'edit' | 'create';
export type StaticCrudActionCode = 'create' | 'update' | 'delete' | 'enable' | 'disable';

export interface StaticCrudRecord {
  id?: string;
  title?: string;
  enabled?: boolean;
  version?: number;
}

export type StaticCrudConfirmAction = (options: UiConfirmOptions) => Promise<boolean>;

export interface StaticCrudActionErrorContext<TRecord extends StaticCrudRecord> {
  actionCode: StaticCrudActionCode;
  mode: StaticCrudCardMode;
  record: TRecord | undefined;
}

export type StaticCrudActionErrorHandler<TRecord extends StaticCrudRecord> = PlatformActionErrorHandler<
  StaticCrudActionErrorContext<TRecord>
>;

export interface StaticCrudManagementOptions<TRecord extends StaticCrudRecord> {
  context: ModuleContext<TRecord>;
  confirmAction: StaticCrudConfirmAction;
  emptyDraft: () => TRecord;
  normalizeDraft: (record: TRecord, selected: TRecord | undefined, mode: StaticCrudCardMode) => TRecord;
  copyRecord?: (record: TRecord) => TRecord;
  titleOf: (record: TRecord) => string;
  fallbackTitle: string;
  createTitle: string;
  requiredMessage: string;
  isValid: (record: TRecord) => boolean;
  recordName: string;
  deleteTitle: string;
  saveDeniedMessage: string;
  createDeniedMessage: string;
  enableDeniedMessage: string;
  deleteDeniedMessage: (record: TRecord | undefined) => string;
  canDeleteRecord?: (record: TRecord) => boolean;
  canEnableRecord?: (record: TRecord, actionCode: 'enable' | 'disable') => boolean;
  validateBeforeSave?: (record: TRecord) => string | undefined;
  actionErrorHandlers?: StaticCrudActionErrorHandler<TRecord>[];
  actionResultReactionHandlers?: Record<string, PlatformActionResultReactionHandler | undefined>;
}

export function useFlatCrudManagementState<TRecord extends StaticCrudRecord>(
  options: StaticCrudManagementOptions<TRecord>,
) {
  const selected = ref<TRecord>();
  const draft = ref<TRecord>(options.emptyDraft());
  const mode = ref<StaticCrudCardMode>('view');
  const reloadKey = ref(0);
  const saving = ref(false);
  const actionError = ref<string>();
  const copyRecord = options.copyRecord ?? ((record: TRecord) => ({ ...record }) as TRecord);
  const actionResultReactionHandlers = createStaticCrudActionReactionHandlers();

  const cardTitle = computed(() => {
    if (mode.value === 'create') {
      return options.createTitle;
    }
    return selected.value ? options.titleOf(selected.value) : options.fallbackTitle;
  });
  const readonly = computed(() => mode.value === 'view');
  const canCreate = computed(() => options.context.can('create') === true);
  const canUpdate = computed(() => Boolean(selected.value?.id) && options.context.can('update') === true);
  const canDelete = computed(
    () =>
      Boolean(selected.value?.id) &&
      (!selected.value || options.canDeleteRecord?.(selected.value) !== false) &&
      options.context.can('delete') === true,
  );
  const canEnable = computed(() => {
    const record = selected.value;
    if (!record?.id) {
      return false;
    }
    const actionCode = record.enabled === false ? 'enable' : 'disable';
    return (
      options.canEnableRecord?.(record, actionCode) !== false && options.context.can(actionCode) === true
    );
  });
  const canMutate = computed(() => canUpdate.value || canDelete.value || canEnable.value);

  function handleListLoaded(records: TRecord[]) {
    const matched = selected.value?.id ? records.find((item) => item.id === selected.value?.id) : undefined;
    if (matched) {
      selected.value = matched;
      if (mode.value === 'view') {
        draft.value = copyRecord(matched);
      }
      return;
    }
    const first = records[0];
    selected.value = first;
    draft.value = first ? copyRecord(first) : options.emptyDraft();
    mode.value = first || !canCreate.value ? 'view' : 'create';
  }

  function handleReadonlyListLoaded(records: TRecord[]) {
    const matched = selected.value?.id ? records.find((item) => item.id === selected.value?.id) : undefined;
    const current = matched ?? records[0];
    selected.value = current;
    draft.value = current ? copyRecord(current) : options.emptyDraft();
    mode.value = 'view';
  }

  function handleSelect(record: TRecord) {
    selected.value = record;
    draft.value = copyRecord(record);
    mode.value = 'view';
    clearFeedback();
  }

  function startCreate() {
    if (!canCreate.value) {
      presentActionMessage(options.createDeniedMessage, 'authorization');
      return;
    }
    draft.value = options.emptyDraft();
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
    draft.value = selected.value ? copyRecord(selected.value) : options.emptyDraft();
    mode.value = selected.value ? 'view' : 'create';
    clearFeedback();
  }

  async function save(): Promise<boolean> {
    if (saving.value) {
      return false;
    }
    if (mode.value === 'view') {
      return false;
    }
    if (mode.value === 'create' ? !canCreate.value : !canUpdate.value) {
      presentActionMessage(options.saveDeniedMessage, 'authorization');
      return false;
    }
    clearFeedback();
    const validDraft = options.normalizeDraft(draft.value, selected.value, mode.value);
    if (!options.isValid(validDraft)) {
      presentActionMessage(options.requiredMessage, 'validation');
      return false;
    }
    const validationError = options.validateBeforeSave?.(validDraft);
    if (validationError) {
      presentActionMessage(validationError, 'validation');
      return false;
    }

    saving.value = true;
    try {
      await options.context.runtime.ready;
      const crud = options.context.abilities.crud();
      const result =
        mode.value === 'create'
          ? await crud.insert(validDraft)
          : await crud.update(requiredId(validDraft, options.recordName), validDraft);
      const saved = result.record;
      selected.value = saved;
      draft.value = copyRecord(saved);
      await presentActionSuccess(result, [
        platformActionResultReactions.closeEditor(),
        platformActionResultReactions.refreshList(),
      ]);
      return true;
    } catch (cause) {
      handleActionError(cause, mode.value === 'create' ? 'create' : 'update');
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function toggleEnabled() {
    if (saving.value) {
      return;
    }
    if (!selected.value?.id) {
      return;
    }
    if (!canEnable.value) {
      presentActionMessage(options.enableDeniedMessage, 'authorization');
      return;
    }
    clearFeedback();
    saving.value = true;
    try {
      await options.context.runtime.ready;
      const crud = options.context.abilities.crud();
      const enable = options.context.abilities.enable();
      const result =
        selected.value.enabled === false
          ? await enable.enable(selected.value.id, { version: requiredVersion(selected.value) })
          : await enable.disable(selected.value.id, { version: requiredVersion(selected.value) });
      const refreshed = await crud.view(selected.value.id);
      selected.value = refreshed;
      draft.value = copyRecord(refreshed);
      await presentActionSuccess(result, [platformActionResultReactions.refreshList()]);
    } catch (cause) {
      handleActionError(cause, selected.value?.enabled === false ? 'enable' : 'disable');
    } finally {
      saving.value = false;
    }
  }

  async function removeSelected() {
    if (saving.value) {
      return;
    }
    const record = selected.value;
    if (!record?.id) {
      return;
    }
    if (!canDelete.value) {
      presentActionMessage(options.deleteDeniedMessage(record), 'authorization');
      return;
    }
    const runtimeContext = options.context.runtime.snapshot() ?? (await options.context.runtime.ready);
    const requiredText = actionConfirmationRequiredText(
      runtimeContext.uiDescriptor,
      'delete',
      record as Record<string, unknown>,
    );
    const confirmed = await options.confirmAction({
      title: options.deleteTitle,
      content: `确认删除${options.recordName}「${options.titleOf(record)}」？`,
      okText: '删除',
      danger: true,
      requiredText,
    });
    if (!confirmed) {
      return;
    }
    if (selected.value?.id !== record.id) {
      presentActionMessage('待删除记录已变化，请重新确认删除操作');
      return;
    }
    clearFeedback();
    saving.value = true;
    try {
      await options.context.runtime.ready;
      const crud = options.context.abilities.crud();
      const result = await crud.delete(record.id, { version: requiredVersion(record) });
      await presentActionSuccess(result, [
        platformActionResultReactions.clearSelection(),
        platformActionResultReactions.refreshList(),
      ]);
    } catch (cause) {
      handleActionError(cause, 'delete');
    } finally {
      saving.value = false;
    }
  }

  function handleActionError(cause: unknown, actionCode: StaticCrudActionCode) {
    const error = normalizeError(cause);
    const context: StaticCrudActionErrorContext<TRecord> = {
      actionCode,
      mode: mode.value,
      record: selected.value,
    };
    if (tryHandleActionError(error, context)) {
      return;
    }
    actionError.value = error.message;
    presentPlatformError(error, { source: 'static-crud-action', phase: 'action' });
  }

  function tryHandleActionError(error: AppError, context: StaticCrudActionErrorContext<TRecord>) {
    const handlers = options.actionErrorHandlers ?? [];
    const handler = handlers.find((item) => matchesActionErrorHandler(error, item));
    if (!handler) {
      return false;
    }
    return handler.handle(error, context) !== false;
  }

  function presentActionMessage(
    message: string,
    phase: 'validation' | 'authorization' | 'action' = 'action',
  ) {
    actionError.value = message;
    presentPlatformMessage(message, { source: 'static-crud-action', phase });
  }

  function clearFeedback() {
    actionError.value = undefined;
  }

  function presentActionSuccess(result: unknown, defaultReactions: PlatformActionResultReaction[]) {
    return handlePlatformActionSuccess(withPlatformActionResultReactions(result, defaultReactions), {
      source: 'static-crud-action',
      phase: 'action',
      reactionHandlers: actionResultReactionHandlers,
    });
  }

  function createStaticCrudActionReactionHandlers() {
    const defaultHandlers = createPlatformActionResultReactionHandlers({
      refreshList: () => {
        reloadKey.value += 1;
      },
      closeEditor: () => {
        mode.value = 'view';
      },
      clearSelection: () => {
        selected.value = undefined;
        draft.value = options.emptyDraft();
        mode.value = canCreate.value ? 'create' : 'view';
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
    handleListLoaded,
    handleReadonlyListLoaded,
    handleSelect,
    startCreate,
    startEdit,
    cancelEdit,
    save,
    toggleEnabled,
    removeSelected,
  };
}

function requiredId(record: StaticCrudRecord, recordName: string) {
  if (!record.id) {
    throw new Error(`${recordName} ID 不能为空`);
  }
  return record.id;
}

function requiredVersion(record: StaticCrudRecord) {
  if (record.version == null) {
    throw new Error('记录版本不能为空，请重新加载后再操作');
  }
  return record.version;
}

function matchesActionErrorHandler<TRecord extends StaticCrudRecord>(
  error: AppError,
  handler: StaticCrudActionErrorHandler<TRecord>,
) {
  return matchesPlatformActionErrorHandler(error, handler);
}
