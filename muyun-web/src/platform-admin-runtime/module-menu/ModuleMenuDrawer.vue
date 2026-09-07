<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { UiButton, UiInput, UiSelect, UiTreeSelect } from '@muyun/vue-ui-antdv';
import { useModuleContext, type ModuleRuntimeContext } from '@muyun/web-core';
import { useWorkbenchNavigation } from '@muyun/platform-workbench';
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';
import type { MenuOpenMode, MenuRecord, MenuScheme, MenuTreeNode } from '@muyun/web-contracts';
import { createModuleMenuClient, menuDirectoryOptions, menuPlacements } from './moduleMenuClient';

const props = defineProps<{ context: ModulePageDrawerContext }>();
const navigation = useWorkbenchNavigation();
const client = createModuleMenuClient(props.context.module.http);
const alias = String(props.context.record?.alias ?? props.context.record?.id ?? '');
const entryContext = useModuleContext({ moduleAlias: alias });
const entryIssue = ref('');
const entryReady = ref(false);
const title = ref(String(props.context.record?.title ?? alias));
const schemes = ref<MenuScheme[]>([]);
const schemeId = ref('');
const parentId = ref('root');
const openMode = ref<MenuOpenMode>('tab');
const tree = ref<MenuTreeNode[]>([]);
const loading = ref(true);
const treeReady = ref(false);
const saving = ref(false);
const error = ref('');
const saved = ref<MenuRecord>();
const savedPath = ref('');
const visibleMenu = ref<MenuRecord>();
const visibilityChecked = ref(false);
let revision = 0;
onBeforeUnmount(() => revision++);

const directories = computed(() => [
  { value: 'root', title: '顶层', children: menuDirectoryOptions(tree.value) },
]);
const options = computed(() =>
  schemes.value
    .filter((scheme) => scheme.enabled !== false)
    .map((scheme) => {
      const title = scheme.title ?? scheme.alias ?? scheme.id!;
      const duplicateTitle = schemes.value.some(
        (other) => other.id !== scheme.id && other.title === scheme.title,
      );
      const owner = scheme.organizationId ?? scheme.tenantId ?? '系统';
      return { value: scheme.id!, label: duplicateTitle ? `${title} · ${owner}` : title };
    }),
);
const duplicate = computed(() =>
  menuPlacements(tree.value).find(
    ({ menu }) => menu.moduleAlias === alias && menu.parentId === parentId.value,
  ),
);
const canSave = computed(
  () =>
    !loading.value &&
    !saving.value &&
    !saved.value &&
    Boolean(alias && schemeId.value && title.value.trim()) &&
    treeReady.value &&
    entryReady.value,
);

watch(
  [canSave, saving, saved],
  () => {
    props.context.setCloseBlocked(saving.value);
    props.context.setTitleActions(
      saved.value
        ? []
        : [
            {
              key: 'add-menu',
              label: '添加',
              emphasis: 'primary',
              disabled: !canSave.value,
              loading: saving.value,
              run: save,
            },
          ],
    );
  },
  { immediate: true },
);

watch(schemeId, () => void loadTree());
void load();

async function load() {
  entryReady.value = false;
  treeReady.value = false;
  loading.value = true;
  error.value = '';
  const current = ++revision;
  try {
    const [result, entry] = await Promise.all([client.schemes(), entryContext.runtime.load()]);
    if (current !== revision) return;
    openMode.value = entry.entryType === 'link' ? 'window' : 'tab';
    entryIssue.value = entryProblem(entry);
    entryReady.value = !entryIssue.value;
    schemes.value = result;
    if (!options.value.length) {
      error.value = '暂无可用菜单方案，请先在菜单管理中配置方案。';
      loading.value = false;
      return;
    }
    // Prefer the user's effective scheme when it has a visible menu; an empty
    // scheme is still offered and a single scheme never needs another choice.
    const visible = await client.visible();
    if (current !== revision) return;
    const preferred = visible[0]?.record.schemeId;
    const selected = options.value.some((option) => option.value === preferred)
      ? preferred!
      : String(options.value[0]!.value);
    if (schemeId.value === selected) await loadTree();
    else schemeId.value = selected;
  } catch (cause) {
    if (current === revision) {
      error.value = messageOf(cause);
      loading.value = false;
    }
  }
}

async function loadTree() {
  const current = ++revision;
  parentId.value = 'root';
  tree.value = [];
  treeReady.value = false;
  error.value = '';
  loading.value = true;
  try {
    const result = await client.tree(schemeId.value);
    if (current === revision) {
      tree.value = result;
      treeReady.value = true;
    }
  } catch (cause) {
    if (current === revision) error.value = messageOf(cause);
  } finally {
    if (current === revision) loading.value = false;
  }
}

async function refreshVisibility() {
  visibilityChecked.value = false;
  error.value = '';
  try {
    const nodes = await (navigation?.refreshMenus?.() ?? client.visible());
    visibleMenu.value = menuPlacements(nodes).find(({ menu }) => menu.id === saved.value?.id)?.menu;
    visibilityChecked.value = true;
  } catch {
    error.value = '菜单已添加，导航刷新失败。请重试刷新，无需重复添加。';
  }
}

async function save() {
  if (!canSave.value) return;
  saving.value = true;
  error.value = '';
  try {
    const result = await client.insert(schemeId.value, {
      title: title.value.trim(),
      moduleAlias: alias,
      schemeId: schemeId.value,
      parentId: parentId.value,
      openMode: openMode.value,
      enabled: true,
    } as MenuRecord);
    saved.value = result.record;
    const parent = menuPlacements(tree.value).find(({ menu }) => menu.id === parentId.value)?.path;
    const scheme = schemes.value.find((item) => item.id === schemeId.value);
    savedPath.value = [scheme?.title, parent, title.value.trim()].filter(Boolean).join(' / ');
    props.context.refreshDetailExtensions();
    await refreshVisibility();
  } catch (cause) {
    error.value = messageOf(cause);
  } finally {
    saving.value = false;
  }
}

function entryProblem(entry: ModuleRuntimeContext) {
  if (entry.entryType === 'route') return entry.entryRoute ? '' : '该模块尚未配置页面地址，请返回模块补充。';
  if (entry.entryType === 'link')
    return entry.entryExternalUrl ? '' : '该模块尚未配置链接地址，请返回模块补充。';
  return entry.uiDescriptor?.page
    ? ''
    : '该模块尚无可用业务页面，请返回模块完成页面配置；使用草稿配置的页面需先发布。';
}

function messageOf(cause: unknown) {
  return cause instanceof Error ? cause.message : '加载失败，请重试。';
}

function open() {
  if (visibleMenu.value && navigation?.openMenu) {
    navigation.openMenu(visibleMenu.value);
    props.context.close();
  }
}
</script>

<template>
  <div class="module-menu-form">
    <template v-if="!saved">
      <label>菜单名称<UiInput v-model:value="title" :disabled="saving" aria-label="菜单名称" /></label>
      <label v-if="options.length > 1"
        >菜单方案
        <UiSelect
          :value="schemeId"
          :options="options"
          :allow-clear="false"
          :disabled="saving"
          @update:value="schemeId = String($event ?? '')"
        />
      </label>
      <label
        >放置位置
        <UiTreeSelect
          :value="parentId"
          :tree-data="directories"
          :allow-clear="false"
          :disabled="loading || saving"
          :loading="loading"
          @update:value="parentId = String($event ?? 'root')"
        />
      </label>
      <details>
        <summary>更多设置</summary>
        <label
          >打开方式
          <UiSelect
            :value="openMode"
            :allow-clear="false"
            :disabled="saving"
            :options="[
              { value: 'tab', label: '页签内打开' },
              { value: 'window', label: '新窗口打开' },
            ]"
            @update:value="openMode = $event === 'window' ? 'window' : 'tab'"
          />
        </label>
      </details>
      <p v-if="duplicate" class="hint">此位置已有「{{ duplicate.menu.title }}」，仍可添加另一个入口。</p>
    </template>
    <template v-else>
      <strong>已添加到菜单</strong>
      <p>{{ savedPath }}</p>
      <UiButton v-if="visibleMenu && navigation?.openMenu" type="primary" @click="open">打开</UiButton>
      <p v-else-if="visibilityChecked" class="hint">
        当前用户的导航中暂不可见，请检查菜单方案适用范围、上级菜单状态及模块访问权限。
      </p>
      <UiButton v-if="error" :disabled="saving" @click="refreshVisibility">刷新导航</UiButton>
    </template>
    <p v-if="entryIssue" role="status">{{ entryIssue }}</p>
    <UiButton v-if="entryIssue" @click="context.close()">返回模块</UiButton>
    <p v-if="error" role="alert">{{ error }}</p>
    <UiButton v-if="error && !saved && !treeReady" :disabled="saving" @click="load">重试</UiButton>
  </div>
</template>

<style scoped>
.module-menu-form {
  display: grid;
  gap: 20px;
}
summary {
  cursor: pointer;
  color: var(--muyun-text-muted);
}
details[open] summary {
  margin-bottom: 12px;
}
label {
  display: grid;
  gap: 8px;
}
p {
  margin: 0;
  overflow-wrap: anywhere;
}
.hint {
  color: var(--muyun-text-muted);
  font-size: 13px;
}
[role='alert'] {
  color: var(--muyun-color-danger, #b42318);
}
</style>
