<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  ManagementExplorerColumn,
  ManagementWorkspace,
  RecordDetailPanel,
  RecordExplorerPanel,
  presentPlatformError,
} from '@muyun/platform-components';
import { createStaticResourceCrudClient, useModuleContext, type ModuleCrudClient } from '@muyun/web-core';
import {
  confirmAction,
  UiButton,
  UiEmpty,
  UiInput,
  UiSidePanel,
  UiSpin,
  UiTabs,
  UiTree,
  type UiTabItem,
  type UiTreeDragEvent,
  type UiTreeDropEvent,
  type UiTreeExternalDropEvent,
  type UiTreeNode,
} from '@muyun/vue-ui-antdv';
import type { MetadataField, ModuleMetadataRelation, WebPageResponse, WebQueryCondition } from '@muyun/web-contracts';
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
const saving = ref(false);
const publishing = ref(false);
const relation = ref<ModuleMetadataRelation>();
const metadataFields = ref<PageComposerField[]>([]);
const page = ref<PageDefinition>();
const variant = ref<PresentationVariant>();
const revision = ref<PresentationRevision>();
const selectedSlot = ref<PageComposerSlot>('list');
const editorOpen = ref(false);
const componentTitle = ref('');
const fieldKeyword = ref('');
const selectedMetadataTreeKey = ref<string>();
const metadataExpandedKeys = ref<string[]>(['metadata:root']);
const uiExpandedKeys = ref<string[]>(['ui:root', 'ui:slot:list', 'ui:slot:form']);

const previewTabs: UiTabItem[] = [
  { key: 'list', title: '列表预览' },
  { key: 'card', title: '卡片预览' },
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
const selectedUiTreeKey = computed(() => {
  const node = state.selectedNode.value;
  if (!node) return undefined;
  return node.kind === 'slot' ? `ui:slot:${node.slot}` : `ui:field:${node.slot}:${node.field?.id}`;
});
const composerTitle = computed(() => `${props.moduleTitle ?? props.moduleAlias} · Web 管理页`);
const mainEntityTitle = computed(() => relation.value?.relationAlias ?? '主实体');
const compositionSubtitle = computed(() => {
  if (!page.value) return '尚未初始化页面定义';
  if (!revision.value) return '尚无可编辑草稿';
  return `草稿 v${revision.value.revisionNo} · management v1`;
});
const metadataTreeNodes = computed<UiTreeNode[]>(() => [
  {
    key: 'metadata:root',
    title: mainEntityTitle.value,
    secondary: '主元数据',
    children: visibleFields.value.map((field) => ({
      key: `metadata:field:${field.id}`,
      title: field.title,
      secondary: field.fieldName,
      isLeaf: true,
    })),
  },
]);
const uiTreeNodes = computed<UiTreeNode[]>(() => [
  {
    key: 'ui:root',
    title: '管理页模板',
    secondary: 'management v1',
    children: (['list', 'form'] as PageComposerSlot[]).map((slot) => ({
      key: `ui:slot:${slot}`,
      title: slotTitle(slot),
      secondary: slot === 'list' ? '列表展示字段' : '详情 / 表单字段',
      children: fieldsInSlot(slot).map((field) => ({
        key: `ui:field:${slot}:${field.id}`,
        title: field.title,
        secondary: field.fieldName,
        isLeaf: true,
      })),
    })),
  },
]);

watch(
  () => props.moduleAlias,
  () => void loadWorkspace(),
  { immediate: true },
);

type PageDefinition = {
  id?: string;
  version?: number;
  title?: string;
  alias?: string;
  moduleAlias?: string;
  contractType?: 'management' | 'form' | 'detail' | 'reference';
  mainRelationId?: string;
  enabled?: boolean;
};
type PresentationVariant = {
  id?: string;
  version?: number;
  title?: string;
  pageId?: string;
  clientType?: 'web' | 'mobile';
  scopeType?: 'global' | 'tenant' | 'organization';
  enabled?: boolean;
};
type PresentationRevision = {
  id?: string;
  version?: number;
  title?: string;
  variantId?: string;
  revisionNo?: number;
  templateAlias?: string;
  templateVersion?: number;
  uiTreeJson?: string;
  status?: 'draft' | 'published' | 'archived';
  enabled?: boolean;
};

function pageClient() {
  return createStaticResourceCrudClient<PageDefinition>(
    moduleContext.http,
    `/platform.module/${encodeURIComponent(props.moduleAlias)}/pages`,
  );
}

function variantClient(pageId: string) {
  return createStaticResourceCrudClient<PresentationVariant>(
    moduleContext.http,
    `/platform.module/${encodeURIComponent(props.moduleAlias)}/pages/${encodeURIComponent(pageId)}/presentation-variants`,
  );
}

function revisionClient(variantId: string) {
  return createStaticResourceCrudClient<PresentationRevision>(
    moduleContext.http,
    `/platform.presentation-variant/${encodeURIComponent(variantId)}/revisions`,
  );
}

async function loadWorkspace() {
  await loadMetadataTree();
  await loadComposition();
}

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

async function loadComposition() {
  page.value = undefined;
  variant.value = undefined;
  revision.value = undefined;
  state.replaceFields({ list: [], form: [] });
  try {
    const pages = await loadAllFromClient(pageClient(), [{ fieldName: 'alias', operator: 'EQ', values: ['management'] }]);
    page.value = pages[0];
    if (!page.value?.id) return;
    const variants = await loadAllFromClient(variantClient(page.value.id), [
      { fieldName: 'clientType', operator: 'EQ', values: ['web'] },
      { fieldName: 'scopeType', operator: 'EQ', values: ['global'] },
    ]);
    variant.value = variants[0];
    if (!variant.value?.id) return;
    const revisions = await loadAllFromClient(revisionClient(variant.value.id), [
      { fieldName: 'status', operator: 'EQ', values: ['draft'] },
    ]);
    revision.value = revisions.sort((left, right) => (right.revisionNo ?? 0) - (left.revisionNo ?? 0))[0];
    hydrateDraft(revision.value);
  } catch (cause) {
    presentPlatformError(cause, { source: 'page-composition', phase: 'load' });
  }
}

async function loadAllFromClient<T>(client: ModuleCrudClient<T>, conditions: WebQueryCondition[] = []): Promise<T[]> {
  const response = await client.query({ unpaged: true, conditions });
  return response.records;
}

function hydrateDraft(current: PresentationRevision | undefined) {
  if (!current?.uiTreeJson) return;
  try {
    const tree = JSON.parse(current.uiTreeJson) as { nodes?: Array<{ slot?: PageComposerSlot; fields?: string[] }> };
    const resolve = (slot: PageComposerSlot) => tree.nodes?.find((node) => node.slot === slot)?.fields ?? [];
    const fieldsByName = new Map(metadataFields.value.map((field) => [field.fieldName, field]));
    state.replaceFields({
      list: resolve('list').map((name) => fieldsByName.get(name)).filter((field): field is PageComposerField => Boolean(field)),
      form: resolve('form').map((name) => fieldsByName.get(name)).filter((field): field is PageComposerField => Boolean(field)),
    });
  } catch {
    // Publication validates the persisted tree. A malformed draft should remain editable as an empty local tree.
  }
}

async function initializeComposition() {
  if (!relation.value?.id) return;
  saving.value = true;
  try {
    if (!page.value) {
      page.value = (await pageClient().insert({
        alias: 'management', contractType: 'management', mainRelationId: relation.value.id,
        title: `${props.moduleTitle ?? props.moduleAlias}管理页`, enabled: true,
      })).record;
    }
    if (!page.value.id) return;
    if (!variant.value) {
      variant.value = (await variantClient(page.value.id).insert({
        clientType: 'web', scopeType: 'global', title: 'Web 全局呈现', enabled: true,
      })).record;
    }
    if (!variant.value.id || revision.value) return;
    const revisions = await loadAllFromClient(revisionClient(variant.value.id));
    revision.value = (await revisionClient(variant.value.id).insert({
      revisionNo: Math.max(0, ...revisions.map((item) => item.revisionNo ?? 0)) + 1,
      templateAlias: 'management', templateVersion: 1,
      uiTreeJson: JSON.stringify(state.toManagementUiTree()),
      status: 'draft', title: '初始草稿', enabled: true,
    })).record;
  } catch (cause) {
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function saveDraft() {
  if (!revision.value?.id || !variant.value?.id) return;
  saving.value = true;
  try {
    revision.value = (await revisionClient(variant.value.id).update(revision.value.id, {
      ...revision.value,
      uiTreeJson: JSON.stringify(state.toManagementUiTree()),
    })).record;
  } catch (cause) {
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function publishDraft() {
  if (!revision.value?.id) return;
  const confirmed = await confirmAction({
    title: '发布页面修订',
    content: '发布将校验 UI Tree，并将该修订确认为当前 Web 全局呈现。是否继续？',
    okText: '确认发布',
  });
  if (!confirmed) return;
  publishing.value = true;
  try {
    const publishedTreeJson = JSON.stringify(state.toManagementUiTree());
    const publishedRevision = revision.value;
    await saveDraft();
    await moduleContext.http.request<number>({
      method: 'POST', path: `/platform.presentation_publish/revisions/${encodeURIComponent(revision.value.id)}/publish`,
    });
    await createFollowUpDraft(publishedRevision, publishedTreeJson);
    await loadComposition();
  } catch (cause) {
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
  } finally {
    publishing.value = false;
  }
}

/** Keeps a stable editable working copy after an immutable revision becomes published. */
async function createFollowUpDraft(publishedRevision: PresentationRevision, uiTreeJson: string) {
  if (!variant.value?.id) return;
  const revisions = await loadAllFromClient(revisionClient(variant.value.id));
  await revisionClient(variant.value.id).insert({
    revisionNo: Math.max(0, ...revisions.map((item) => item.revisionNo ?? 0)) + 1,
    templateAlias: publishedRevision.templateAlias ?? 'management',
    templateVersion: publishedRevision.templateVersion ?? 1,
    uiTreeJson,
    status: 'draft',
    title: `基于 v${publishedRevision.revisionNo ?? 1} 的草稿`,
    enabled: true,
  });
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

function slotTitle(slot: PageComposerSlot) {
  return slot === 'list' ? '列表' : '详情 / 表单';
}

function fieldsInSlot(slot: PageComposerSlot) {
  return slot === 'list' ? state.listFields.value : state.formFields.value;
}

function selectMetadataNode(node: UiTreeNode) {
  selectedMetadataTreeKey.value = node.key;
}

function handleMetadataDoubleClick(event: UiTreeDragEvent) {
  const field = fieldOfMetadataNode(event.node);
  if (field) addToSelectedSlot(field);
}

function selectUiTreeNode(node: UiTreeNode) {
  const parsed = parseUiNode(node.key);
  if (!parsed) return;
  if (parsed.kind === 'slot') {
    selectNode({ id: `slot:${parsed.slot}`, kind: 'slot', title: slotTitle(parsed.slot), slot: parsed.slot });
    return;
  }
  if (parsed.kind !== 'field') return;
  const field = fieldsInSlot(parsed.slot).find((candidate) => candidate.id === parsed.fieldId);
  if (field) selectNode({ id: `${parsed.slot}:${field.id}`, kind: 'field', title: field.title, slot: parsed.slot, field });
}

function handleMetadataDragStart(event: UiTreeDragEvent) {
  const field = fieldOfMetadataNode(event.node);
  const dataTransfer = (event.nativeEvent as DragEvent | undefined)?.dataTransfer;
  if (!field || !dataTransfer) return;
  dataTransfer.effectAllowed = 'copy';
  dataTransfer.setData('text/page-composer-field', field.id);
}

function canDragMetadataNode(node: UiTreeNode) {
  return fieldOfMetadataNode(node) != null;
}

function handleUiTreeDragStart(event: UiTreeDragEvent) {
  const parsed = parseUiNode(event.node.key);
  const dataTransfer = (event.nativeEvent as DragEvent | undefined)?.dataTransfer;
  if (!parsed || parsed.kind !== 'field' || !dataTransfer) return;
  dataTransfer.effectAllowed = 'move';
  dataTransfer.setData('text/page-composer-ui-field', JSON.stringify(parsed));
}

function handleUiTreeDoubleClick(event: UiTreeDragEvent) {
  selectUiTreeNode(event.node);
  if (parseUiNode(event.node.key)?.kind === 'field') openComponentEditor();
}

function handleUiTreeDrop(event: Pick<UiTreeDropEvent, 'dropNode' | 'dropPosition' | 'dropToGap' | 'nativeEvent'>) {
  const target = parseUiNode(event.dropNode.key);
  if (!target || target.kind === 'root') return;
  const dataTransfer = (event.nativeEvent as DragEvent | undefined)?.dataTransfer;
  const fieldId = dataTransfer?.getData('text/page-composer-field');
  if (fieldId) {
    const field = metadataFields.value.find((candidate) => candidate.id === fieldId);
    const targetIndex = target.kind === 'field'
      ? fieldsInSlot(target.slot).findIndex((candidate) => candidate.id === target.fieldId)
        + (event.dropPosition > 0 ? 1 : 0)
      : undefined;
    if (field) state.addField(field, target.slot, targetIndex);
    return;
  }
  const raw = dataTransfer?.getData('text/page-composer-ui-field');
  if (!raw) return;
  try {
    const source = JSON.parse(raw) as { kind?: string; slot?: PageComposerSlot; fieldId?: string };
    if (source.kind !== 'field' || !source.slot || !source.fieldId) return;
    const targetIndex = target.kind === 'field'
      ? fieldsInSlot(target.slot).findIndex((field) => field.id === target.fieldId) + (event.dropPosition > 0 ? 1 : 0)
      : undefined;
    state.moveField(source.fieldId, source.slot, target.slot, targetIndex);
  } catch {
    // Ignore payloads not owned by the page composer.
  }
}

function handleUiTreeExternalDrop(event: UiTreeExternalDropEvent) {
  handleUiTreeDrop(event);
}

function allowUiTreeDrop(event: Pick<UiTreeDropEvent, 'dropNode' | 'dropToGap'>) {
  const target = parseUiNode(event.dropNode.key);
  if (!target || target.kind === 'root') return false;
  return target.kind === 'slot' ? !event.dropToGap : event.dropToGap;
}

function fieldOfMetadataNode(node: UiTreeNode) {
  const prefix = 'metadata:field:';
  if (!node.key.startsWith(prefix)) return undefined;
  return metadataFields.value.find((field) => field.id === node.key.slice(prefix.length));
}

function parseUiNode(key: string):
  | { kind: 'root' }
  | { kind: 'slot'; slot: PageComposerSlot }
  | { kind: 'field'; slot: PageComposerSlot; fieldId: string }
  | undefined {
  if (key === 'ui:root') return { kind: 'root' };
  const slotMatch = /^ui:slot:(list|form)$/.exec(key);
  if (slotMatch) return { kind: 'slot', slot: slotMatch[1] as PageComposerSlot };
  const fieldMatch = /^ui:field:(list|form):(.+)$/.exec(key);
  if (fieldMatch) return { kind: 'field', slot: fieldMatch[1] as PageComposerSlot, fieldId: fieldMatch[2] };
  return undefined;
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
        <UiEmpty v-else-if="!relation" description="页面编排仅面向已发布主元数据；当前模块暂无可编排主实体" />
        <div v-else class="metadata-tree" data-testid="page-composer-metadata-tree">
          <p class="metadata-tree__hint">拖入中间 UI Tree 的模板槽位；引用字段递归展开将在关联治理接入后提供。</p>
          <UiTree
            v-model:expanded-keys="metadataExpandedKeys"
            :nodes="metadataTreeNodes"
            :selected-key="selectedMetadataTreeKey"
            draggable
            :can-drag="canDragMetadataNode"
            :allow-drop="() => false"
            @select="selectMetadataNode"
            @drag-start="handleMetadataDragStart"
            @double-click="handleMetadataDoubleClick"
          />
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
          <UiTree
            v-model:expanded-keys="uiExpandedKeys"
            :nodes="uiTreeNodes"
            :selected-key="selectedUiTreeKey"
            draggable
            :allow-drop="allowUiTreeDrop"
            :allow-external-drop="allowUiTreeDrop"
            @select="selectUiTreeNode"
            @drag-start="handleUiTreeDragStart"
            @double-click="handleUiTreeDoubleClick"
            @drop="handleUiTreeDrop"
            @external-drop="handleUiTreeExternalDrop"
          />
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

    <RecordDetailPanel :title="composerTitle" :subtitle="compositionSubtitle">
      <template #actions>
        <UiButton v-if="!revision" :loading="saving" type="primary" :disabled="!relation" @click="initializeComposition">初始化页面</UiButton>
        <template v-else>
          <UiButton :loading="saving" @click="saveDraft">保存草稿</UiButton>
          <UiButton type="primary" :loading="publishing" @click="publishDraft">发布</UiButton>
        </template>
      </template>
      <p class="page-composition-notice">
        <template v-if="!page">初始化会创建管理页、Web 全局呈现与首个草稿；不会调用旧 UI 配置集接口。</template>
        <template v-else-if="!revision">当前页面尚无草稿，点击“初始化页面”创建首个可编辑修订。</template>
        <template v-else>草稿保存后不会覆盖已发布呈现；通过发布校验后才会更新当前 Web 全局呈现。</template>
      </p>
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
      <section v-else-if="state.previewMode.value === 'card'" class="preview-surface" data-testid="page-composer-card-preview">
        <div v-if="!state.cardFields.value.length" class="preview-empty">从元数据拖入列表字段，开始配置卡片</div>
        <div v-else class="preview-cards">
          <article v-for="sample in ['示例记录 A', '示例记录 B']" :key="sample" class="preview-card">
            <header><strong>{{ sample }}</strong><span>卡片</span></header>
            <dl>
              <template v-for="field in state.cardFields.value" :key="field.id">
                <dt>{{ field.title }}</dt><dd>{{ field.fieldSpecAlias ?? '文本' }}</dd>
              </template>
            </dl>
          </article>
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
.metadata-tree__hint, .page-composition-notice { margin: 0; color: var(--muyun-text-muted); font-size: 13px; line-height: 1.55; }
.metadata-tree :deep(.ant-tree), .ui-tree :deep(.ant-tree) { min-height: 0; overflow: auto; }
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
.preview-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 14px; }
.preview-card { display: grid; gap: 14px; padding: 16px; border: 1px solid var(--muyun-border-subtle); border-radius: 8px; background: var(--muyun-surface); }
.preview-card header { display: flex; align-items: center; justify-content: space-between; }.preview-card header span { color: var(--muyun-text-muted); font-size: 12px; }
.preview-card dl { display: grid; grid-template-columns: minmax(76px, auto) 1fr; gap: 8px 12px; margin: 0; }.preview-card dt { color: var(--muyun-text-muted); }.preview-card dd { margin: 0; }
.component-drawer { display: grid; align-content: start; gap: 18px; height: 100%; padding: 20px; background: var(--muyun-surface); }.component-drawer header { display: flex; align-items: center; justify-content: space-between; }.component-drawer h2, .component-drawer p { margin: 0; }.component-drawer p { color: var(--muyun-text-muted); }.component-drawer label { display: grid; gap: 6px; }.component-drawer__actions { margin-top: auto; }
</style>
