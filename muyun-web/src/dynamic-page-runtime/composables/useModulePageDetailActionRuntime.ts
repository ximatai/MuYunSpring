import { computed, ref, type Ref } from 'vue';
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
    if (!record || typeof record.version !== 'number') return;
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
    localEditOpen.value = false;
    localEditBaseline.value = undefined;
  }

  async function submitLocalEdit() {
    const block = localEditBlock.value;
    const draft = localEditDraft.value;
    const recordId =
      options.selectedRecord.value?.id == null ? undefined : String(options.selectedRecord.value.id);
    if (
      !block ||
      !draft ||
      !recordId ||
      typeof draft.version !== 'number' ||
      !block.submitPath ||
      options.localEditValid?.value === false
    )
      return;
    localEditSaving.value = true;
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
      let refreshFailure: unknown;
      if (block.refreshStrategy?.detail !== false) {
        try {
          const refreshed = await options.context.crud.view(recordId);
          options.detail.resolveLoad(refreshed);
          options.recordChanged?.(refreshed);
        } catch (cause) {
          refreshFailure = cause;
        }
      }
      if (block.refreshStrategy?.list !== false) options.refreshList();
      closeLocalEditSession();
      await options.presentSuccess(result, `${block.title ?? block.actionCode}成功`, 'module-local-edit');
      if (refreshFailure) reportRefreshFailure(refreshFailure, 'module-local-edit');
    } catch (cause) {
      options.presentError(cause, 'module-local-edit');
    } finally {
      localEditSaving.value = false;
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
      await options.presentSuccess(result, `${block.title ?? block.actionCode}成功`, 'module-action-block');
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
    handleConfiguredAction,
    dismissLocalEdit,
    submitLocalEdit,
  };
}
