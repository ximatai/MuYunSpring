<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  ManagementExplorerColumn,
  ManagementWorkspace,
  RecordDetailDrawer,
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
  UiSelect,
  UiSpin,
  UiSwitch,
  UiTabs,
  UiTree,
  type UiTabItem,
  type UiTreeDragEvent,
  type UiTreeDropEvent,
  type UiTreeExternalDropEvent,
  type UiTreeNode,
} from '@muyun/vue-ui-antdv';
import type {
  MetadataField,
  ModuleMetadataRelation,
  WebPageResponse,
  WebQueryCondition,
} from '@muyun/web-contracts';
import {
  createPageCompositionDraftState,
  type PageComposerField,
  type PageComposerFieldProperties,
  type PageComposerSlot,
} from './pageCompositionDraftState';
import { pageCompositionTransport } from './pageCompositionTransport';

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
const fieldKeyword = ref('');
const selectedMetadataTreeKey = ref<string>();
const metadataExpandedKeys = ref<string[]>(['metadata:root']);
const uiExpandedKeys = ref<string[]>(['ui:root', 'ui:slot:list', 'ui:slot:form']);
const propertyDrawerOpen = ref(false);
const propertyDraft = ref<PageComposerFieldProperties>({});
const savedUiTreeJson = ref<string>();

const previewTabs: UiTabItem[] = [
  { key: 'list', title: '列表预览' },
  { key: 'card', title: '列表卡片' },
  { key: 'detail', title: '详情预览' },
];
const listPreviewGridStyle = computed(() => ({
  gridTemplateColumns: state.listFields.value
    .map((field) => field.properties?.width ?? 'minmax(120px, 1fr)')
    .join(' '),
}));
const visibleFields = computed(() => {
  const keyword = fieldKeyword.value.trim().toLowerCase();
  if (!keyword) return metadataFields.value;
  return metadataFields.value.filter(
    (field) => field.title.toLowerCase().includes(keyword) || field.fieldName.toLowerCase().includes(keyword),
  );
});
const selectedField = computed(() => state.selectedNode.value?.field);
const selectedFieldLabel = computed(() =>
  selectedField.value ? fieldDisplayTitle(selectedField.value) : '组件',
);
const currentUiTreeJson = computed(() => JSON.stringify(state.toManagementUiTree()));
const hasUnsavedChanges = computed(() =>
  Boolean(revision.value?.id && savedUiTreeJson.value !== currentUiTreeJson.value),
);
const propertyValidationMessage = computed(() => {
  if (state.selectedNode.value?.slot !== 'list') return undefined;
  const width = propertyDraft.value.width?.trim();
  if (!width || /^\d+(px|%)$/.test(width)) return undefined;
  return '列宽需使用数字加 px 或 %，例如 160px、25%。';
});
const selectedUiTreeKey = computed(() => {
  const node = state.selectedNode.value;
  if (!node) return undefined;
  return node.kind === 'slot' ? `ui:slot:${node.slot}` : `ui:field:${node.slot}:${node.field?.id}`;
});
const composerTitle = computed(() => '页面预览');
const mainEntityTitle = computed(() => relation.value?.relationAlias ?? '主实体');
const compositionSubtitle = computed(() => {
  if (!page.value) return '尚未初始化页面定义';
  if (!revision.value) return '尚无可编辑草稿';
  return `草稿 v${revision.value.revisionNo} · Web 全局 · management v1`;
});
const compositionHint = computed(() => {
  if (!page.value) return '初始化后即可从左侧字段投放到页面结构。';
  if (!revision.value) return '当前页面尚无草稿，初始化后即可开始编排。';
  return '调整页面结构与组件属性；保存草稿后，再发布到 Web 管理页。';
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
      secondary:
        fieldsInSlot(slot).length > 0
          ? slot === 'list'
            ? '列表展示字段'
            : '详情 / 表单字段'
          : '拖动字段到此处',
      children: fieldsInSlot(slot).map((field) => ({
        key: `ui:field:${slot}:${field.id}`,
        title: fieldDisplayTitle(field),
        secondary: field.fieldName,
        isLeaf: true,
      })),
    })),
  },
]);

watch(selectedField, (field) => {
  propertyDraft.value = { ...(field?.properties ?? {}) };
});

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
    const fields = await loadAll<MetadataField>(
      `/platform.metadata/${encodeURIComponent(main.metadataId)}/fields/query`,
    );
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
  savedUiTreeJson.value = undefined;
  try {
    const pages = await loadAllFromClient(pageClient(), [
      { fieldName: 'alias', operator: 'EQ', values: ['management'] },
    ]);
    page.value = pages[0];
    if (!page.value?.id) return;
    const variants = await loadAllFromClient(variantClient(page.value.id), [
      { fieldName: 'clientType', operator: 'EQ', values: [pageCompositionTransport.webClient] },
      { fieldName: 'scopeType', operator: 'EQ', values: [pageCompositionTransport.globalScope] },
    ]);
    variant.value = variants[0];
    if (!variant.value?.id) return;
    const revisions = await loadAllFromClient(revisionClient(variant.value.id), [
      { fieldName: 'status', operator: 'EQ', values: [pageCompositionTransport.draftRevision] },
    ]);
    revision.value = revisions.sort((left, right) => (right.revisionNo ?? 0) - (left.revisionNo ?? 0))[0];
    hydrateDraft(revision.value);
  } catch (cause) {
    presentPlatformError(cause, { source: 'page-composition', phase: 'load' });
  }
}

async function loadAllFromClient<T>(
  client: ModuleCrudClient<T>,
  conditions: WebQueryCondition[] = [],
): Promise<T[]> {
  const response = await client.query({ unpaged: true, conditions });
  return response.records;
}

function hydrateDraft(current: PresentationRevision | undefined) {
  if (!current?.uiTreeJson) return;
  try {
    const tree = JSON.parse(current.uiTreeJson) as {
      nodes?: Array<{
        slot?: PageComposerSlot;
        fields?: Array<string | { field?: string; props?: PageComposerFieldProperties }>;
      }>;
    };
    const resolve = (slot: PageComposerSlot) => tree.nodes?.find((node) => node.slot === slot)?.fields ?? [];
    const fieldsByName = new Map(metadataFields.value.map((field) => [field.fieldName, field]));
    const resolveField = (
      entry: string | { field?: string; props?: PageComposerFieldProperties },
    ): PageComposerField | undefined => {
      const fieldName = typeof entry === 'string' ? entry : entry.field;
      const source = fieldName ? fieldsByName.get(fieldName) : undefined;
      if (!source) return undefined;
      return typeof entry === 'string' || !entry.props
        ? { ...source }
        : { ...source, properties: entry.props };
    };
    state.replaceFields({
      list: resolve('list')
        .map(resolveField)
        .filter((field): field is PageComposerField => Boolean(field)),
      form: resolve('form')
        .map(resolveField)
        .filter((field): field is PageComposerField => Boolean(field)),
    });
    savedUiTreeJson.value = currentUiTreeJson.value;
  } catch {
    // Publication validates the persisted tree. A malformed draft should remain editable as an empty local tree.
  }
}

async function initializeComposition() {
  if (!relation.value?.id) return;
  saving.value = true;
  try {
    if (!page.value) {
      page.value = (
        await pageClient().insert({
          alias: 'management',
          contractType: pageCompositionTransport.managementContract,
          mainRelationId: relation.value.id,
          title: `${props.moduleTitle ?? props.moduleAlias}管理页`,
          enabled: true,
        })
      ).record;
    }
    if (!page.value.id) return;
    if (!variant.value) {
      variant.value = (
        await variantClient(page.value.id).insert({
          clientType: pageCompositionTransport.webClient,
          scopeType: pageCompositionTransport.globalScope,
          title: 'Web 全局呈现',
          enabled: true,
        })
      ).record;
    }
    if (!variant.value.id || revision.value) return;
    const revisions = await loadAllFromClient(revisionClient(variant.value.id));
    revision.value = (
      await revisionClient(variant.value.id).insert({
        revisionNo: Math.max(0, ...revisions.map((item) => item.revisionNo ?? 0)) + 1,
        templateAlias: 'management',
        templateVersion: 1,
        uiTreeJson: JSON.stringify(state.toManagementUiTree()),
        status: pageCompositionTransport.draftRevision,
        title: '初始草稿',
        enabled: true,
      })
    ).record;
    savedUiTreeJson.value = currentUiTreeJson.value;
  } catch (cause) {
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function saveDraft(): Promise<boolean> {
  if (!revision.value?.id || !variant.value?.id) return false;
  saving.value = true;
  try {
    revision.value = (
      await revisionClient(variant.value.id).update(revision.value.id, {
        ...revision.value,
        uiTreeJson: JSON.stringify(state.toManagementUiTree()),
      })
    ).record;
    savedUiTreeJson.value = currentUiTreeJson.value;
    return true;
  } catch (cause) {
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
    return false;
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
    if (!(await saveDraft())) return;
    const publishedTreeJson = JSON.stringify(state.toManagementUiTree());
    const publishedRevision = revision.value;
    await moduleContext.http.request<number>({
      method: 'POST',
      path: `/platform.presentation_publish/revisions/${encodeURIComponent(revision.value.id)}/publish`,
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
    status: pageCompositionTransport.draftRevision,
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
  if (field)
    selectNode({
      id: `${parsed.slot}:${field.id}`,
      kind: 'field',
      title: field.title,
      slot: parsed.slot,
      field,
    });
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

function canDragUiTreeNode(node: UiTreeNode) {
  return parseUiNode(node.key)?.kind === 'field';
}

function handleUiTreeDoubleClick(event: UiTreeDragEvent) {
  selectUiTreeNode(event.node);
  if (parseUiNode(event.node.key)?.kind === 'field') openPropertyDrawer();
}

function handleUiTreeDrop(
  event: Pick<UiTreeDropEvent, 'dropNode' | 'dropPosition' | 'dropToGap' | 'nativeEvent'>,
) {
  const target = parseUiNode(event.dropNode.key);
  if (!target || target.kind === 'root') return;
  const dataTransfer = (event.nativeEvent as DragEvent | undefined)?.dataTransfer;
  const fieldId = dataTransfer?.getData('text/page-composer-field');
  if (fieldId) {
    const field = metadataFields.value.find((candidate) => candidate.id === fieldId);
    const targetIndex =
      target.kind === 'field'
        ? fieldsInSlot(target.slot).findIndex((candidate) => candidate.id === target.fieldId) +
          (event.dropPosition > 0 ? 1 : 0)
        : undefined;
    if (field) state.addField(field, target.slot, targetIndex);
    return;
  }
  const raw = dataTransfer?.getData('text/page-composer-ui-field');
  if (!raw) return;
  try {
    const source = JSON.parse(raw) as { kind?: string; slot?: PageComposerSlot; fieldId?: string };
    if (source.kind !== 'field' || !source.slot || !source.fieldId) return;
    const targetIndex =
      target.kind === 'field'
        ? fieldsInSlot(target.slot).findIndex((field) => field.id === target.fieldId) +
          (event.dropPosition > 0 ? 1 : 0)
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

function parseUiNode(
  key: string,
):
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

function selectPreviewField(slot: PageComposerSlot, field: PageComposerField) {
  selectNode({ id: `${slot}:${field.id}`, kind: 'field', title: field.title, slot, field });
}

function openPreviewFieldProperties(slot: PageComposerSlot, field: PageComposerField) {
  selectPreviewField(slot, field);
  openPropertyDrawer();
}

function isPreviewFieldSelected(slot: PageComposerSlot, field: PageComposerField) {
  return state.selectedNodeId.value === `${slot}:${field.id}`;
}

function canMoveSelectedField(offset: -1 | 1) {
  const node = state.selectedNode.value;
  if (!node?.field) return false;
  const index = fieldsInSlot(node.slot).findIndex((field) => field.id === node.field?.id);
  return index >= 0 && index + offset >= 0 && index + offset < fieldsInSlot(node.slot).length;
}

function fieldDisplayTitle(field: PageComposerField) {
  return field.properties?.label ?? field.title;
}

function openPropertyDrawer() {
  if (!selectedField.value) return;
  propertyDraft.value = { ...(selectedField.value.properties ?? {}) };
  propertyDrawerOpen.value = true;
}

function applyPropertyDraft() {
  if (!selectedField.value) return;
  state.updateSelectedFieldProperties(propertyDraft.value);
  propertyDrawerOpen.value = false;
}
</script>

<template>
  <ManagementWorkspace class="page-composition-workspace" :explorer-count="2">
    <ManagementExplorerColumn>
      <RecordExplorerPanel
        v-model:search-keyword="fieldKeyword"
        title="可用字段"
        :subtitle="`${mainEntityTitle} · 主实体`"
        search-placeholder="搜索字段"
        @refresh="loadMetadataTree"
      >
        <UiSpin v-if="loading" tip="加载主实体字段" />
        <UiEmpty v-else-if="!relation" description="页面编排仅面向已发布主元数据；当前模块暂无可编排主实体" />
        <div v-else class="metadata-tree" data-testid="page-composer-metadata-tree">
          <p class="metadata-tree__hint">拖动字段到“页面结构”；引用字段展开将在关联治理接入后提供。</p>
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
      <RecordExplorerPanel title="页面结构" subtitle="management v1" :searchable="false">
        <div v-if="selectedField" class="ui-tree__contextbar">
          <span>已选：{{ selectedFieldLabel }}</span>
          <div class="ui-tree__operations">
            <UiButton size="small" @click="openPropertyDrawer">配置</UiButton>
            <UiButton
              size="small"
              :disabled="!canMoveSelectedField(-1)"
              title="已在首位"
              @click="state.moveSelectedField(-1)"
            >
              上移
            </UiButton>
            <UiButton
              size="small"
              :disabled="!canMoveSelectedField(1)"
              title="已在末位"
              @click="state.moveSelectedField(1)"
            >
              下移
            </UiButton>
            <UiButton size="small" danger @click="state.removeSelectedField">移除</UiButton>
          </div>
        </div>
        <div class="ui-tree" data-testid="page-composer-ui-tree">
          <UiTree
            v-model:expanded-keys="uiExpandedKeys"
            :nodes="uiTreeNodes"
            :selected-key="selectedUiTreeKey"
            draggable
            :can-drag="canDragUiTreeNode"
            :allow-drop="allowUiTreeDrop"
            :allow-external-drop="allowUiTreeDrop"
            @select="selectUiTreeNode"
            @drag-start="handleUiTreeDragStart"
            @double-click="handleUiTreeDoubleClick"
            @drop="handleUiTreeDrop"
            @external-drop="handleUiTreeExternalDrop"
          />
        </div>
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <RecordDetailPanel :title="composerTitle" :subtitle="compositionSubtitle">
      <template #actions>
        <div class="page-composition-actions">
          <UiButton
            v-if="!revision"
            :loading="saving"
            type="primary"
            :disabled="!relation"
            @click="initializeComposition"
          >
            初始化页面
          </UiButton>
          <template v-else>
            <UiButton :loading="saving" @click="saveDraft">保存草稿</UiButton>
            <UiButton type="primary" :loading="publishing" @click="publishDraft">发布</UiButton>
          </template>
        </div>
      </template>
      <div
        v-if="revision"
        class="page-composition-status"
        :class="{ 'page-composition-status--dirty': hasUnsavedChanges }"
      >
        {{ hasUnsavedChanges ? '未保存更改' : '草稿已保存' }}
      </div>
      <p class="page-composition-notice">{{ compositionHint }}</p>
      <UiTabs v-model:active-key="state.previewMode.value" :tabs="previewTabs" />
      <section
        v-if="state.previewMode.value === 'list'"
        class="preview-surface"
        data-testid="page-composer-list-preview"
      >
        <header class="preview-surface__toolbar">
          <strong>列表布局</strong><span>字段来自页面结构</span>
        </header>
        <div class="preview-table">
          <div v-if="!state.listFields.value.length" class="preview-empty">
            从元数据拖入字段，开始配置列表
          </div>
          <template v-else>
            <div class="preview-table__header" :style="listPreviewGridStyle">
              <span
                v-for="field in state.listFields.value"
                :key="field.id"
                class="preview-field"
                :class="{ 'preview-field--selected': isPreviewFieldSelected('list', field) }"
                :style="{ textAlign: field.properties?.align }"
                role="button"
                tabindex="0"
                :title="`配置${fieldDisplayTitle(field)}`"
                @click="selectPreviewField('list', field)"
                @dblclick="openPreviewFieldProperties('list', field)"
                @keydown.enter="openPreviewFieldProperties('list', field)"
                >{{ fieldDisplayTitle(field) }}</span
              >
            </div>
            <div class="preview-table__row" :style="listPreviewGridStyle">
              <span
                v-for="field in state.listFields.value"
                :key="field.id"
                class="preview-field"
                :class="{ 'preview-field--selected': isPreviewFieldSelected('list', field) }"
                :style="{ textAlign: field.properties?.align }"
                role="button"
                tabindex="0"
                :title="`配置${fieldDisplayTitle(field)}`"
                @click="selectPreviewField('list', field)"
                @dblclick="openPreviewFieldProperties('list', field)"
                @keydown.enter="openPreviewFieldProperties('list', field)"
                >{{ field.fieldSpecAlias ?? '文本' }}</span
              >
            </div>
          </template>
        </div>
      </section>
      <section
        v-else-if="state.previewMode.value === 'card'"
        class="preview-surface"
        data-testid="page-composer-card-preview"
      >
        <header class="preview-surface__toolbar">
          <strong>列表卡片</strong><span>继承列表字段，不单独编排</span>
        </header>
        <div v-if="!state.cardFields.value.length" class="preview-empty">
          先配置列表字段，即可查看卡片呈现
        </div>
        <div v-else class="preview-cards">
          <article v-for="sample in ['示例记录 A', '示例记录 B']" :key="sample" class="preview-card">
            <header>
              <strong>{{ sample }}</strong
              ><span>卡片</span>
            </header>
            <dl>
              <div
                v-for="field in state.cardFields.value"
                :key="field.id"
                class="preview-card__field preview-field"
                :class="{ 'preview-field--selected': isPreviewFieldSelected('list', field) }"
                role="button"
                tabindex="0"
                :title="`配置${fieldDisplayTitle(field)}`"
                @click="selectPreviewField('list', field)"
                @dblclick="openPreviewFieldProperties('list', field)"
                @keydown.enter="openPreviewFieldProperties('list', field)"
              >
                <dt>{{ fieldDisplayTitle(field) }}</dt>
                <dd>{{ field.fieldSpecAlias ?? '文本' }}</dd>
              </div>
            </dl>
          </article>
        </div>
      </section>
      <section v-else class="preview-surface" data-testid="page-composer-detail-preview">
        <div v-if="!state.formFields.value.length" class="preview-empty">
          从元数据拖入字段，开始配置详情 / 表单
        </div>
        <dl v-else class="preview-form">
          <div
            v-for="field in state.formFields.value"
            :key="field.id"
            class="preview-form__field preview-field"
            :class="{ 'preview-field--selected': isPreviewFieldSelected('form', field) }"
            :style="{ gridColumn: `span ${field.properties?.columnSpan ?? 1}` }"
            role="button"
            tabindex="0"
            :title="`配置${fieldDisplayTitle(field)}`"
            @click="selectPreviewField('form', field)"
            @dblclick="openPreviewFieldProperties('form', field)"
            @keydown.enter="openPreviewFieldProperties('form', field)"
          >
            <dt>{{ fieldDisplayTitle(field) }}<em v-if="field.required">*</em></dt>
            <dd>
              <span>{{ field.fieldSpecAlias ?? '输入控件' }}</span>
              <small v-if="field.properties?.readOnly">只读</small>
            </dd>
          </div>
        </dl>
      </section>
    </RecordDetailPanel>

    <RecordDetailDrawer
      :open="propertyDrawerOpen"
      render-mode="inline"
      :title="`配置：${selectedFieldLabel}`"
      subtitle="页面组件属性仅作用于当前草稿；元数据字段事实不在此处修改。"
      :width="420"
      @close="propertyDrawerOpen = false"
    >
      <div v-if="selectedField" class="component-property-drawer">
        <label>
          <span>展示标题</span>
          <UiInput v-model:value="propertyDraft.label" :placeholder="selectedField.title" />
        </label>
        <template v-if="state.selectedNode.value?.slot === 'list'">
          <label>
            <span>列宽</span>
            <UiInput v-model:value="propertyDraft.width" placeholder="例如 160px 或 25%" />
            <small v-if="propertyValidationMessage" class="component-property-drawer__error">
              {{ propertyValidationMessage }}
            </small>
          </label>
          <label>
            <span>对齐</span>
            <UiSelect
              v-model:value="propertyDraft.align"
              :options="[
                { label: '左对齐', value: 'left' },
                { label: '居中', value: 'center' },
                { label: '右对齐', value: 'right' },
              ]"
              placeholder="遵循平台默认"
            />
          </label>
        </template>
        <template v-else>
          <label>
            <span>表单列宽度</span>
            <UiSelect
              v-model:value="propertyDraft.columnSpan"
              :options="[
                { label: '半行（1 列）', value: 1 },
                { label: '整行（2 列）', value: 2 },
              ]"
              placeholder="遵循平台默认"
            />
          </label>
          <label class="component-property-drawer__switch">
            <span>只读展示</span>
            <UiSwitch v-model:checked="propertyDraft.readOnly" />
          </label>
        </template>
        <p>保存草稿后属性才会持久化；发布时由模板 schema 校验后写入运行态。</p>
      </div>
      <template #operation>
        <UiButton @click="propertyDrawerOpen = false">取消</UiButton>
        <UiButton type="primary" :disabled="Boolean(propertyValidationMessage)" @click="applyPropertyDraft">
          应用到草稿
        </UiButton>
      </template>
    </RecordDetailDrawer>
  </ManagementWorkspace>
</template>

<style scoped>
.page-composition-workspace {
  min-height: 0;
  height: 100%;
}
.metadata-tree,
.ui-tree {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
  overflow: auto;
}
.metadata-tree__hint,
.page-composition-notice {
  flex: 0 0 auto;
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 13px;
  line-height: 1.55;
}
.metadata-tree > :deep(.ui-tree) {
  flex: 1 1 auto;
}
.metadata-tree :deep(.ant-tree),
.ui-tree :deep(.ant-tree) {
  flex: 0 0 auto;
  min-height: 0;
  overflow: auto;
}
.ui-tree__contextbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--muyun-border-subtle);
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.ui-tree__contextbar > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ui-tree__operations {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}
.page-composition-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
.page-composition-notice {
  margin-bottom: 12px;
}
.page-composition-status {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  margin-bottom: 6px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--muyun-surface-muted);
  color: var(--muyun-text-muted);
  font-size: 12px;
  line-height: 20px;
}
.page-composition-status--dirty {
  background: var(--muyun-warning-surface, var(--muyun-surface-muted));
  color: var(--muyun-warning-text, var(--muyun-text));
}
.preview-surface {
  margin-top: 12px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  min-height: 280px;
  padding: 16px;
}
.preview-surface__toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 18px;
}
.preview-surface__toolbar span {
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.preview-table {
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 6px;
  overflow: hidden;
}
.preview-table__header,
.preview-table__row {
  display: grid;
}
.preview-table__header {
  background: var(--muyun-surface-muted);
  font-weight: 600;
}
.preview-table span {
  padding: 10px;
  border-right: 1px solid var(--muyun-border-subtle);
}
.preview-field {
  cursor: pointer;
  outline: 1px solid transparent;
  outline-offset: -1px;
  transition:
    outline-color 120ms ease,
    background 120ms ease;
}
.preview-field:hover,
.preview-field:focus-visible {
  outline-color: var(--muyun-primary);
}
.preview-field--selected {
  outline: 2px solid var(--muyun-primary);
  background: var(--muyun-primary-surface, var(--muyun-hover));
}
.preview-empty {
  display: grid;
  min-height: 180px;
  place-items: center;
  color: var(--muyun-text-muted);
}
.preview-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
  max-width: 760px;
}
.preview-form__field {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
}
.preview-form dt {
  color: var(--muyun-text-muted);
}
.preview-form dd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--muyun-border);
  border-radius: 4px;
}
.preview-form small {
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.preview-form em {
  color: var(--muyun-danger);
  margin-left: 4px;
}
.preview-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
}
.preview-card {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  background: var(--muyun-surface);
}
.preview-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.preview-card header span {
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.preview-card dl {
  display: grid;
  grid-template-columns: minmax(76px, auto) 1fr;
  gap: 8px 12px;
  margin: 0;
}
.preview-card dt {
  color: var(--muyun-text-muted);
}
.preview-card dd {
  margin: 0;
}
.preview-card__field {
  display: grid;
  grid-column: 1 / -1;
  grid-template-columns: minmax(76px, auto) 1fr;
  gap: 8px 12px;
  margin: -2px;
  padding: 2px;
  border-radius: 4px;
}
.component-property-drawer {
  display: grid;
  gap: 16px;
}
.component-property-drawer label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.component-property-drawer__switch {
  grid-template-columns: 1fr auto;
  align-items: center;
}
.component-property-drawer p {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 13px;
  line-height: 1.55;
}
.component-property-drawer__error {
  color: var(--muyun-danger);
  font-size: 12px;
  line-height: 1.4;
}

@media (max-width: 1180px) {
  .page-composition-workspace :deep(.management-workspace__grid) {
    grid-template-columns: minmax(180px, 0.8fr) minmax(220px, 1fr) minmax(0, 2fr);
    gap: 8px;
  }

  .page-composition-workspace :deep(.management-panel-header) {
    align-items: flex-start;
  }

  .page-composition-workspace :deep(.management-panel-header-actions) {
    flex-wrap: wrap;
  }
}
</style>
