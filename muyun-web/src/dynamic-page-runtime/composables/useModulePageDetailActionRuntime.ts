import { AppError, useLanguageContext, userFacingErrorMessage } from '@muyun/web-core';
import { isRecordSaveRejection, recordSaveFieldErrors } from '../recordSaveFeedback';
import { computed, getCurrentScope, onScopeDispose, ref, watch, type Ref } from 'vue';
import { recordDraftFingerprint } from '@muyun/platform-components';
import type {
  RecordActionItem,
  RecordFormFieldDescriptor,
  RecordFormRecord,
} from '@muyun/platform-components';
import type { PageBootstrap, PageBootstrapActionBlock } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';

interface DetailLoader {
  resolveLoad(record: QueryListRecord): void;
}

export interface ModulePageDetailActionRuntimeOptions {
  context: ModuleContext<QueryListRecord>;
  pageBootstrap: Ref<PageBootstrap | undefined>;
  selectedRecord: Ref<QueryListRecord | undefined>;
  editorMode: Ref<'create' | 'edit' | 'view'>;
  detail: DetailLoader;
  refreshList(): void;
  /** Current local-edit form validity, owned by RecordFormFields at the host boundary. */
  localEditValid?: Ref<boolean>;
  presentSuccess(result: unknown, fallbackMessage: string, source: string): Promise<unknown> | unknown;
  presentError(cause: unknown, source: string): void;
  /** Reports a failed post-mutation detail read without treating the mutation itself as failed. */
  reportRefreshFailure?(cause: unknown, source: string): void;
  recordChanged?(record: QueryListRecord): void;
}

/**
 * Runs server-issued detail action contracts and their typed local-edit forms.
 *
 * The host retains layout and action routing. This runtime owns the action
 * payload, refresh policy and error/success lifecycle, so presentation
 * templates never acquire request or authorization responsibilities.
 */
export function useModulePageDetailActionRuntime(options: ModulePageDetailActionRuntimeOptions) {
  const language = useLanguageContext();
  const supportedDetailActionBlocks = computed(() =>
    (options.pageBootstrap.value?.resolvedConfig.actionBlocks ?? []).filter(
      (block): block is PageBootstrapActionBlock => block.type === 'action',
    ),
  );
  const supportedDetailActions = computed<RecordActionItem[]>(() =>
    supportedDetailActionBlocks.value.map((block, index) => ({
      key: `page-action-block:${block.uiConfigId ?? 'entry'}:${block.key ?? block.actionCode}:${index}`,
      actionCode: block.actionCode,
      title: block.title ?? options.context.runtimeAction(block.actionCode)?.title ?? block.actionCode,
      actionLevel: block.importance?.toLowerCase() as RecordActionItem['actionLevel'],
    })),
  );
  const localEditActionBlocks = computed(() =>
    (options.pageBootstrap.value?.resolvedConfig.actionBlocks ?? []).filter(
      (
        block,
      ): block is PageBootstrapActionBlock & {
        localEditForm: NonNullable<PageBootstrapActionBlock['localEditForm']>;
      } => block.type === 'localEdit' && block.localEditForm != null,
    ),
  );
  const localEditActions = computed<RecordActionItem[]>(() =>
    localEditActionBlocks.value.map((block, index) => ({
      key: `page-local-edit:${block.uiConfigId ?? 'entry'}:${block.key ?? block.actionCode}:${index}`,
      actionCode: block.actionCode,
      title: block.title ?? options.context.runtimeAction(block.actionCode)?.title ?? block.actionCode,
      actionLevel: block.importance?.toLowerCase() as RecordActionItem['actionLevel'],
    })),
  );
  const detailPageActions = computed(() => [...supportedDetailActions.value, ...localEditActions.value]);
  const localEditOpen = ref(false);
  const localEditSaving = ref(false);
  const localEditBlock = ref<(typeof localEditActionBlocks.value)[number]>();
  const localEditDraft = ref<RecordFormRecord>();
  const localEditBaseline = ref<string>();
  const localEditFieldErrors = ref<Readonly<Record<string, string>>>({});
  const localEditErrorMessage = ref<string>();
  let localEditSession = 0;
  let rejectedValues = new Map<string, string>();

  function clearLocalEditFeedback() {
    localEditFieldErrors.value = {};
    localEditErrorMessage.value = undefined;
    rejectedValues = new Map();
  }

  // Compare values rather than deep-watch oldValue, which shares the mutated draft.
  watch(
    localEditDraft,
    (draft) => {
      const changed = [...rejectedValues].filter(
        ([name, value]) => recordDraftFingerprint({ value: draft?.[name] }) !== value,
      );
      if (!changed.length) return;
      const errors = { ...localEditFieldErrors.value };
      for (const [name] of changed) {
        delete errors[name];
        rejectedValues.delete(name);
      }
      localEditFieldErrors.value = errors;
      localEditErrorMessage.value = undefined;
    },
    { deep: true, flush: 'sync' },
  );

  watch(
    [() => options.selectedRecord.value?.id, options.editorMode, options.pageBootstrap],
    closeLocalEditSession,
    { flush: 'sync' },
  );
  watch(
    localEditOpen,
    (open) => {
      if (!open) closeLocalEditSession();
    },
    { flush: 'sync' },
  );
  if (getCurrentScope()) onScopeDispose(closeLocalEditSession);
  const localEditDirty = computed(
    () =>
      localEditOpen.value &&
      localEditDraft.value != null &&
      localEditBaseline.value !== recordDraftFingerprint(localEditDraft.value),
  );
  const localEditFields = computed<Map<string, RecordFormFieldDescriptor>>(() => {
    const form = localEditBlock.value?.localEditForm;
    const controlsByAlias = new Map((form?.fieldUiControls ?? []).map((control) => [control.alias, control]));
    return new Map(
      (form?.fields ?? [])
        .filter((field) => field.visible !== false && !field.relationAlias)
        .map((field) => [
          field.fieldName,
          {
            fieldRef: { fieldName: field.fieldName },
            label: field.fieldTitle,
            visible: { constant: true },
            required: { constant: field.requiredOverride === true },
            readOnly: { constant: field.readOnly === true },
            // The published local-edit form carries resolved controls separately so the
            // editor consumes the same semantic renderer facts as its target FORM.
            // `uiType` remains only as a compatibility fallback for older bootstraps.
            fieldControl: controlsByAlias.get(field.fieldUiControlAlias ?? ''),
            uiType: field.fieldUiControlAlias,
            valueType: field.valueType,
            columnSpan: field.columnSpan,
          },
        ]),
    );
  });

  function handleConfiguredAction(action: { key?: string }) {
    const localIndex = localEditActions.value.findIndex((item) => item.key === action.key);
    if (localIndex >= 0) {
      openLocalEdit(localEditActionBlocks.value[localIndex]);
      return true;
    }
    const index = supportedDetailActions.value.findIndex((item) => item.key === action.key);
    const block = index < 0 ? undefined : supportedDetailActionBlocks.value[index];
    const recordId =
      options.selectedRecord.value?.id == null ? undefined : String(options.selectedRecord.value.id);
    if (!block || !recordId || options.editorMode.value !== 'view') return false;
    void executeActionBlock(block, recordId);
    return true;
  }

  function openLocalEdit(block: (typeof localEditActionBlocks.value)[number]) {
    const record = options.selectedRecord.value;
    if (localEditSaving.value || options.editorMode.value !== 'view') return;
    if (!record || typeof record.version !== 'number') return;
    localEditSession += 1;
    clearLocalEditFeedback();
    localEditBlock.value = block;
    localEditDraft.value = {
      id: record.id,
      version: record.version,
      ...Object.fromEntries(
        (block.localEditForm.fields ?? []).map((field) => [field.fieldName, record[field.fieldName]]),
      ),
    };
    localEditBaseline.value = recordDraftFingerprint(localEditDraft.value);
    localEditOpen.value = true;
  }

  function dismissLocalEdit() {
    if (localEditSaving.value) return;
    closeLocalEditSession();
  }

  function closeLocalEditSession() {
    localEditSession += 1;
    localEditOpen.value = false;
    localEditSaving.value = false;
    localEditBaseline.value = undefined;
    clearLocalEditFeedback();
  }

  async function submitLocalEdit() {
    const block = localEditBlock.value;
    const draft = localEditDraft.value;
    const recordId =
      options.selectedRecord.value?.id == null ? undefined : String(options.selectedRecord.value.id);
    if (
      !localEditOpen.value ||
      localEditSaving.value ||
      !block ||
      !draft ||
      !recordId ||
      typeof draft.version !== 'number' ||
      !block.submitPath ||
      options.localEditValid?.value === false
    )
      return;
    const session = localEditSession;
    const snapshot = recordDraftFingerprint(draft);
    const isCurrent = () => session === localEditSession && localEditOpen.value;
    clearLocalEditFeedback();
    localEditSaving.value = true;
    let committed = false;
    try {
      const fieldNames = [...localEditFields.value.keys()];
      const values = Object.fromEntries(fieldNames.map((fieldName) => [fieldName, draft[fieldName]]));
      const result = await options.context.http.request<unknown>({
        method: 'POST',
        path: block.submitPath.replace('{recordId}', encodeURIComponent(recordId)),
        body: {
          recordId,
          record: { id: recordId, version: draft.version, values },
          fieldNames,
          payload: {
            [block.localEditForm.submitContract.uiConfigIdPayloadKey]: block.localEditForm.uiConfigId,
          },
        },
      });
      committed = true;
      if (!isCurrent()) return;
      let refreshFailure: unknown;
      if (block.refreshStrategy?.detail !== false) {
        try {
          const refreshed = await options.context.crud.view(recordId);
          if (!isCurrent()) return;
          options.detail.resolveLoad(refreshed);
          options.recordChanged?.(refreshed);
        } catch (cause) {
          refreshFailure = cause;
        }
      }
      if (!isCurrent()) return;
      if (block.refreshStrategy?.list !== false) options.refreshList();
      localEditBaseline.value = undefined;
      await options.presentSuccess(
        result,
        language.t('platform.screen.useModulePageDetailActionRuntime.value-completed', '{value1}成功', {
          value1: block.title ?? block.actionCode,
        }),
        'module-local-edit',
      );
      if (!isCurrent()) return;
      if (refreshFailure) reportRefreshFailure(refreshFailure, 'module-local-edit');
      closeLocalEditSession();
    } catch (cause) {
      if (!isCurrent()) return;
      if (committed) {
        closeLocalEditSession();
        reportRefreshFailure(cause, 'module-local-edit');
      } else if (cause instanceof AppError && isRecordSaveRejection(cause)) {
        if (snapshot !== recordDraftFingerprint(draft)) return;
        // RecordFormFields renders inline feedback only for editable fields.
        const feedbackFields = new Map(
          [...localEditFields.value].filter(([, field]) => field.readOnly?.constant !== true),
        );
        localEditFieldErrors.value = recordSaveFieldErrors(cause, feedbackFields, language);
        if (!Object.keys(localEditFieldErrors.value).length)
          localEditErrorMessage.value = userFacingErrorMessage(cause, language);
        rejectedValues = new Map(
          [...localEditFields.value.keys()].map((name) => [
            name,
            recordDraftFingerprint({ value: draft[name] }),
          ]),
        );
      } else {
        options.presentError(cause, 'module-local-edit');
      }
    } finally {
      if (isCurrent()) localEditSaving.value = false;
    }
  }

  async function executeActionBlock(block: PageBootstrapActionBlock, recordId: string) {
    try {
      const result = await options.context.http.request<unknown>({
        method: 'POST',
        path: `/${encodeURIComponent(options.context.moduleAlias)}/${encodeURIComponent(block.actionCode)}/${encodeURIComponent(recordId)}`,
        body: {},
      });
      let refreshFailure: unknown;
      try {
        const refreshed = await options.context.crud.view(recordId);
        options.detail.resolveLoad(refreshed);
        options.recordChanged?.(refreshed);
      } catch (cause) {
        refreshFailure = cause;
      }
      options.refreshList();
      await options.presentSuccess(
        result,
        language.t('platform.screen.useModulePageDetailActionRuntime.value-completed', '{value1}成功', {
          value1: block.title ?? block.actionCode,
        }),
        'module-action-block',
      );
      if (refreshFailure) reportRefreshFailure(refreshFailure, 'module-action-block');
    } catch (cause) {
      options.presentError(cause, 'module-action-block');
    }
  }

  function reportRefreshFailure(cause: unknown, source: string) {
    if (options.reportRefreshFailure) options.reportRefreshFailure(cause, source);
    else options.presentError(cause, source);
  }

  return {
    supportedDetailActions,
    localEditActionBlocks,
    detailPageActions,
    localEditOpen,
    localEditSaving,
    localEditBlock,
    localEditDraft,
    localEditDirty,
    localEditFields,
    localEditFieldErrors,
    localEditErrorMessage,
    handleConfiguredAction,
    dismissLocalEdit,
    submitLocalEdit,
  };
}
