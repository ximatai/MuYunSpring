<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useLanguageContext, useModuleHttpClient } from '@muyun/web-core';
import { ReferencePicker, type ReferencePickerProvider } from '@muyun/platform-components';
import type { ModuleKind } from '@muyun/web-contracts';
import type { ModulePageFormContributionContext } from '@muyun/dynamic-page-runtime';

const props = defineProps<{ context: ModulePageFormContributionContext }>();
const language = useLanguageContext();
const http = useModuleHttpClient();
interface Target {
  moduleAlias: string;
  moduleTitle: string;
  actionCode: string;
  actionTitle: string;
  source: ModuleKind;
  parametersRequired: boolean | null;
}
const targets = ref<Target[]>([]);
const loading = ref(true);
const failed = ref(false);
const moduleValid = ref(true);
const actionValid = ref(true);
let generation = 0;
const moduleAlias = computed(() => String(props.context.draft.moduleAlias ?? ''));
const actionCode = computed(() => String(props.context.draft.actionCode ?? ''));
const scopeKey = computed(() =>
  JSON.stringify([
    props.context.formSessionKey,
    props.context.draft.executionTenantId,
    language.languageTag.value,
  ]),
);
watch(scopeKey, () => void load(), { immediate: true });
onBeforeUnmount(() => generation++);
async function load() {
  const current = ++generation;
  targets.value = [];
  loading.value = true;
  failed.value = false;
  try {
    const rows = await http.request<Target[]>({
      path: '/platform.scheduled_action_plan/scheduled-targets',
      query: { executionTenantId: String(props.context.draft.executionTenantId ?? '') || undefined },
    });
    if (current === generation) targets.value = rows;
  } catch {
    if (current === generation) failed.value = true;
  } finally {
    if (current === generation) loading.value = false;
  }
}
const unavailable = computed(() =>
  language.t('platform.scheduled-target.unavailable', '当前目标已失效或无权调度，请重新选择'),
);
const chosen = computed(() =>
  targets.value.find((row) => row.moduleAlias === moduleAlias.value && row.actionCode === actionCode.value),
);
watch(
  [loading, failed, chosen, moduleAlias, actionCode, moduleValid, actionValid],
  () => {
    const valid =
      props.context.mode === 'view' ||
      (!loading.value && !failed.value && !!chosen.value && moduleValid.value && actionValid.value);
    props.context.reportValidity({ valid, errors: valid ? {} : { actionCode: unavailable.value } });
  },
  { immediate: true },
);
function provider(field: 'moduleAlias' | 'actionCode'): ReferencePickerProvider {
  const rows = targets.value;
  const module = moduleAlias.value;
  const options =
    field === 'moduleAlias'
      ? [
          ...new Map(
            rows.map((row) => [
              row.moduleAlias,
              { id: row.moduleAlias, title: row.moduleTitle, subtitle: row.moduleAlias },
            ]),
          ).values(),
        ]
      : rows
          .filter((row) => row.moduleAlias === module)
          .map((row) => ({
            id: row.actionCode,
            title: row.actionTitle,
            subtitle: `${row.actionCode} · ${row.source === 'static' ? language.t('platform.scheduled-target.static', '静态') : language.t('platform.scheduled-target.dynamic', '动态')}`,
          }));
  return {
    identity: {
      targetModuleAlias: 'platform.scheduled_action_plan',
      source: { kind: 'businessPurpose', id: `scheduled-target:${field}` },
      authorizationScope: scopeKey.value,
    },
    async searchPage(request) {
      const filtered = options.filter((row) =>
        `${row.title} ${row.subtitle}`.toLocaleLowerCase().includes(request.keyword.toLocaleLowerCase()),
      );
      const start = (request.pageNum - 1) * request.pageSize;
      return { records: filtered.slice(start, start + request.pageSize), total: filtered.length };
    },
    async resolve(ids) {
      return ids.map(
        (id) => options.find((row) => row.id === id) ?? { id, title: id, unavailable: true, disabled: true },
      );
    },
  };
}
const moduleProvider = computed(() => provider('moduleAlias'));
const actionProvider = computed(() => provider('actionCode'));
function updateModule(value: string | string[] | undefined) {
  const next = typeof value === 'string' ? value : undefined;
  if (next === moduleAlias.value) return;
  props.context.setField('moduleAlias', next);
  props.context.setField('actionCode', undefined);
}
function updateAction(value: string | string[] | undefined) {
  props.context.setField('actionCode', typeof value === 'string' ? value : undefined);
}
function disabled(field: string) {
  const state = props.context.fields.find((item) => item.fieldName === field);
  return props.context.mode === 'view' || state?.readOnly === true || loading.value || failed.value;
}
function label(field: string) {
  return props.context.fields.find((item) => item.fieldName === field)?.label ?? field;
}
</script>

<template>
  <div class="scheduled-target-fields">
    <label>
      <span>{{ label('moduleAlias') }}</span>
      <ReferencePicker
        :value="moduleAlias || undefined"
        :provider="moduleProvider"
        :reload-key="scopeKey"
        mode="dropdown"
        :disabled="disabled('moduleAlias')"
        @update:value="updateModule"
        @validity-change="moduleValid = $event.valid"
      />
    </label>
    <label>
      <span>{{ label('actionCode') }}</span>
      <ReferencePicker
        :value="actionCode || undefined"
        :provider="actionProvider"
        :reload-key="`${scopeKey}:${moduleAlias}`"
        mode="dropdown"
        :disabled="disabled('actionCode') || !moduleAlias"
        @update:value="updateAction"
        @validity-change="actionValid = $event.valid"
      />
    </label>
    <p v-if="loading" role="status">{{ language.t('platform.scheduled-target.loading', '正在加载候选') }}</p>
    <p v-else-if="failed" role="alert">
      {{ language.t('platform.scheduled-target.load-failed', '候选加载失败，请重试') }}
      <button type="button" @click="load">{{ language.t('platform.scheduled-target.retry', '重试') }}</button>
    </p>
    <p v-else-if="!loading && (moduleAlias || actionCode) && !chosen" role="alert">{{ unavailable }}</p>
    <p v-if="chosen?.parametersRequired">
      {{ language.t('platform.scheduled-target.parameters-required', '此动作需要参数，请填写静态参数 JSON') }}
    </p>
    <p v-else-if="chosen && chosen.parametersRequired == null">
      {{
        language.t(
          'platform.scheduled-target.parameters-unspecified',
          '此动作未声明参数要求，保存时将校验参数',
        )
      }}
    </p>
  </div>
</template>

<style scoped>
.scheduled-target-fields {
  display: grid;
  gap: 12px;
}
label {
  display: grid;
  gap: 6px;
}
p {
  margin: 0;
  color: var(--muyun-text-muted);
}
</style>
