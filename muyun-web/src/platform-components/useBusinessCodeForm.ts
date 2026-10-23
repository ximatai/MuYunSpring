import { computed, onScopeDispose, ref, watch } from 'vue';
import {
  actionResultData,
  normalizeError,
  userFacingErrorMessage,
  type LanguageContext,
  type ModuleContext,
} from '@muyun/web-core';
import type { BusinessCodeFieldState } from '@muyun/web-contracts';
import type {
  RecordFormFieldDescriptor,
  RecordFormFieldState,
  RecordFormRecord,
} from './recordFormFieldModel';

/** Candidates live here, separate from the mutation draft and from user-entered manual codes. */
export function useBusinessCodeForm(options: {
  record: () => RecordFormRecord;
  parent: () => RecordFormRecord | undefined;
  context: () => ModuleContext<unknown> | undefined;
  entityAlias: () => string | undefined;
  fields: () => Map<string, RecordFormFieldDescriptor> | undefined;
  fieldNames: () => string[];
  mode: () => 'create' | 'edit' | 'view';
  session: () => string | number | undefined;
  language: LanguageContext;
}) {
  const policies = ref(new Map<string, BusinessCodeFieldState>());
  const loading = ref(false);
  const error = ref('');
  const initialAssigned = new Set<string>();
  const names = computed(() =>
    options.fieldNames().filter((name) => options.fields()?.get(name)?.businessCode),
  );
  let sequence = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const hasValue = (value: unknown) => value != null && String(value).trim() !== '';
  watch(
    () =>
      JSON.stringify([
        options.context()?.moduleAlias,
        options.entityAlias(),
        options.record().id,
        options.session(),
        names.value,
      ]),
    () => {
      initialAssigned.clear();
      names.value.forEach((name) => {
        if (hasValue(options.record()[name])) initialAssigned.add(name);
      });
      policies.value = new Map();
    },
    { immediate: true },
  );
  async function load(version = ++sequence) {
    if (version !== sequence) return;
    const context = options.context();
    if (!context || !names.value.length || options.mode() === 'view') {
      loading.value = false;
      return;
    }
    loading.value = true;
    error.value = '';
    try {
      const response = actionResultData(
        await context.http.request<BusinessCodeFieldState[]>({
          method: 'POST',
          path: `/${encodeURIComponent(context.moduleAlias)}/code/form-state`,
          body: { entityAlias: options.entityAlias(), record: options.record(), parent: options.parent() },
        }),
      );
      if (version !== sequence) return;
      policies.value = new Map(
        (response ?? [])
          .filter((policy) => names.value.includes(policy.fieldName))
          .map((policy) => [policy.fieldName, policy]),
      );
    } catch (cause) {
      if (version === sequence) error.value = userFacingErrorMessage(normalizeError(cause), options.language);
    } finally {
      if (version === sequence) loading.value = false;
    }
  }
  watch(
    [
      () => options.context(),
      () => options.context()?.http,
      () =>
        JSON.stringify([
          options.context()?.moduleAlias,
          options.entityAlias(),
          options.record(),
          options.parent(),
          names.value,
          options.mode(),
          options.session(),
        ]),
    ],
    () => {
      const version = ++sequence;
      if (timer) clearTimeout(timer);
      if (!names.value.length || options.mode() === 'view') {
        policies.value = new Map();
        loading.value = false;
        error.value = '';
        return;
      }
      error.value = '';
      loading.value = true;
      timer = setTimeout(() => {
        void load(version);
      }, 120);
    },
    { immediate: true },
  );
  onScopeDispose(() => {
    ++sequence;
    if (timer) clearTimeout(timer);
  });
  function field(base: RecordFormFieldState): RecordFormFieldState {
    if (!base.businessCode || options.mode() === 'view') return base;
    const policy = policies.value.get(base.fieldName);
    const readOnly = base.readOnly || !policy || policy.readOnly;
    const required =
      !readOnly &&
      ((policy?.mode === 'MANUAL' && base.businessCodeRequired === true) ||
        (policy?.mode === 'AUTO_WITH_MANUAL_EDIT' &&
          options.mode() === 'edit' &&
          initialAssigned.has(base.fieldName)));
    return { ...base, readOnly, required };
  }
  function errorOf(name: string) {
    if (!names.value.includes(name) || options.mode() === 'view') return undefined;
    if (error.value) return error.value;
    if (loading.value) return options.language.t('platform.form.code.loading', '正在读取编号策略…');
    const policy = policies.value.get(name);
    const retained =
      options.mode() === 'edit' && initialAssigned.has(name) && hasValue(options.record()[name]);
    if (!policy || (!policy.configured && policy.trigger === 'SAVE' && !retained))
      return options.language.t(
        'platform.form.code.unconfigured',
        '尚未配置有效的编号规则，请管理员在「模块管理 → 业务编码」中完成配置。',
      );
    return undefined;
  }
  return {
    field,
    errorOf,
    policies,
    loading,
    error,
    retry: () => {
      if (timer) clearTimeout(timer);
      return load();
    },
  };
}
