import { computed, ref, watch, type Ref } from 'vue';
import {
  resolveRecordFormFieldState,
  type RecordFormFieldDescriptor,
  type RecordFormFieldState,
  type RecordFormFieldValue,
  type RecordFormRecord,
  type QueryListRecord,
} from '@muyun/platform-components';
import type { WebPageResponse, WebQueryRequest } from '@muyun/web-contracts';
import {
  createReadonlyCardRecordSnapshot,
  type ModulePageFormContribution,
  type ModulePageFormContributionContext,
  type ModulePageFormContributionState,
  type ModulePageFormContributionValidity,
} from '../modulePageEnhancements';

interface UseModulePageFormContributionRuntimeOptions {
  contributions: Ref<readonly ModulePageFormContribution[]>;
  mode: Ref<'create' | 'edit' | 'view'>;
  draft: Ref<RecordFormRecord | undefined>;
  fields: Ref<Map<string, RecordFormFieldDescriptor>>;
  formSessionKey: Ref<number>;
  setField(fieldName: string, value: RecordFormFieldValue): void;
  queryRecords?(request?: WebQueryRequest): Promise<WebPageResponse<QueryListRecord>>;
}

/**
 * Keeps application contribution validity at the same save boundary as the
 * descriptor-owned form, without giving contributed components mutable form
 * state or an arbitrary transport. Read queries stay on the host context.
 */
export function useModulePageFormContributionRuntime(options: UseModulePageFormContributionRuntimeOptions) {
  const validityByContribution = ref(new Map<string, ModulePageFormContributionValidity>());
  const valid = computed(() =>
    [...validityByContribution.value.values()].every((validity) => validity.valid),
  );

  watch(
    options.formSessionKey,
    () => {
      validityByContribution.value = new Map();
    },
    { flush: 'sync' },
  );

  function contextFor(
    contribution: ModulePageFormContribution,
    field?: Readonly<RecordFormFieldState>,
  ): ModulePageFormContributionContext {
    const state = stateSnapshot();
    const resolvedField = field
      ? state.fields.find((candidate) => candidate.fieldName === field.fieldName)
      : undefined;
    return {
      ...state,
      ...(resolvedField ? { field: resolvedField } : {}),
      setField(fieldName, value) {
        const target = stateSnapshot().fields.find((candidate) => candidate.fieldName === fieldName);
        if (!target) {
          throw new Error(`表单增强不能写入未声明字段：${fieldName}`);
        }
        if (target.readOnly) {
          throw new Error(`表单增强不能写入只读字段：${fieldName}`);
        }
        options.setField(fieldName, value);
      },
      queryRecords(request) {
        if (!options.queryRecords) {
          return Promise.reject(new Error('当前表单宿主未提供受控读取能力'));
        }
        return options.queryRecords(request);
      },
      formSessionKey: options.formSessionKey.value,
      reportValidity(validity) {
        validityByContribution.value = new Map(validityByContribution.value).set(
          contribution.key,
          normalizeValidity(validity),
        );
      },
    };
  }

  function stateSnapshot(): ModulePageFormContributionState {
    return createModulePageFormState(
      options.mode.value,
      options.draft.value ?? {},
      options.fields.value,
      options.formSessionKey.value,
    );
  }

  return { valid, contextFor, stateSnapshot };
}

/** One read projection for form presentation policies and controlled editor adapters. */
export function createModulePageFormState(
  mode: 'create' | 'edit' | 'view',
  draft: RecordFormRecord,
  fields: Map<string, RecordFormFieldDescriptor>,
  formSessionKey: number,
): ModulePageFormContributionState {
  return {
    mode,
    draft: createReadonlyCardRecordSnapshot(draft),
    fields: [...fields.keys()].map((fieldName) =>
      detachedReadonlyFieldState(resolveRecordFormFieldState(fieldName, { fields, record: draft, mode })),
    ),
    formSessionKey,
  };
}

function detachedReadonlyFieldState(state: RecordFormFieldState): Readonly<RecordFormFieldState> {
  return createReadonlyCardRecordSnapshot(
    state as unknown as Record<string, unknown>,
  ) as Readonly<RecordFormFieldState>;
}

function normalizeValidity(validity: ModulePageFormContributionValidity): ModulePageFormContributionValidity {
  return {
    valid: validity.valid,
    ...(validity.errors && Object.keys(validity.errors).length > 0 ? { errors: { ...validity.errors } } : {}),
  };
}
