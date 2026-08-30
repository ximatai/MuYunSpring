<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
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
  ResolvedModuleUiDescriptor,
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
import PageCompositionDescriptorPreview from './PageCompositionDescriptorPreview.vue';

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
const publishedRevision = ref<PresentationRevision>();
const selectedSlot = ref<PageComposerSlot>('list');
const fieldKeyword = ref('');
const selectedMetadataTreeKey = ref<string>();
const metadataExpandedKeys = ref<string[]>(['metadata:root']);
const uiExpandedKeys = ref<string[]>(['ui:root', 'ui:slot:list', 'ui:slot:list:fields', 'ui:slot:form']);
const propertyDrawerOpen = ref(false);
const propertyDraft = ref<PageComposerFieldProperties>({});
const quickSearchPlaceholderDraft = ref('');
const savedUiTreeJson = ref<string>();
const previewDescriptor = ref<ResolvedModuleUiDescriptor>();
const previewLoading = ref(false);
const previewError = ref<string>();
let previewRequestSequence = 0;
let previewDebounceTimer: ReturnType<typeof setTimeout> | undefined;

const previewTabs: UiTabItem[] = [
  { key: 'list', title: '列表预览' },
  { key: 'card', title: '列表卡片' },
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
const selectedQuickSearch = computed(() => state.selectedNode.value?.id === 'template:list:quick-search');
const selectedFieldLabel = computed(() =>
  selectedQuickSearch.value
    ? '快速查询'
    : selectedField.value
      ? fieldDisplayTitle(selectedField.value)
      : '组件',
);
const selectedPreviewFieldName = computed(() => {
  const node = state.selectedNode.value;
  return node?.field ? `${node.slot}:${node.field.fieldName}` : undefined;
});
const currentUiTreeJson = computed(() => JSON.stringify(state.toManagementUiTree()));
const hasUnsavedChanges = computed(() =>
  Boolean(revision.value?.id && savedUiTreeJson.value !== currentUiTreeJson.value),
);
const isMutating = computed(() => saving.value || publishing.value);
const unsavedChangeSummary = computed(() =>
  hasUnsavedChanges.value ? summarizeUiTreeChanges(savedUiTreeJson.value, currentUiTreeJson.value) : [],
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
  if (node.kind === 'template') return 'ui:template:list:quick-search';
  return node.kind === 'slot'
    ? node.slot === 'list'
      ? 'ui:slot:list:fields'
      : `ui:slot:${node.slot}`
    : `ui:field:${node.slot}:${node.field?.id}`;
});
const composerTitle = computed(() => '页面预览');
const mainEntityTitle = computed(() => relation.value?.relationAlias ?? '主实体');
const compositionSubtitle = computed(() => {
  if (!page.value) return '尚未初始化页面定义';
  if (!revision.value) return '尚无可编辑草稿';
  return `草稿 v${revision.value.revisionNo} · 最近发布 ${publishedRevision.value ? `v${publishedRevision.value.revisionNo}` : '无'}`;
});
const compositionHint = computed(() => {
  if (!page.value) return '初始化后即可从左侧字段投放到页面结构。';
  if (!revision.value && publishedRevision.value)
    return '当前没有可编辑草稿；基于最近发布修订创建后续草稿后即可继续编排。';
  if (!revision.value) return '当前页面尚无草稿，初始化后即可开始编排。';
  return '调整页面结构与组件属性；保存草稿后，再发布到 Web 管理页。';
});
const pageContextItems = computed(() => [
  {
    label: '页面',
    value: page.value ? (page.value.title ?? '管理页') : '未初始化',
    detail: page.value?.alias ?? 'management',
  },
  { label: '呈现目标', value: 'Web · 全局', detail: variant.value?.title ?? '等待初始化' },
  {
    label: '模板',
    value: revision.value?.templateAlias
      ? `${revision.value.templateAlias} v${revision.value.templateVersion ?? 1}`
      : 'management v1',
    detail: '受模板契约约束',
  },
  {
    label: '修订',
    value: revision.value ? `草稿 v${revision.value.revisionNo}` : '尚无草稿',
    detail: publishedRevision.value ? `最近发布 v${publishedRevision.value.revisionNo}` : '尚未发布',
  },
]);
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
    children: [
      {
        key: 'ui:slot:list',
        title: slotTitle('list'),
        secondary: '标准列表',
        children: [
          {
            key: 'ui:template:list:quick-search',
            title: '快速查询',
            secondary: state.quickSearchPlaceholder.value ? '模板内置 · 已配置' : '模板内置 · 可配置',
            isLeaf: true,
          },
          {
            key: 'ui:slot:list:fields',
            title: '列表展示字段',
            secondary: fieldsInSlot('list').length ? '可拖拽编排' : '拖动字段到此处',
            children: fieldsInSlot('list').map((field) => ({
              key: `ui:field:list:${field.id}`,
              title: fieldDisplayTitle(field),
              secondary: field.fieldName,
              isLeaf: true,
            })),
          },
        ],
      },
      {
        key: 'ui:slot:form',
        title: slotTitle('form'),
        secondary: fieldsInSlot('form').length ? '详情 / 表单字段' : '拖动字段到此处',
        children: fieldsInSlot('form').map((field) => ({
          key: `ui:field:form:${field.id}`,
          title: fieldDisplayTitle(field),
          secondary: field.fieldName,
          isLeaf: true,
        })),
      },
    ],
  },
]);

watch(selectedField, (field) => {
  propertyDraft.value = { ...(field?.properties ?? {}) };
});

watch(selectedQuickSearch, (selected) => {
  if (selected) quickSearchPlaceholderDraft.value = state.quickSearchPlaceholder.value ?? '';
});

watch(
  () => props.moduleAlias,
  () => {
    resetPreviewDescriptor();
    void loadWorkspace();
  },
  { immediate: true },
);

watch([currentUiTreeJson, () => variant.value?.id, () => revision.value?.id], () =>
  schedulePreviewDescriptor(),
);

onBeforeUnmount(() => resetPreviewDescriptor());

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
type PresentationRevisionPreview = {
  pageId: string;
  variantId: string;
  revisionId: string;
  uiDescriptor: ResolvedModuleUiDescriptor;
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
  resetPreviewDescriptor();
  page.value = undefined;
  variant.value = undefined;
  revision.value = undefined;
  publishedRevision.value = undefined;
  state.replaceFields({ list: [], form: [] });
  state.updateQuickSearchPlaceholder(undefined);
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
    const [drafts, published] = await Promise.all([
      loadAllFromClient(revisionClient(variant.value.id), [
        { fieldName: 'status', operator: 'EQ', values: [pageCompositionTransport.draftRevision] },
      ]),
      loadAllFromClient(revisionClient(variant.value.id), [
        { fieldName: 'status', operator: 'EQ', values: [pageCompositionTransport.publishedRevision] },
      ]),
    ]);
    revision.value = latestRevision(drafts);
    publishedRevision.value = latestRevision(published);
    hydrateDraft(revision.value);
  } catch (cause) {
    presentPlatformError(cause, { source: 'page-composition', phase: 'load' });
  }
}

function resetPreviewDescriptor() {
  previewRequestSequence += 1;
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = undefined;
  }
  previewDescriptor.value = undefined;
  previewLoading.value = false;
  previewError.value = undefined;
}

function schedulePreviewDescriptor() {
  const variantId = variant.value?.id;
  const revisionId = revision.value?.id;
  if (!variantId || !revisionId) {
    resetPreviewDescriptor();
    return;
  }
  const requestSequence = ++previewRequestSequence;
  if (previewDebounceTimer) clearTimeout(previewDebounceTimer);
  previewLoading.value = true;
  previewError.value = undefined;
  const uiTreeJson = currentUiTreeJson.value;
  previewDebounceTimer = setTimeout(() => {
    previewDebounceTimer = undefined;
    void requestPreviewDescriptor(requestSequence, variantId, revisionId, uiTreeJson);
  }, 250);
}

async function requestPreviewDescriptor(
  requestSequence: number,
  variantId: string,
  revisionId: string,
  uiTreeJson: string,
) {
  try {
    const preview = await moduleContext.http.request<PresentationRevisionPreview>({
      method: 'POST',
      path: pageCompositionTransport.previewRevisionPath(variantId, revisionId),
      body: { uiTreeJson },
    });
    if (requestSequence !== previewRequestSequence) return;
    previewDescriptor.value = preview.uiDescriptor;
    previewError.value = undefined;
  } catch (cause) {
    if (requestSequence !== previewRequestSequence) return;
    previewError.value = cause instanceof Error ? cause.message : '服务端未能解析当前草稿。';
  } finally {
    if (requestSequence === previewRequestSequence) previewLoading.value = false;
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
      props?: { list?: { searchPlaceholder?: unknown } };
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
    state.updateQuickSearchPlaceholder(
      typeof tree.props?.list?.searchPlaceholder === 'string' ? tree.props.list.searchPlaceholder : undefined,
    );
    savedUiTreeJson.value = currentUiTreeJson.value;
  } catch {
    // Publication validates the persisted tree. A malformed draft should remain editable as an empty local tree.
  }
}

async function initializeComposition() {
  if (isMutating.value || !relation.value?.id) return;
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
    const latestPublished = latestRevision(
      revisions.filter((item) => item.status === pageCompositionTransport.publishedRevision),
    );
    const treeJsonToPersist = latestPublished?.uiTreeJson ?? currentUiTreeJson.value;
    revision.value = (
      await revisionClient(variant.value.id).insert({
        revisionNo: Math.max(0, ...revisions.map((item) => item.revisionNo ?? 0)) + 1,
        templateAlias: latestPublished?.templateAlias ?? 'management',
        templateVersion: latestPublished?.templateVersion ?? 1,
        uiTreeJson: treeJsonToPersist,
        status: pageCompositionTransport.draftRevision,
        title: latestPublished ? `基于 v${latestPublished.revisionNo ?? 1} 的草稿` : '初始草稿',
        enabled: true,
      })
    ).record;
    if (latestPublished) publishedRevision.value = latestPublished;
    hydrateDraft(revision.value);
  } catch (cause) {
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function saveDraft(
  allowDuringPublish = false,
  treeJsonToPersist = currentUiTreeJson.value,
): Promise<boolean> {
  if (saving.value || (!allowDuringPublish && publishing.value) || !revision.value?.id || !variant.value?.id)
    return false;
  saving.value = true;
  try {
    revision.value = (
      await revisionClient(variant.value.id).update(revision.value.id, {
        ...revision.value,
        uiTreeJson: treeJsonToPersist,
      })
    ).record;
    savedUiTreeJson.value = treeJsonToPersist;
    return true;
  } catch (cause) {
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
    return false;
  } finally {
    saving.value = false;
  }
}

async function publishDraft() {
  if (isMutating.value || !revision.value?.id) return;
  const confirmed = await confirmAction({
    title: '发布页面修订',
    content: `将发布“${page.value?.title ?? '管理页'}”的草稿 v${revision.value.revisionNo}，目标为 Web · 全局，模板为 ${revision.value.templateAlias ?? 'management'} v${revision.value.templateVersion ?? 1}。发布会先保存并校验页面结构，随后替换该目标当前的已发布修订。是否继续？`,
    okText: '确认发布',
  });
  if (!confirmed) return;
  const treeJsonToPublish = currentUiTreeJson.value;
  publishing.value = true;
  try {
    if (!(await saveDraft(true, treeJsonToPublish))) return;
    const publicationCandidate = revision.value;
    await moduleContext.http.request<number>({
      method: 'POST',
      path: `/platform.presentation_publish/revisions/${encodeURIComponent(revision.value.id)}/publish`,
    });
    try {
      await createFollowUpDraft(publicationCandidate, treeJsonToPublish);
    } catch {
      await loadComposition();
      presentPlatformError(
        new Error(
          `草稿 v${publicationCandidate.revisionNo ?? 1} 已发布，但未能生成后续草稿；请基于最近发布修订重新创建草稿。`,
        ),
        { source: 'page-composition', phase: 'action' },
      );
      return;
    }
    await loadComposition();
  } catch (cause) {
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
  } finally {
    publishing.value = false;
  }
}

async function discardUnsavedChanges() {
  if (!revision.value || !hasUnsavedChanges.value || isMutating.value) return;
  const confirmed = await confirmAction({
    title: '放弃本次更改',
    content: `将撤销当前草稿 v${revision.value.revisionNo} 尚未保存的本地调整，已保存的草稿内容不会受影响。是否继续？`,
    okText: '放弃更改',
  });
  if (!confirmed || isMutating.value) return;
  hydrateDraft(revision.value);
  propertyDrawerOpen.value = false;
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

function latestRevision(revisions: PresentationRevision[]) {
  return [...revisions].sort((left, right) => (right.revisionNo ?? 0) - (left.revisionNo ?? 0))[0];
}

type PersistedUiField = { field: string; props?: PageComposerFieldProperties };
type PersistedUiTree = {
  props?: { list?: { searchPlaceholder?: unknown } };
  nodes?: Array<{ slot?: PageComposerSlot; fields?: Array<string | PersistedUiField> }>;
};

function summarizeUiTreeChanges(savedTreeJson: string | undefined, currentTreeJson: string) {
  const saved = parsePersistedUiTree(savedTreeJson);
  const current = parsePersistedUiTree(currentTreeJson);
  const changes: string[] = [];
  let added = 0;
  let removed = 0;
  let propertiesChanged = 0;
  let reordered = false;
  for (const slot of ['list', 'form'] as PageComposerSlot[]) {
    const savedFields = saved.fieldsBySlot.get(slot) ?? [];
    const currentFields = current.fieldsBySlot.get(slot) ?? [];
    const savedByName = new Map(savedFields.map((field) => [field.field, field]));
    const currentByName = new Map(currentFields.map((field) => [field.field, field]));
    added += currentFields.filter((field) => !savedByName.has(field.field)).length;
    removed += savedFields.filter((field) => !currentByName.has(field.field)).length;
    propertiesChanged += currentFields.filter(
      (field) =>
        savedByName.has(field.field) &&
        JSON.stringify(savedByName.get(field.field)?.props ?? {}) !== JSON.stringify(field.props ?? {}),
    ).length;
    if (
      savedFields.length === currentFields.length &&
      savedFields.map((field) => field.field).join('|') !==
        currentFields.map((field) => field.field).join('|')
    ) {
      reordered = true;
    }
  }
  if (added) changes.push(`新增 ${added} 个字段`);
  if (removed) changes.push(`移除 ${removed} 个字段`);
  if (reordered) changes.push('调整字段顺序');
  if (propertiesChanged) changes.push(`修改 ${propertiesChanged} 项展示属性`);
  if (saved.quickSearchPlaceholder !== current.quickSearchPlaceholder) {
    changes.push('修改快速查询占位提示');
  }
  return changes.length ? changes : ['调整页面结构'];
}

function parsePersistedUiTree(treeJson: string | undefined) {
  const fieldsBySlot = new Map<PageComposerSlot, PersistedUiField[]>();
  let quickSearchPlaceholder: string | undefined;
  if (!treeJson) return { fieldsBySlot, quickSearchPlaceholder };
  try {
    const tree = JSON.parse(treeJson) as PersistedUiTree;
    quickSearchPlaceholder =
      typeof tree.props?.list?.searchPlaceholder === 'string' ? tree.props.list.searchPlaceholder : undefined;
    for (const slot of ['list', 'form'] as PageComposerSlot[]) {
      const fields = tree.nodes?.find((node) => node.slot === slot)?.fields ?? [];
      fieldsBySlot.set(
        slot,
        fields.flatMap((entry) => {
          const field = typeof entry === 'string' ? entry : entry.field;
          return field ? [{ field, props: typeof entry === 'string' ? undefined : entry.props }] : [];
        }),
      );
    }
  } catch {
    // A malformed persisted draft is still recoverable through the editor's empty local state.
  }
  return { fieldsBySlot, quickSearchPlaceholder };
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
  if (isMutating.value) return;
  state.addField(field, selectedSlot.value);
}

function slotTitle(slot: PageComposerSlot) {
  return slot === 'list' ? '列表' : '详情 / 表单';
}

function fieldsInSlot(slot: PageComposerSlot) {
  return slot === 'list' ? state.listFields.value : state.formFields.value;
}

function selectMetadataNode(node: UiTreeNode) {
  if (isMutating.value) return;
  selectedMetadataTreeKey.value = node.key;
}

function handleMetadataDoubleClick(event: UiTreeDragEvent) {
  const field = fieldOfMetadataNode(event.node);
  if (field) addToSelectedSlot(field);
}

function selectUiTreeNode(node: UiTreeNode) {
  const parsed = parseUiNode(node.key);
  if (!parsed) return;
  if (parsed.kind === 'slot' || parsed.kind === 'fieldGroup') {
    selectNode({ id: `slot:${parsed.slot}`, kind: 'slot', title: slotTitle(parsed.slot), slot: parsed.slot });
    return;
  }
  if (parsed.kind === 'template') {
    selectNode({ id: 'template:list:quick-search', kind: 'template', title: '快速查询', slot: 'list' });
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
  if (isMutating.value) return;
  const field = fieldOfMetadataNode(event.node);
  const dataTransfer = (event.nativeEvent as DragEvent | undefined)?.dataTransfer;
  if (!field || !dataTransfer) return;
  dataTransfer.effectAllowed = 'copy';
  dataTransfer.setData('text/page-composer-field', field.id);
}

function canDragMetadataNode(node: UiTreeNode) {
  return !isMutating.value && fieldOfMetadataNode(node) != null;
}

function handleUiTreeDragStart(event: UiTreeDragEvent) {
  if (isMutating.value) return;
  const parsed = parseUiNode(event.node.key);
  const dataTransfer = (event.nativeEvent as DragEvent | undefined)?.dataTransfer;
  if (!parsed || parsed.kind !== 'field' || !dataTransfer) return;
  dataTransfer.effectAllowed = 'move';
  dataTransfer.setData('text/page-composer-ui-field', JSON.stringify(parsed));
}

function canDragUiTreeNode(node: UiTreeNode) {
  return !isMutating.value && parseUiNode(node.key)?.kind === 'field';
}

function handleUiTreeDoubleClick(event: UiTreeDragEvent) {
  selectUiTreeNode(event.node);
  if (['field', 'template'].includes(parseUiNode(event.node.key)?.kind ?? '')) openPropertyDrawer();
}

function handleUiTreeDrop(
  event: Pick<UiTreeDropEvent, 'dropNode' | 'dropPosition' | 'dropToGap' | 'nativeEvent'>,
) {
  if (isMutating.value) return;
  const target = parseUiNode(event.dropNode.key);
  if (!target || target.kind === 'root' || target.kind === 'template') return;
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
  if (isMutating.value) return false;
  const target = parseUiNode(event.dropNode.key);
  if (!target || target.kind === 'root' || target.kind === 'template') return false;
  if (target.kind === 'fieldGroup') return !event.dropToGap;
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
  | { kind: 'fieldGroup'; slot: 'list' }
  | { kind: 'template' }
  | { kind: 'field'; slot: PageComposerSlot; fieldId: string }
  | undefined {
  if (key === 'ui:root') return { kind: 'root' };
  if (key === 'ui:template:list:quick-search') return { kind: 'template' };
  if (key === 'ui:slot:list:fields') return { kind: 'fieldGroup', slot: 'list' };
  const slotMatch = /^ui:slot:(list|form)$/.exec(key);
  if (slotMatch) return { kind: 'slot', slot: slotMatch[1] as PageComposerSlot };
  const fieldMatch = /^ui:field:(list|form):(.+)$/.exec(key);
  if (fieldMatch) return { kind: 'field', slot: fieldMatch[1] as PageComposerSlot, fieldId: fieldMatch[2] };
  return undefined;
}

function selectNode(node: (typeof state.nodes.value)[number]) {
  if (isMutating.value) return;
  state.selectNode(node);
  selectedSlot.value = node.slot;
}

function selectPreviewField(slot: PageComposerSlot, field: PageComposerField) {
  if (isMutating.value) return;
  selectNode({ id: `${slot}:${field.id}`, kind: 'field', title: field.title, slot, field });
}

function selectDescriptorPreviewField(slot: PageComposerSlot, fieldName: string, configure = false) {
  const field = fieldsInSlot(slot).find((candidate) => candidate.fieldName === fieldName);
  if (!field) return;
  selectPreviewField(slot, field);
  if (configure) openPropertyDrawer();
}

function selectPreviewMode(key: string) {
  if (isMutating.value || (key !== 'list' && key !== 'card' && key !== 'detail')) return;
  state.previewMode.value = key;
}

function canMoveSelectedField(offset: -1 | 1) {
  const node = state.selectedNode.value;
  if (!node?.field) return false;
  const index = fieldsInSlot(node.slot).findIndex((field) => field.id === node.field?.id);
  return index >= 0 && index + offset >= 0 && index + offset < fieldsInSlot(node.slot).length;
}

function moveSelectedField(offset: -1 | 1) {
  if (isMutating.value) return;
  state.moveSelectedField(offset);
}

function removeSelectedField() {
  if (isMutating.value) return;
  state.removeSelectedField();
}

function fieldDisplayTitle(field: PageComposerField) {
  return field.properties?.label ?? field.title;
}

function openPropertyDrawer() {
  if (isMutating.value || (!selectedField.value && !selectedQuickSearch.value)) return;
  if (selectedQuickSearch.value) quickSearchPlaceholderDraft.value = state.quickSearchPlaceholder.value ?? '';
  else if (selectedField.value) propertyDraft.value = { ...(selectedField.value.properties ?? {}) };
  propertyDrawerOpen.value = true;
}

function applyPropertyDraft() {
  if (isMutating.value) return;
  if (selectedQuickSearch.value) state.updateQuickSearchPlaceholder(quickSearchPlaceholderDraft.value);
  else if (selectedField.value) state.updateSelectedFieldProperties(propertyDraft.value);
  else return;
  propertyDrawerOpen.value = false;
}
</script>

<template>
  <ManagementWorkspace class="page-composition-workspace" layout="composer" :explorer-count="2">
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
            :draggable="!isMutating"
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
        <div v-if="state.selectedNode.value" class="ui-tree__contextbar">
          <span>已选：{{ selectedFieldLabel }}</span>
          <div class="ui-tree__operations">
            <UiButton size="small" :disabled="isMutating" @click="openPropertyDrawer">配置</UiButton>
            <template v-if="selectedField">
              <UiButton
                size="small"
                :disabled="isMutating || !canMoveSelectedField(-1)"
                title="已在首位"
                @click="moveSelectedField(-1)"
              >
                上移
              </UiButton>
              <UiButton
                size="small"
                :disabled="isMutating || !canMoveSelectedField(1)"
                title="已在末位"
                @click="moveSelectedField(1)"
              >
                下移
              </UiButton>
              <UiButton size="small" danger :disabled="isMutating" @click="removeSelectedField">
                移除
              </UiButton>
            </template>
          </div>
        </div>
        <div class="ui-tree" data-testid="page-composer-ui-tree">
          <UiTree
            v-model:expanded-keys="uiExpandedKeys"
            :nodes="uiTreeNodes"
            :selected-key="selectedUiTreeKey"
            :draggable="!isMutating"
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
            :disabled="isMutating || !relation"
            type="primary"
            @click="initializeComposition"
          >
            {{ publishedRevision ? '基于已发布版本创建草稿' : '初始化页面' }}
          </UiButton>
          <template v-else>
            <UiButton
              :loading="saving"
              :disabled="isMutating || !hasUnsavedChanges"
              @click="() => void saveDraft()"
            >
              保存草稿
            </UiButton>
            <UiButton v-if="hasUnsavedChanges" :disabled="isMutating" @click="discardUnsavedChanges">
              放弃本次更改
            </UiButton>
            <UiButton type="primary" :loading="publishing" :disabled="isMutating" @click="publishDraft">
              发布草稿
            </UiButton>
          </template>
        </div>
      </template>
      <section class="page-composition-context" aria-label="当前页面编排上下文">
        <div v-for="item in pageContextItems" :key="item.label" class="page-composition-context__item">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.detail }}</small>
        </div>
      </section>
      <div
        v-if="revision"
        class="page-composition-status"
        :class="{ 'page-composition-status--dirty': hasUnsavedChanges }"
        aria-live="polite"
      >
        {{ hasUnsavedChanges ? '未保存更改' : '草稿已保存' }}
      </div>
      <p v-if="hasUnsavedChanges" class="page-composition-change-summary">
        本次更改：{{ unsavedChangeSummary.join(' · ') }}
      </p>
      <p class="page-composition-notice">{{ compositionHint }}</p>
      <UiTabs
        :active-key="state.previewMode.value"
        :tabs="previewTabs"
        @update:active-key="selectPreviewMode"
      />
      <p v-if="previewLoading" class="page-composition-preview-status" aria-live="polite">
        草稿解析中{{ previewDescriptor ? '；当前仍展示上一次成功解析的结果。' : '。' }}
      </p>
      <p
        v-else-if="previewError"
        class="page-composition-preview-status page-composition-preview-status--error"
        aria-live="polite"
      >
        草稿解析失败：{{ previewError }}
      </p>
      <PageCompositionDescriptorPreview
        v-if="previewDescriptor"
        :descriptor="previewDescriptor"
        :mode="state.previewMode.value"
        :selected-field-name="selectedPreviewFieldName"
        @select-field="(slot, fieldName) => selectDescriptorPreviewField(slot, fieldName)"
        @configure-field="(slot, fieldName) => selectDescriptorPreviewField(slot, fieldName, true)"
      />
      <UiEmpty
        v-else-if="revision && !previewLoading"
        class="page-composition-preview-empty"
        :description="
          previewError ? '保留当前草稿；修正页面结构后将自动重新解析。' : '正在等待草稿解析结果。'
        "
      />
      <UiEmpty
        v-else-if="!revision"
        class="page-composition-preview-empty"
        description="初始化页面草稿后，即可查看服务端解析的页面预览。"
      />
    </RecordDetailPanel>

    <RecordDetailDrawer
      :open="propertyDrawerOpen"
      render-mode="inline"
      :title="`配置：${selectedFieldLabel}`"
      subtitle="页面组件属性仅作用于当前草稿；元数据字段事实不在此处修改。"
      :width="420"
      @close="propertyDrawerOpen = false"
    >
      <div v-if="selectedQuickSearch" class="component-property-drawer">
        <label>
          <span>搜索占位提示</span>
          <UiInput v-model:value="quickSearchPlaceholderDraft" placeholder="例如：搜索名称、编码或 ID" />
        </label>
        <p>该组件是 management v1 的模板内置快速查询；仅可调整占位提示。</p>
      </div>
      <div v-else-if="selectedField" class="component-property-drawer">
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
        <UiButton
          type="primary"
          :disabled="isMutating || (!selectedQuickSearch && Boolean(propertyValidationMessage))"
          @click="applyPropertyDraft"
        >
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
.page-composition-context {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}
.page-composition-context__item {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 6px;
  background: var(--muyun-surface-muted);
}
.page-composition-context__item > span,
.page-composition-context__item > small {
  overflow: hidden;
  color: var(--muyun-text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.page-composition-context__item > strong {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
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
.page-composition-change-summary {
  margin: 0 0 6px;
  color: var(--muyun-warning-text, var(--muyun-text-muted));
  font-size: 12px;
  line-height: 1.5;
}
.page-composition-preview-status {
  margin: 10px 0 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
  line-height: 1.5;
}
.page-composition-preview-status--error {
  color: var(--muyun-danger);
}
.page-composition-preview-empty {
  min-height: 280px;
  margin-top: 12px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
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
  .page-composition-context {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
