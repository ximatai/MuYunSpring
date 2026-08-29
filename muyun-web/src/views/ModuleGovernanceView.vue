<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { RecordDetailPanel, RecordRelationTabs } from '@muyun/platform-components';
import { UiEmpty } from '@muyun/vue-ui-antdv';
import MetadataOrchestrationView from './MetadataOrchestrationView.vue';
import PageCompositionWorkspace from './PageCompositionWorkspace.vue';
import { moduleGovernanceTabs, type ModuleGovernanceTab } from './moduleGovernanceWorkspaceView';

defineOptions({ name: 'ModuleGovernanceView' });

const props = defineProps<{
  moduleAlias: string;
  moduleTitle?: string;
  governanceTab?: ModuleGovernanceTab;
}>();

const activeTab = ref<ModuleGovernanceTab>(props.governanceTab ?? 'metadata');
const tabs: Array<{ key: ModuleGovernanceTab; title: string }> = [
  { key: 'overview', title: '概览' },
  { key: 'metadata', title: '数据模型' },
  { key: 'capabilities', title: '能力' },
  { key: 'actions', title: '动作' },
  { key: 'ui', title: '页面配置' },
  { key: 'diagnostics', title: '运行与诊断' },
];

watch(
  () => props.governanceTab,
  (tab) => {
    activeTab.value = tab ?? 'metadata';
  },
);

const activeTabTitle = computed(() => tabs.find((tab) => tab.key === activeTab.value)?.title ?? '数据模型');
const placeholderDescription = computed(() => {
  if (activeTab.value === 'capabilities') return '能力范围、依赖关系与字段贡献将在此治理。';
  if (activeTab.value === 'actions') return '模块动作治理将在迁移后接入此处。';
  if (activeTab.value === 'ui') return '页面编排器正在接入新的页面修订发布链路。';
  if (activeTab.value === 'diagnostics') return '已发布配置的解析结果与运行诊断将在此展示。';
  return '模块配置完成度与关键阻塞项将在此汇总。';
});

function selectTab(key: string) {
  if ((moduleGovernanceTabs as readonly string[]).includes(key)) activeTab.value = key as ModuleGovernanceTab;
}
</script>

<template>
  <section class="module-governance">
    <header class="module-governance__header">
      <RecordRelationTabs :tabs="tabs" :active-key="activeTab" @update:active-key="selectTab" />
    </header>

    <MetadataOrchestrationView
      v-if="activeTab === 'metadata'"
      class="module-governance__surface"
      :module-alias="moduleAlias"
      :module-title="moduleTitle"
    />
    <PageCompositionWorkspace
      v-else-if="activeTab === 'ui'"
      class="module-governance__surface"
      :module-alias="moduleAlias"
      :module-title="moduleTitle"
    />
    <RecordDetailPanel
      v-else
      class="module-governance__surface"
      :title="activeTabTitle"
      :subtitle="moduleTitle ?? moduleAlias"
    >
      <UiEmpty :description="placeholderDescription" />
    </RecordDetailPanel>
  </section>
</template>

<style scoped>
.module-governance {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 10px;
  height: 100%;
  min-height: 0;
}

.module-governance__header {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.module-governance__surface {
  min-height: 0;
}
</style>
