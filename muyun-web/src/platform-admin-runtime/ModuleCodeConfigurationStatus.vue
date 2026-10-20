<script setup lang="ts">
import { computed, watch } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import { useWorkbenchNavigation, createWorkspaceViewDescriptor } from '@muyun/platform-workbench';
import { UiButton } from '@muyun/vue-ui-antdv';
import type { ModulePageCardAssistantContext } from '@muyun/dynamic-page-runtime';
import { moduleCodeCatalog } from './moduleCodeConfiguration';
import { moduleGovernanceWorkspaceView } from '../views/moduleGovernanceWorkspaceView';

const props = defineProps<{ context: ModulePageCardAssistantContext }>();
const language = useLanguageContext();
const navigation = useWorkbenchNavigation();
const catalog = computed(() => moduleCodeCatalog(props.context.module));
const alias = computed(() => String(props.context.record?.alias ?? props.context.record?.id ?? ''));
const state = computed(() => catalog.value.states.value[alias.value]);
watch(
  [alias, () => props.context.mode, () => props.context.module],
  ([value, mode]) => {
    if (value && mode === 'view' && !catalog.value.states.value[value]) void catalog.value.refresh(value);
  },
  { immediate: true },
);
const reason = computed(() => state.value?.reason);
const visible = computed(
  () =>
    props.context.mode === 'view' &&
    !!alias.value &&
    (state.value?.phase === 'failed' ||
      (props.context.record?.moduleKind === 'dynamic' &&
        state.value?.phase === 'ready' &&
        !state.value.configurable)),
);
function configureModel() {
  navigation?.openPage(
    createWorkspaceViewDescriptor(moduleGovernanceWorkspaceView, {
      moduleAlias: alias.value,
      moduleTitle: String(props.context.record?.title ?? alias.value),
      governanceTab: 'metadata',
    }),
  );
}
</script>

<template>
  <div v-if="visible" class="module-code-status" role="status">
    <template v-if="state?.phase === 'failed'">
      <span>{{
        language.t('platform.code.config.catalog-failed', '暂时无法检查业务编码配置条件，请重试。')
      }}</span>
      <span>{{ state?.error }}</span>
      <UiButton type="link" @click="catalog.refresh(alias)">{{
        language.t('platform.code.config.retry', '重试')
      }}</UiButton>
    </template>
    <template v-else>
      <span>{{
        language.t(
          `platform.code.config.reason.${reason}`,
          reason === 'no-model'
            ? '尚未关联业务数据模型，请先配置模型，再设置编号规则。'
            : '当前数据模型没有可用于编号的字段，请先检查字段配置。',
        )
      }}</span>
      <UiButton v-if="navigation" type="link" @click="configureModel">{{
        language.t('platform.code.config.configure-model', '配置模型')
      }}</UiButton>
    </template>
  </div>
</template>

<style scoped>
.module-code-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
  padding-top: 12px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
</style>
