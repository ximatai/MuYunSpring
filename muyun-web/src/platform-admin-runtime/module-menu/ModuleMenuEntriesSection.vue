<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { UiButton } from '@muyun/vue-ui-antdv';
import { useWorkbenchNavigation } from '@muyun/platform-workbench';
import type { ModulePageDetailSectionContext } from '@muyun/dynamic-page-runtime';
import type { MenuRecord } from '@muyun/web-contracts';
import { createModuleMenuClient, menuPlacements, type MenuPlacement } from './moduleMenuClient';

const props = defineProps<{ context: ModulePageDetailSectionContext }>();
const navigation = useWorkbenchNavigation();
const entries = ref<MenuPlacement[]>([]);
const visible = ref(new Map<string, MenuRecord>());
const loading = ref(false);
const error = ref('');
let revision = 0;
onBeforeUnmount(() => revision++);

watch(
  () => [props.context.record.id, props.context.refreshKey],
  () => void load(),
  { immediate: true },
);
async function load() {
  const current = ++revision;
  const alias = String(props.context.record.alias ?? props.context.record.id ?? '');
  const client = createModuleMenuClient(props.context.module.http);
  loading.value = true;
  error.value = '';
  entries.value = [];
  visible.value = new Map();
  try {
    const [schemes, mine] = await Promise.all([client.schemes(), client.visible()]);
    const results = await Promise.all(
      schemes.map(async (scheme) =>
        menuPlacements(await client.tree(scheme.id!), scheme.title ?? scheme.alias ?? scheme.id).filter(
          ({ menu }) => menu.moduleAlias === alias,
        ),
      ),
    );
    if (current !== revision) return;
    entries.value = results.flat();
    visible.value = new Map(menuPlacements(mine).map(({ menu }) => [menu.id, menu]));
  } catch {
    if (current === revision) error.value = '暂时无法读取菜单入口，请确认有菜单管理权限后重试。';
  } finally {
    if (current === revision) loading.value = false;
  }
}
</script>

<template>
  <div class="module-menu-entries">
    <span v-if="loading">正在加载…</span>
    <div v-else-if="error" role="status">{{ error }} <UiButton type="link" @click="load">重试</UiButton></div>
    <span v-else-if="!entries.length">尚未添加到菜单，可通过上方“添加到菜单”设置入口。</span>
    <div v-for="entry in entries" :key="entry.menu.id" class="menu-entry">
      <span>{{ entry.path }}<small v-if="entry.menu.enabled === false"> · 已停用</small></span>
      <UiButton
        v-if="visible.has(entry.menu.id) && navigation?.openMenu"
        type="link"
        @click="navigation.openMenu(visible.get(entry.menu.id)!)"
      >
        <span>打开</span>
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
