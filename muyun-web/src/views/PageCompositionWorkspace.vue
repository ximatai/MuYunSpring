<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  ManagementExplorerColumn,
  ManagementWorkspace,
  RecordDetailPanel,
  RecordExplorerPanel,
  presentPlatformError,
} from '@muyun/platform-components';
import { useModuleContext } from '@muyun/web-core';
import {
  UiButton,
  UiEmpty,
  UiInput,
  UiSidePanel,
  UiSpin,
  UiTabs,
  type UiTabItem,
} from '@muyun/vue-ui-antdv';
import type { MetadataField, ModuleMetadataRelation, WebPageResponse } from '@muyun/web-contracts';
import {
  createPageCompositionDraftState,
  type PageComposerField,
  type PageComposerSlot,
} from './pageCompositionDraftState';

defineOptions({ name: 'PageCompositionWorkspace' });

const props = defineProps<{ moduleAlias: string; moduleTitle?: string }>();
const moduleContext = useModuleContext({ moduleAlias: 'platform.module' });
const state = createPageCompositionDraftState();
const loading = ref(false);
const relation = ref<ModuleMetadataRelation>();
const metadataFields = ref<PageComposerField[]>([]);
const selectedSlot = ref<PageComposerSlot>('list');
const editorOpen = ref(false);
const componentTitle = ref('');
const fieldKeyword = ref('');

const previewTabs: UiTabItem[] = [
  { key: 'list', title: '列表预览' },
  { key: 'detail', title: '详情预览' },
];
const visibleFields = computed(() => {
  const keyword = fieldKeyword.value.trim().toLowerCase();
  if (!keyword) return metadataFields.value;
  return metadataFields.value.filter(
    (field) => field.title.toLowerCase().includes(keyword) || field.fieldName.toLowerCase().includes(keyword),
  );
});
const selectedField = computed(() => state.selectedNode.value?.field);
const composerTitle = computed(() => `${props.moduleTitle ?? props.moduleAlias} · Web 管理页`);
const mainEntityTitle = computed(() => relation.value?.relationAlias ?? '主实体');

watch(
  () => props.moduleAlias,
  () => void loadMetadataTree(),
  { immediate: true },
);

async function loadMetadataTree() {
  loading.value = true;
  relation.value = undefined;
  metadataFields.value = [];
  try {
    const relations = await loadAll<ModuleMetadataRelation>(
      `/platform.module/${encodeURIComponent(props.moduleAlias)}/metadata-relations/query`,
    );
    const main = relations.find((item) => item.relationRole === 'main' || item.relationRole === 'MAIN');
    relation.value = main;
    if (!main?.metadataId) return;
    const fields = await loadAll<MetadataField>(`/platform.metadata/${encodeURIComponent(main.metadataId)}/fields/query`);
    metadataFields.value = fields
      .filter((field) => field.enabled !== false)
      .map(toComposerField)
      .filter((field): field is PageComposerField => field != null);
  } catch (cause) {
    presentPlatformError(cause, { source: 'page-composition', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

async function loadAll<T>(path: string): Promise<T[]> {
  const records: T[] = [];
  for (let pageNum = 1; ; pageNum += 1) {
    const response = await moduleContext.http.request<WebPageResponse<T>>({
      method: 'POST',
      path,
      body: { page: { pageNum, pageSize: 200 } },
    });
    records.push(...response.records);
    if (response.totalKnown ? pageNum >= response.pages : response.records.length < 200) return records;
  }
}

function toComposerField(field: MetadataField): PageComposerField | undefined {
  if (!field.id || !field.fieldName) return undefined;
  return {
    id: field.id,
    title: field.title ?? field.fieldName,
    fieldName: field.fieldName,
    fieldSpecAlias: field.fieldSpecAlias,
    required: field.required,
  };
}

function addToSelectedSlot(field: PageComposerField) {
  state.addField(field, selectedSlot.value);
}

function selectNode(node: (typeof state.nodes.value)[number]) {
  state.selectNode(node);
  selectedSlot.value = node.slot;
}

function openComponentEditor() {
  const selected = state.selectedNode.value;
  componentTitle.value = selected?.field?.title ?? selected?.title ?? '';
  editorOpen.value = true;
}

function saveComponentEditor() {
  // Component option persistence is intentionally local until Revision Draft APIs are exposed.
  editorOpen.value = false;
}

function dropField(event: DragEvent, slot: PageComposerSlot) {
  const fieldId = event.dataTransfer?.getData('text/page-composer-field');
  const field = metadataFields.value.find((candidate) => candidate.id === fieldId);
  if (field) state.addField(field, slot);
}
</script>

<template>
  <ManagementWorkspace class="page-composition-workspace" :explorer-count="2" detail-surface>
    <ManagementExplorerColumn>
      <RecordExplorerPanel
        v-model:search-keyword="fieldKeyword"
        title="元数据"
        :subtitle="mainEntityTitle"
        search-placeholder="搜索字段"
        @refresh="loadMetadataTree"
      >
        <UiSpin v-if="loading" tip="加载主实体字段" />
        <UiEmpty v-else-if="!relation" description="动态模块尚无主实体，请先完成数据模型编排" />
        <div v-else class="metadata-tree" data-testid="page-composer-metadata-tree">
          <div class="metadata-tree__root">{{ mainEntityTitle }}</div>
          <p class="metadata-tree__hint">拖入右侧模板槽位；引用字段的递归展开将在关联治理接入后提供。</p>
          <button
            v-for="field in visibleFields"
            :key="field.id"
            class="metadata-tree__field"
            draggable="true"
            type="button"
            @dragstart="$event.dataTransfer?.setData('text/page-composer-field', field.id)"
            @dblclick="addToSelectedSlot(field)"
          >
            <span>{{ field.title }}</span><small>{{ field.fieldName }}</small>
          </button>
          <UiEmpty v-if="!visibleFields.length" description="暂无可编排字段" />
        </div>
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <ManagementExplorerColumn>
      <RecordExplorerPanel title="UI Tree" subtitle="management v1" :searchable="false">
        <template #actions>
          <UiButton size="small" type="text" title="配置选中组件" @click="openComponentEditor">配置</UiButton>
        </template>
        <div class="ui-tree" data-testid="page-composer-ui-tree">
          <section v-for="slot in (['list', 'form'] as PageComposerSlot[])" :key="slot" class="ui-tree__slot">
            <button
              class="ui-tree__slot-title"
              :class="{ 'is-selected': state.selectedNodeId.value === `slot:${slot}` }"
              type="button"
              @click="selectNode({ id: `slot:${slot}`, kind: 'slot', title: slot === 'list' ? '列表' : '详情 / 表单', slot })"
              @dragover.prevent
              @drop="dropField($event, slot)"
            >
              {{ slot === 'list' ? '列表' : '详情 / 表单' }}
              <small>拖入字段</small>
            </button>
            <button
              v-for="field in slot === 'list' ? state.listFields.value : state.formFields.value"
              :key="`${slot}:${field.id}`"
              class="ui-tree__field"
              :class="{ 'is-selected': state.selectedNodeId.value === `${slot}:${field.id}` }"
              type="button"
              @click="selectNode({ id: `${slot}:${field.id}`, kind: 'field', title: field.title, slot, field })"
              @dblclick="openComponentEditor"
            >
              <span>{{ field.title }}</span><small>{{ field.fieldName }}</small>
            </button>
          </section>
        </div>
        <template #footer>
          <div class="ui-tree__operations">
            <UiButton size="small" :disabled="!selectedField" @click="state.moveSelectedField(-1)">上移</UiButton>
            <UiButton size="small" :disabled="!selectedField" @click="state.moveSelectedField(1)">下移</UiButton>
            <UiButton size="small" danger :disabled="!selectedField" @click="state.removeSelectedField">移除</UiButton>
          </div>
        </template>
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <RecordDetailPanel :title="composerTitle" subtitle="本地草稿 · 发布接口接入中">
      <template #actions>
        <UiButton type="primary" disabled title="等待页面修订发布接口">保存草稿</UiButton>
      </template>
      <p class="page-composition-notice">当前可完成模板选槽、字段编排与预览；草稿保存、发布确认会接入新的页面修订 API，不会调用旧 UI 配置集接口。</p>
      <UiTabs v-model:active-key="state.previewMode.value" :tabs="previewTabs" />
      <section v-if="state.previewMode.value === 'list'" class="preview-surface" data-testid="page-composer-list-preview">
        <header class="preview-surface__toolbar"><span>快速查询</span><UiButton size="small">查询</UiButton></header>
        <div class="preview-table">
          <div v-if="!state.listFields.value.length" class="preview-empty">从元数据拖入字段，开始配置列表</div>
          <template v-else>
            <div class="preview-table__header">
              <span v-for="field in state.listFields.value" :key="field.id">{{ field.title }}</span>
            </div>
            <div class="preview-table__row">
              <span v-for="field in state.listFields.value" :key="field.id">{{ field.fieldSpecAlias ?? '文本' }}</span>
            </div>
          </template>
        </div>
      </section>
      <section v-else class="preview-surface" data-testid="page-composer-detail-preview">
        <div v-if="!state.formFields.value.length" class="preview-empty">从元数据拖入字段，开始配置详情 / 表单</div>
        <dl v-else class="preview-form">
          <template v-for="field in state.formFields.value" :key="field.id">
            <dt>{{ field.title }}<em v-if="field.required">*</em></dt><dd>{{ field.fieldSpecAlias ?? '输入控件' }}</dd>
          </template>
        </dl>
      </section>
    </RecordDetailPanel>
  </ManagementWorkspace>

  <UiSidePanel :open="editorOpen" width="420" @close="editorOpen = false">
    <section class="component-drawer" data-testid="page-composer-component-drawer">
      <header><h2>组件配置</h2><UiButton type="text" icon-name="close" icon-only title="关闭" @click="editorOpen = false" /></header>
      <p>配置会随页面修订草稿保存；当前为本地预览。</p>
      <label><span>显示标题</span><UiInput v-model:value="componentTitle" /></label>
      <div class="component-drawer__actions"><UiButton @click="editorOpen = false">取消</UiButton><UiButton type="primary" @click="saveComponentEditor">应用</UiButton></div>
    </section>
  </UiSidePanel>
</template>

<style scoped>
.page-composition-workspace { min-height: 0; height: 100%; }
.metadata-tree, .ui-tree { display: grid; gap: 4px; min-height: 0; overflow: auto; }
.metadata-tree__root { font-weight: 650; padding: 4px 8px; }
.metadata-tree__hint, .page-composition-notice { margin: 0; color: var(--muyun-text-muted); font-size: 13px; line-height: 1.55; }
.metadata-tree__field, .ui-tree__field, .ui-tree__slot-title { display: flex; align-items: baseline; justify-content: space-between; width: 100%; border: 0; border-radius: 4px; background: transparent; padding: 7px 8px; color: var(--muyun-text); text-align: left; cursor: pointer; }
.metadata-tree__field { padding-left: 20px; }
.metadata-tree__field:hover, .ui-tree__field:hover, .ui-tree__slot-title:hover, .is-selected { background: var(--muyun-hover); }
.metadata-tree small, .ui-tree small { color: var(--muyun-text-muted); }
.ui-tree__slot { display: grid; gap: 2px; margin-bottom: 8px; }
.ui-tree__slot-title { font-weight: 650; border-bottom: 1px solid var(--muyun-border-subtle); }
.ui-tree__field { padding-left: 20px; }
.ui-tree__operations, .component-drawer__actions { display: flex; gap: 8px; justify-content: flex-end; }
.page-composition-notice { margin-bottom: 12px; }
.preview-surface { margin-top: 12px; border: 1px solid var(--muyun-border); border-radius: 8px; min-height: 280px; padding: 16px; }
.preview-surface__toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; }
.preview-table { border: 1px solid var(--muyun-border-subtle); border-radius: 6px; overflow: hidden; }
.preview-table__header, .preview-table__row { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); }
.preview-table__header { background: var(--muyun-surface-muted); font-weight: 600; }
.preview-table span { padding: 10px; border-right: 1px solid var(--muyun-border-subtle); }
.preview-empty { display: grid; min-height: 180px; place-items: center; color: var(--muyun-text-muted); }
.preview-form { display: grid; grid-template-columns: 140px 1fr; gap: 12px 16px; align-items: center; max-width: 660px; }
.preview-form dt { color: var(--muyun-text-muted); }.preview-form dd { margin: 0; padding: 8px 10px; border: 1px solid var(--muyun-border); border-radius: 4px; }.preview-form em { color: var(--muyun-danger); margin-left: 4px; }
.component-drawer { display: grid; align-content: start; gap: 18px; height: 100%; padding: 20px; background: var(--muyun-surface); }.component-drawer header { display: flex; align-items: center; justify-content: space-between; }.component-drawer h2, .component-drawer p { margin: 0; }.component-drawer p { color: var(--muyun-text-muted); }.component-drawer label { display: grid; gap: 6px; }.component-drawer__actions { margin-top: auto; }
</style>
