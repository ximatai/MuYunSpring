<script setup lang="ts">
import { recordPresentationLabel, useLanguageContext } from '@muyun/web-core';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { UiButton } from '@muyun/vue-ui-antdv';
import { useWorkbenchNavigation } from '@muyun/platform-workbench';
import type { ModulePageDetailSectionContext } from '@muyun/dynamic-page-runtime';
import type { MenuRecord, MenuScheme, MenuTreeNode } from '@muyun/web-contracts';
import { createModuleMenuClient, menuPlacements } from './moduleMenuClient';

const language = useLanguageContext();

const props = defineProps<{ context: ModulePageDetailSectionContext }>();
const navigation = useWorkbenchNavigation();
const entryTrees = ref<{ scheme: MenuScheme; tree: MenuTreeNode[] }[]>([]);
const entries = computed(() =>
  entryTrees.value.flatMap(({ scheme, tree }) =>
    menuPlacements(
      tree,
      recordPresentationLabel(scheme, 'title', language) ?? scheme.alias ?? scheme.id,
      language,
    ).filter(
      ({ menu }) => menu.moduleAlias === String(props.context.record.alias ?? props.context.record.id ?? ''),
    ),
  ),
);
const visible = ref(new Map<string, MenuRecord>());
const loading = ref(false);
const loadFailed = ref(false);
const error = computed(() =>
  loadFailed.value
    ? language.t(
        'platform.screen.ModuleMenuEntriesSection.cannot-load-menu-entries-right-now-check-that-you-have',
        '暂时无法读取菜单入口，请确认有菜单管理权限后重试。',
      )
    : '',
);
let revision = 0;
onBeforeUnmount(() => revision++);

watch(
  () => [props.context.record.id, props.context.refreshKey],
  () => void load(),
  { immediate: true },
);
async function load() {
  const current = ++revision;
  const client = createModuleMenuClient(props.context.module.http);
  loading.value = true;
  loadFailed.value = false;
  entryTrees.value = [];
  visible.value = new Map();
  try {
    const [schemes, mine] = await Promise.all([client.schemes(), client.visible()]);
    const results = await Promise.all(
      schemes.map(async (scheme) => ({ scheme, tree: await client.tree(scheme.id!) })),
    );
    if (current !== revision) return;
    entryTrees.value = results;
    visible.value = new Map(menuPlacements(mine).map(({ menu }) => [menu.id, menu]));
  } catch {
    if (current === revision) loadFailed.value = true;
  } finally {
    if (current === revision) loading.value = false;
  }
}
</script>

<template>
  <div class="module-menu-entries">
    <span v-if="loading">{{
      language.t('platform.screen.WorkflowAdministrationView.loading', '正在加载…')
    }}</span>
    <div v-else-if="error" role="status">
      {{ error }}
      <UiButton type="link" @click="load">{{
        language.t('platform.screen.WorkflowWorkbenchView.retry', '重试')
      }}</UiButton>
    </div>
    <span v-else-if="!entries.length">{{
      language.t(
        'platform.screen.ModuleMenuEntriesSection.no-menu-entry-yet-use-add-to-menu-above-to',
        '尚未添加到菜单，可通过上方“添加到菜单”设置入口。',
      )
    }}</span>
    <div v-for="entry in entries" :key="entry.menu.id" class="menu-entry">
      <span
        >{{ entry.path
        }}<small v-if="entry.menu.enabled === false">
          {{ language.t('platform.screen.ModuleMenuEntriesSection.disabled', '· 已停用') }}</small
        ></span
      >
      <UiButton
        v-if="visible.has(entry.menu.id) && navigation?.openMenu"
        type="link"
        @click="navigation.openMenu(visible.get(entry.menu.id)!)"
      >
        <span>{{ language.t('platform.screen.ModuleMenuEntriesSection.open', '打开') }}</span>
      </UiButton>
    </div>
  </div>
</template>

<style scoped>
.module-menu-entries {
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.menu-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.menu-entry span {
  overflow-wrap: anywhere;
}
</style>
