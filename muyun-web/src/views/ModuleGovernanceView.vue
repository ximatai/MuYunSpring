<script setup lang="ts">
import { computed, ref, watch, type Component } from 'vue';
import { RecordRelationTabs } from '@muyun/platform-components';
import { useWorkspaceViewNavigation } from '@muyun/platform-workbench';
import ModuleActionManagementView from './ModuleActionManagementView.vue';
import ModuleExperienceProfileOverview from './ModuleExperienceProfileOverview.vue';
import MetadataOrchestrationView from './MetadataOrchestrationView.vue';
import PageCompositionWorkspace from './PageCompositionWorkspace.vue';
import PageCompositionRuntimeDiagnostics from './PageCompositionRuntimeDiagnostics.vue';
import { moduleGovernanceTabs, type ModuleGovernanceTab } from './moduleGovernanceWorkspaceView';

defineOptions({ name: 'ModuleGovernanceView' });

const props = defineProps<{
  moduleAlias: string;
  moduleTitle?: string;
  governanceTab?: ModuleGovernanceTab;
}>();

const activeTab = ref<ModuleGovernanceTab>(props.governanceTab ?? 'overview');
const navigation = useWorkspaceViewNavigation();
const tabs: Array<{ key: ModuleGovernanceTab; title: string }> = [
  { key: 'overview', title: '概览' },
  { key: 'metadata', title: '数据模型' },
  { key: 'actions', title: '动作' },
  { key: 'ui', title: '页面配置' },
  { key: 'diagnostics', title: '运行与诊断' },
];

watch(
  () => props.governanceTab,
  (tab) => {
    activeTab.value = tab ?? 'overview';
  },
);

const activePanel = computed<{ component: Component; props: Record<string, unknown> }>(() => {
  const moduleProps = { moduleAlias: props.moduleAlias, moduleTitle: props.moduleTitle };
  switch (activeTab.value) {
    case 'metadata':
      return { component: MetadataOrchestrationView, props: moduleProps };
    case 'actions':
      return { component: ModuleActionManagementView, props: { ...moduleProps, moduleKind: 'dynamic' } };
    case 'ui':
      return { component: PageCompositionWorkspace, props: moduleProps };
    case 'diagnostics':
      return { component: PageCompositionRuntimeDiagnostics, props: moduleProps };
    case 'overview':
    default:
      return { component: ModuleExperienceProfileOverview, props: moduleProps };
  }
});

function selectTab(key: string) {
  if (!(moduleGovernanceTabs as readonly string[]).includes(key)) return;
  const tab = key as ModuleGovernanceTab;
  activeTab.value = tab;
  navigation.replaceQuery({ governanceTab: tab });
}
</script>

<template>
  <section class="module-governance">
    <header class="module-governance__header">
      <RecordRelationTabs :tabs="tabs" :active-key="activeTab" @update:active-key="selectTab" />
    </header>

    <KeepAlive :max="moduleGovernanceTabs.length">
      <component
        :is="activePanel.component"
        :key="`${moduleAlias}:${activeTab}`"
        class="module-governance__surface"
        v-bind="activePanel.props"
      />
    </KeepAlive>
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
