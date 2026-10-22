<script setup lang="ts">
import { computed, shallowRef } from 'vue';
import {
  ReadonlyRecordWorkspace,
  UiButton,
  createMenuTab,
  getMenuNavigationTarget,
  resolveRecordReadEntry,
  usePageDescriptor,
  useWorkbenchNavigation,
  type MenuRecord,
  type PageDescriptor,
  type RecordReadQuery,
  type RecordReadView,
  type ResolvedModuleUiDescriptor,
} from '@ximatai/muyun-web-app';

import { deviceUseQuerySchema, queryDeviceUseFixture } from '../fixtures/readonlyDeviceUse';

const props = withDefaults(
  defineProps<{
    /** Direct-link selection, e.g. the page-instance route's `view` query value. */
    directViewCode?: string;
    /** The host changes this on principal, tenant or authorization revision changes. */
    scopeKey?: string;
  }>(),
  { directViewCode: undefined, scopeKey: 'local-fixture' },
);
const moduleAlias = 'example.device_use';
const navigation = useWorkbenchNavigation();
const page = usePageDescriptor();
const localPage = shallowRef<PageDescriptor>();
const definitions = {
  current: { title: '当前领用', status: 'CURRENT', summary: false },
  history: { title: '交接记录', status: 'RETURNED', summary: true },
};
const menus = Object.entries(definitions).map(
  ([view, definition]) =>
    ({
      id: `example.device-use.${view}`,
      title: definition.title,
      moduleAlias,
      entryType: 'module',
      openMode: 'tab',
      entryParamsJson: JSON.stringify({ view }),
      defaultUiConfigId: `${view}-ui`,
      defaultQueryTemplateId: `${view}-query`,
    }) as MenuRecord,
);
function open(menu: MenuRecord) {
  if (navigation?.openMenu) navigation.openMenu(menu);
  else localPage.value = createMenuTab(menu, getMenuNavigationTarget(menu)!).pageDescriptor;
}
const selection = computed(() => {
  try {
    return {
      entry: resolveRecordReadEntry({
        moduleAlias,
        viewCodes: Object.keys(definitions),
        page: page.value ?? localPage.value,
        directViewCode: props.directViewCode,
        defaultViewCode: 'current',
      }),
      error: undefined,
    };
  } catch (error) {
    return { entry: undefined, error: error instanceof Error ? error.message : String(error) };
  }
});
const definition = computed(() => definitions[selection.value.entry!.viewCode as keyof typeof definitions]);
const query = computed<RecordReadQuery>(() => ({
  // A user-adjustable initial filter. A real server always applies tenant/data permissions and
  // mandatory domain scope independently, including when the client removes this condition.
  conditions: [{ fieldName: 'status', operator: 'EQ', values: [definition.value.status] }],
  uiConfigId: selection.value.entry!.defaultUiConfigId,
  queryTemplateId: selection.value.entry!.defaultQueryTemplateId,
}));
const view = computed<RecordReadView>(() => {
  const entry = selection.value.entry!;
  const config = definition.value;
  const fields = [
    { fieldRef: { fieldName: 'title' }, label: '设备事项', width: '220px', valueType: 'STRING' },
    { fieldRef: { fieldName: 'status' }, label: '领用状态', valueType: 'STRING' },
    ...(config.summary
      ? [{ fieldRef: { fieldName: 'summary' }, label: '交接摘要', valueType: 'STRING' }]
      : []),
  ];
  const descriptor = {
    page: {
      list: { searchPlaceholder: '搜索设备事项', fields: { title: config.title, fields } },
      detail: { display: { fields } },
    },
  } as unknown as ResolvedModuleUiDescriptor;
  return {
    sourceId: moduleAlias,
    viewCode: entry.viewCode,
    scopeKey: props.scopeKey,
    descriptor,
    recordKey: (record) => `${record.origin}:${record.id}`,
    // Deterministic UI fixture only. Production query/detail use the authenticated domain service;
    // attach entry.menuId via withHttpHeaders(..., { 'X-MuYun-Menu-Id': entry.menuId }). The server
    // re-resolves the visible menu through MenuEntryRequestContext and enforces entry-owned
    // restrictions with ModulePageEntryPolicy, independently of browser defaults or view names.
    querySchema: deviceUseQuerySchema,
    query: async (request) => queryDeviceUseFixture(request),
    detail: { title: (record) => String(record.title), load: async (record) => ({ ...record }) },
  };
});
</script>

<template>
  <section class="readonly-business-example">
    <h2>同模块具名只读入口（本地样例）</h2>
    <p>本地数据演示入口与展示契约；真实授权由业务服务端执行。</p>
    <UiButton v-for="menu in menus" :key="menu.id" @click="open(menu)">{{ menu.title }}</UiButton>
    <p v-if="selection.error" role="alert">{{ selection.error }}</p>
    <div v-else class="readonly-business-example__workspace">
      <ReadonlyRecordWorkspace :view="view" :query="query" />
    </div>
  </section>
</template>

<style scoped>
.readonly-business-example__workspace {
  height: 560px;
  margin-top: 12px;
}
</style>
