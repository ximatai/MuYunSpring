import { computed, ref, watch, type Ref } from 'vue';
import {
  resolveRecordFormFieldState,
  type RecordFormFieldDescriptor,
  type RecordFormFieldState,
  type RecordFormFieldValue,
  type RecordFormRecord,
} from '@muyun/platform-components';
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
}

/**
 * Keeps application contribution validity at the same save boundary as the
 * descriptor-owned form, without giving contributed components access to the
 * module HTTP client or mutable form state.
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
    return {
      ...state,
      ...(field ? { field } : {}),
      setField: options.setField,
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
    const draft = options.draft.value ?? {};
    return {
      mode: options.mode.value,
      draft: createReadonlyCardRecordSnapshot(draft),
      fields: [...options.fields.value.keys()].map((fieldName) =>
        Object.freeze(
          resolveRecordFormFieldState(fieldName, {
            fields: options.fields.value,
            record: draft,
          }),
        ),
      ),
      formSessionKey: options.formSessionKey.value,
    };
  }

  return { valid, contextFor, stateSnapshot };
}

function normalizeValidity(validity: ModulePageFormContributionValidity): ModulePageFormContributionValidity {
  return {
    valid: validity.valid,
    ...(validity.errors && Object.keys(validity.errors).length > 0 ? { errors: { ...validity.errors } } : {}),
  };
}
