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
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
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
import PageCompositionTree, { type ComposerDropTarget } from './PageCompositionTree.vue';

defineOptions({ name: 'PageCompositionWorkspace' });

const props = defineProps<{ moduleAlias: string; moduleTitle?: string }>();
const moduleContext = useModuleContext({ moduleAlias: 'platform.module' });
const state = createPageCompositionDraftState();
const loading = ref(false);
const saving = ref(false);
const publishing = ref(false);
const relation = ref<ModuleMetadataRelation>();
const metadataRelations = ref<ModuleMetadataRelation[]>([]);
const metadataFields = ref<PageComposerField[]>([]);
const childMetadataFields = ref(new Map<string, PageComposerField[]>());
const page = ref<PageDefinition>();
const variant = ref<PresentationVariant>();
const revision = ref<PresentationRevision>();
const publishedRevision = ref<PresentationRevision>();
const selectedSlot = ref<PageComposerSlot>('list');
const fieldKeyword = ref('');
const selectedMetadataTreeKey = ref<string>();
const metadataExpandedKeys = ref<string[]>(['metadata:root']);
const propertyDrawerOpen = ref(false);
const propertyDraft = ref<PageComposerFieldProperties>({});
const groupTitleDraft = ref('');
const groupSubtitleDraft = ref('');
const quickSearchPlaceholderDraft = ref('');
const savedUiTreeJson = ref<string>();
const previewDescriptor = ref<ResolvedModuleUiDescriptor>();
const previewLoading = ref(false);
const previewError = ref<string>();
const activeMetadataDragPayload = ref<MetadataDragPayload>();
let previewRequestSequence = 0;
let previewDebounceTimer: ReturnType<typeof setTimeout> | undefined;
let workspaceLoadSequence = 0;

const previewTabs: UiTabItem[] = [
  { key: 'list', title: '列表预览' },
  { key: 'query', title: '查询预览' },
  { key: 'detail', title: '详情预览' },
  { key: 'edit', title: '编辑预览' },
];
const visibleFields = computed(() => {
  const keyword = fieldKeyword.value.trim().toLowerCase();
  if (!keyword) return metadataFields.value;
  return metadataFields.value.filter(
    (field) => field.title.toLowerCase().includes(keyword) || field.fieldName.toLowerCase().includes(keyword),
  );
});
const selectedField = computed(() => state.selectedNode.value?.field);
const selectedMetadataRelation = computed(() => {
  const key = selectedMetadataTreeKey.value;
  const prefix = 'metadata:relation:';
  if (!key?.startsWith(prefix)) return undefined;
  return metadataRelations.value.find(
    (candidate) => (candidate.id ?? candidate.metadataId) === key.slice(prefix.length),
  );
});
const selectedRelation = computed(() => state.selectedNode.value?.relation);
const selectedRelationField = computed(() => state.selectedNode.value?.relationField);
const selectedGroup = computed(() => state.selectedNode.value?.group);
const selectedGroupNode = computed(() =>
  state.selectedNode.value?.kind === 'group' ? state.selectedNode.value.group : undefined,
);
const selectedQuickSearch = computed(() => state.selectedNode.value?.id === 'template:list:quick-search');
const selectedMetadataField = computed(() => {
  const key = selectedMetadataTreeKey.value;
  const prefix = 'metadata:field:';
  if (!key?.startsWith(prefix)) return undefined;
  return metadataFields.value.find((field) => field.id === key.slice(prefix.length));
});
const selectedChildMetadataField = computed(() => {
  const match = /^metadata:relation-field:(.+):(.+)$/.exec(selectedMetadataTreeKey.value ?? '');
  if (!match) return undefined;
  const relation = metadataRelations.value.find(
    (candidate) => (candidate.id ?? candidate.metadataId) === match[1],
  );
  const field = childMetadataFields.value.get(match[1])?.find((candidate) => candidate.id === match[2]);
  return relation && field ? { relation, field } : undefined;
});
const selectedFieldLabel = computed(() =>
  selectedQuickSearch.value
    ? '快速查询'
    : selectedField.value
      ? fieldDisplayTitle(selectedField.value)
      : selectedRelationField.value
        ? fieldDisplayTitle(selectedRelationField.value)
        : selectedGroupNode.value
          ? selectedGroupNode.value.title
          : (selectedRelation.value?.title ?? '组件'),
);
const propertyDrawerTitle = computed(() =>
  selectedQuickSearch.value ? '配置：快速查询占位提示' : `配置：${selectedFieldLabel.value}`,
);
const quickAddTargetLabel = computed(() =>
  selectedSlot.value === 'list' ? '列表展示字段' : '详情 / 表单字段',
);
const selectedPreviewFieldName = computed(() => {
  const node = state.selectedNode.value;
  return node?.field ? `${node.slot}:${node.field.fieldName}` : undefined;
});
const currentUiTreeJson = computed(() => JSON.stringify(state.toManagementUiTree()));
const hasUnsavedChanges = computed(() =>
  Boolean(revision.value?.id && savedUiTreeJson.value !== currentUiTreeJson.value),
);
useWorkspaceViewUnsavedState('页面配置', () => hasUnsavedChanges.value);
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
  if (node.kind === 'relation') return `ui:relation:form:${node.relation?.id}`;
  if (node.kind === 'relationField')
    return `ui:relation-field:form:${node.relation?.id}:${node.relationField?.id}`;
  if (node.kind === 'group') return `ui:group:form:${node.group?.id}`;
  if (node.kind === 'groupField') return `ui:group-field:form:${node.group?.id}:${node.field?.id}`;
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
const metadataTreeNodes = computed<UiTreeNode[]>(() => [
  {
    key: 'metadata:root',
    title: mainEntityTitle.value,
    secondary: '主元数据',
    children: [
      ...visibleFields.value.map((field) => ({
        key: `metadata:field:${field.id}`,
        title: field.title,
        secondary: field.fieldName,
        isLeaf: true,
      })),
      ...childRelationNodes(relation.value?.metadataId),
    ],
  },
]);
watch(selectedField, (field) => {
  propertyDraft.value = { ...(field?.properties ?? {}) };
});

watch(selectedQuickSearch, (selected) => {
  if (selected) quickSearchPlaceholderDraft.value = state.quickSearchPlaceholder.value ?? '';
});

// Older transient drag sessions could place one form field in more than one group.  The editor
// repairs that impossible state before previewing, keeping the UI tree and server descriptor aligned.
watch([state.formFields, state.formGroups], () => state.normalizeFormFieldPlacements(), { immediate: true });

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

onBeforeUnmount(() => {
  workspaceLoadSequence += 1;
  resetPreviewDescriptor();
});

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
function pageClient(moduleAlias = props.moduleAlias) {
  return createStaticResourceCrudClient<PageDefinition>(
    moduleContext.http,
    `/platform.module/${encodeURIComponent(moduleAlias)}/pages`,
  );
}

function variantClient(moduleAlias: string, pageId: string) {
  return createStaticResourceCrudClient<PresentationVariant>(
    moduleContext.http,
    `/platform.module/${encodeURIComponent(moduleAlias)}/pages/${encodeURIComponent(pageId)}/presentation-variants`,
  );
}

function revisionClient(variantId: string) {
  return createStaticResourceCrudClient<PresentationRevision>(
    moduleContext.http,
    `/platform.presentation-variant/${encodeURIComponent(variantId)}/revisions`,
  );
}

async function loadWorkspace() {
  const requestSequence = ++workspaceLoadSequence;
  const moduleAlias = props.moduleAlias;
  await loadMetadataTree(requestSequence, moduleAlias);
  if (requestSequence !== workspaceLoadSequence) return;
  await loadComposition(requestSequence, moduleAlias);
}

async function loadMetadataTree(requestSequence = workspaceLoadSequence, moduleAlias = props.moduleAlias) {
  loading.value = true;
  relation.value = undefined;
  metadataRelations.value = [];
  metadataFields.value = [];
  childMetadataFields.value = new Map();
  try {
    const relations = await loadAll<ModuleMetadataRelation>(
      `/platform.module/${encodeURIComponent(moduleAlias)}/metadata-relations/query`,
    );
    if (requestSequence !== workspaceLoadSequence) return;
    metadataRelations.value = relations;
    const main = relations.find((item) => item.relationRole === 'main' || item.relationRole === 'MAIN');
    relation.value = main;
    if (!main?.metadataId) return;
    const fields = await loadAll<MetadataField>(
      `/platform.metadata/${encodeURIComponent(main.metadataId)}/fields/query`,
    );
    if (requestSequence !== workspaceLoadSequence) return;
    metadataFields.value = fields
      .filter((field) => field.enabled !== false)
      .map(toComposerField)
      .filter((field): field is PageComposerField => field != null);
    const directChildren = relations.filter(
      (candidate) =>
        candidate.relationRole !== 'main' &&
        candidate.relationRole !== 'MAIN' &&
        candidate.parentMetadataId === main.metadataId &&
        Boolean(candidate.metadataId),
    );
    const childFieldEntries = await Promise.all(
      directChildren.map(async (child) => {
        const childFields = await loadAll<MetadataField>(
          `/platform.metadata/${encodeURIComponent(child.metadataId!)}/fields/query`,
        );
        return [
          child.id ?? child.metadataId!,
          childFields
            .filter((field) => field.enabled !== false)
            .map(toComposerField)
            .filter((field): field is PageComposerField => field != null),
        ] as const;
      }),
    );
    if (requestSequence !== workspaceLoadSequence) return;
    childMetadataFields.value = new Map(childFieldEntries);
  } catch (cause) {
    if (requestSequence === workspaceLoadSequence)
      presentPlatformError(cause, { source: 'page-composition', phase: 'load' });
  } finally {
    if (requestSequence === workspaceLoadSequence) loading.value = false;
  }
}

function childRelationNodes(parentMetadataId?: string): UiTreeNode[] {
  if (!parentMetadataId) return [];
  return metadataRelations.value
    .filter(
      (candidate) =>
        candidate.relationRole !== 'main' &&
        candidate.relationRole !== 'MAIN' &&
        candidate.parentMetadataId === parentMetadataId,
    )
    .map((candidate) => {
      const relationId = candidate.id ?? candidate.metadataId;
      const fields = relationId ? (childMetadataFields.value.get(relationId) ?? []) : [];
      return {
        key: `metadata:relation:${relationId}`,
        title: candidate.title ?? candidate.relationAlias ?? '子实体',
        secondary: '子实体 · 拖入详情创建关联列表',
        isLeaf: fields.length === 0,
        children: fields.map((field) => ({
          key: `metadata:relation-field:${relationId}:${field.id}`,
          title: field.title,
          secondary: field.fieldName,
          isLeaf: true,
        })),
      };
    });
}

async function loadComposition(requestSequence = workspaceLoadSequence, moduleAlias = props.moduleAlias) {
  if (requestSequence !== workspaceLoadSequence) return;
  resetPreviewDescriptor();
  page.value = undefined;
  variant.value = undefined;
  revision.value = undefined;
  publishedRevision.value = undefined;
  state.replaceFields({ list: [], form: [] });
  state.updateQuickSearchPlaceholder(undefined);
  savedUiTreeJson.value = undefined;
  try {
    const pages = await loadAllFromClient(pageClient(moduleAlias), [
      { fieldName: 'alias', operator: 'EQ', values: ['management'] },
    ]);
    if (requestSequence !== workspaceLoadSequence) return;
    page.value = pages[0];
    if (!page.value?.id) return;
    const variants = await loadAllFromClient(variantClient(moduleAlias, page.value.id), [
      { fieldName: 'clientType', operator: 'EQ', values: [pageCompositionTransport.webClient] },
      { fieldName: 'scopeType', operator: 'EQ', values: [pageCompositionTransport.globalScope] },
    ]);
    if (requestSequence !== workspaceLoadSequence) return;
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
    if (requestSequence !== workspaceLoadSequence) return;
    revision.value = latestRevision(drafts);
    publishedRevision.value = latestRevision(published);
    hydrateDraft(revision.value);
  } catch (cause) {
    if (requestSequence === workspaceLoadSequence)
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

function retryPreviewDescriptor() {
  if (previewLoading.value || !variant.value?.id || !revision.value?.id) return;
  schedulePreviewDescriptor();
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
        relations?: Array<{ relation?: string; title?: string; fields?: string[] }>;
        groups?: Array<{
          group?: string;
          title?: string;
          subtitle?: string;
          fields?: Array<string | { field?: string; props?: PageComposerFieldProperties }>;
        }>;
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
      relations: (tree.nodes?.find((node) => node.slot === 'form')?.relations ?? []).flatMap((entry) => {
        const relation = metadataRelations.value.find(
          (candidate) => candidate.relationAlias === entry.relation,
        );
        const relationCode = relation?.relationAlias ?? entry.relation;
        if (!relationCode) return [];
        return [
          {
            id: relation?.id ?? relationCode,
            relationCode,
            title: entry.title?.trim() || relation?.title || relation?.relationAlias || relationCode,
            fields: (entry.fields ?? []).flatMap((fieldName) => {
              const childField = relation
                ? childMetadataFields.value
                    .get(relation.id ?? relation.metadataId ?? '')
                    ?.find((candidate) => candidate.fieldName === fieldName)
                : undefined;
              return childField ? [{ ...childField }] : [];
            }),
          },
        ];
      }),
      groups: (tree.nodes?.find((node) => node.slot === 'form')?.groups ?? []).flatMap((entry) => {
        if (!entry.group || !entry.title) return [];
        return [
          {
            id: entry.group,
            groupCode: entry.group,
            title: entry.title,
            subtitle: entry.subtitle,
            fields: (entry.fields ?? [])
              .map(resolveField)
              .filter((field): field is PageComposerField => Boolean(field)),
          },
        ];
      }),
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
        await variantClient(props.moduleAlias, page.value.id).insert({
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
type PersistedUiRelation = { relation: string; title?: string; fields?: string[] };
type PersistedUiTree = {
  props?: { list?: { searchPlaceholder?: unknown } };
  nodes?: Array<{
    slot?: PageComposerSlot;
    fields?: Array<string | PersistedUiField>;
    relations?: PersistedUiRelation[];
  }>;
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
  const savedRelations = saved.relationsBySlot.get('form') ?? [];
  const currentRelations = current.relationsBySlot.get('form') ?? [];
  const savedRelationCodes = new Set(savedRelations.map((relation) => relation.relation));
  const currentRelationCodes = new Set(currentRelations.map((relation) => relation.relation));
  const relationsAdded = currentRelations.filter(
    (relation) => !savedRelationCodes.has(relation.relation),
  ).length;
  const relationsRemoved = savedRelations.filter(
    (relation) => !currentRelationCodes.has(relation.relation),
  ).length;
  const savedRelationsByCode = new Map(savedRelations.map((relation) => [relation.relation, relation]));
  const changedRelationFields = currentRelations.filter((relation) => {
    const savedRelation = savedRelationsByCode.get(relation.relation);
    return savedRelation && (savedRelation.fields ?? []).join('|') !== (relation.fields ?? []).join('|');
  }).length;
  if (added) changes.push(`新增 ${added} 个字段`);
  if (removed) changes.push(`移除 ${removed} 个字段`);
  if (relationsAdded) changes.push(`添加 ${relationsAdded} 个关联子表`);
  if (relationsRemoved) changes.push(`移除 ${relationsRemoved} 个关联子表`);
  if (changedRelationFields) changes.push(`调整 ${changedRelationFields} 个子表展示字段`);
  if (reordered) changes.push('调整字段顺序');
  if (propertiesChanged) changes.push(`修改 ${propertiesChanged} 项展示属性`);
  if (saved.quickSearchPlaceholder !== current.quickSearchPlaceholder) {
    changes.push('修改快速查询占位提示');
  }
  return changes.length ? changes : ['调整页面结构'];
}

function parsePersistedUiTree(treeJson: string | undefined) {
  const fieldsBySlot = new Map<PageComposerSlot, PersistedUiField[]>();
  const relationsBySlot = new Map<PageComposerSlot, PersistedUiRelation[]>();
  let quickSearchPlaceholder: string | undefined;
  if (!treeJson) return { fieldsBySlot, relationsBySlot, quickSearchPlaceholder };
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
      const relations = tree.nodes?.find((node) => node.slot === slot)?.relations ?? [];
      relationsBySlot.set(
        slot,
        relations.filter(
          (relation): relation is PersistedUiRelation =>
            typeof relation?.relation === 'string' && relation.relation.trim().length > 0,
        ),
      );
    }
  } catch {
    // A malformed persisted draft is still recoverable through the editor's empty local state.
  }
  return { fieldsBySlot, relationsBySlot, quickSearchPlaceholder };
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

function selectQuickAddTarget(slot: PageComposerSlot) {
  if (isMutating.value) return;
  selectedSlot.value = slot;
}

function addSelectedMetadataField(slot: PageComposerSlot) {
  const field = selectedMetadataField.value;
  if (!field || isMutating.value) return;
  selectedSlot.value = slot;
  state.addField(field, slot);
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

function addSelectedMetadataRelation() {
  const selectedRelation = selectedMetadataRelation.value;
  if (
    !selectedRelation ||
    isMutating.value ||
    !selectedRelation.relationAlias ||
    selectedRelation.parentMetadataId !== relation.value?.metadataId
  )
    return;
  state.addFormRelation({
    id: selectedRelation.id ?? selectedRelation.metadataId ?? selectedRelation.relationAlias,
    relationCode: selectedRelation.relationAlias,
    title: selectedRelation.title ?? selectedRelation.relationAlias,
    fields: [],
  });
}

function addSelectedChildMetadataField() {
  const selected = selectedChildMetadataField.value;
  if (
    !selected ||
    isMutating.value ||
    !selected.relation.relationAlias ||
    selected.relation.parentMetadataId !== relation.value?.metadataId
  )
    return;
  state.addFormRelationField(
    {
      id: selected.relation.id ?? selected.relation.metadataId ?? selected.relation.relationAlias,
      relationCode: selected.relation.relationAlias,
      title: selected.relation.title ?? selected.relation.relationAlias,
      fields: [],
    },
    selected.field,
  );
}

function handleMetadataDoubleClick(event: UiTreeDragEvent) {
  const field = fieldOfMetadataNode(event.node);
  if (field) addToSelectedSlot(field);
  else if (event.node.key.startsWith('metadata:relation-field:')) addSelectedChildMetadataField();
  else if (event.node.key.startsWith('metadata:relation:')) addSelectedMetadataRelation();
}

function selectUiTreeKey(key: string) {
  const parsed = parseUiNode(key);
  if (!parsed) return;
  if (parsed.kind === 'slot' || parsed.kind === 'fieldGroup') {
    selectNode({ id: `slot:${parsed.slot}`, kind: 'slot', title: slotTitle(parsed.slot), slot: parsed.slot });
    return;
  }
  if (parsed.kind === 'template') {
    selectNode({ id: 'template:list:quick-search', kind: 'template', title: '快速查询', slot: 'list' });
    return;
  }
  if (parsed.kind === 'relation') {
    const relation = state.formRelations.value.find((candidate) => candidate.id === parsed.relationId);
    if (relation)
      selectNode({
        id: `form:relation:${relation.id}`,
        kind: 'relation',
        title: relation.title,
        slot: 'form',
        relation,
      });
    return;
  }
  if (parsed.kind === 'group') {
    const group = state.formGroups.value.find((candidate) => candidate.id === parsed.groupId);
    if (group)
      selectNode({ id: `form:group:${group.id}`, kind: 'group', title: group.title, slot: 'form', group });
    return;
  }
  if (parsed.kind === 'groupField') {
    const group = state.formGroups.value.find((candidate) => candidate.id === parsed.groupId);
    const field = group?.fields.find((candidate) => candidate.id === parsed.fieldId);
    if (group && field)
      selectNode({
        id: `form:group:${group.id}:field:${field.id}`,
        kind: 'groupField',
        title: field.title,
        slot: 'form',
        group,
        field,
      });
    return;
  }
  if (parsed.kind === 'relationField') {
    const relation = state.formRelations.value.find((candidate) => candidate.id === parsed.relationId);
    const field = relation?.fields.find((candidate) => candidate.id === parsed.fieldId);
    if (relation && field)
      selectNode({
        id: `form:relation:${relation.id}:field:${field.id}`,
        kind: 'relationField',
        title: field.title,
        slot: 'form',
        relation,
        relationField: field,
      });
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
  const dataTransfer = (event.nativeEvent as DragEvent | undefined)?.dataTransfer;
  const payload = metadataDragPayload(event.node);
  if (!payload) return;
  activeMetadataDragPayload.value = payload;
  if (!dataTransfer) return;
  dataTransfer.effectAllowed = 'copy';
  const serialized = JSON.stringify(payload);
  dataTransfer.setData('application/x-muyun-page-composer', serialized);
  // text/plain keeps the payload available when a browser strips custom MIME types between tree components.
  dataTransfer.setData('text/plain', serialized);
}

function canDragMetadataNode(node: UiTreeNode) {
  return !isMutating.value && metadataDragPayload(node) != null;
}

function handleUiTreeDoubleClick(key: string) {
  selectUiTreeKey(key);
  if (['field', 'group', 'template'].includes(parseUiNode(key)?.kind ?? '')) openPropertyDrawer();
}

function reorderListField(fieldId: string, targetIndex: number) {
  if (!isMutating.value) state.moveField(fieldId, 'list', 'list', targetIndex);
}

function reorderFormField(fieldId: string, targetIndex: number) {
  if (!isMutating.value) state.moveField(fieldId, 'form', 'form', targetIndex);
}

function moveFormFieldToGroup(fieldId: string, groupId: string, targetIndex: number) {
  if (!isMutating.value) state.moveFormFieldToGroup(fieldId, groupId, targetIndex);
}

function moveGroupFieldToForm(groupId: string, fieldId: string, targetIndex: number) {
  if (!isMutating.value) state.moveGroupFieldToForm(groupId, fieldId, targetIndex);
}

function reorderGroupField(groupId: string, fieldId: string, targetIndex: number) {
  if (!isMutating.value) state.moveGroupField(groupId, fieldId, targetIndex);
}

function moveGroupFieldToGroup(
  sourceGroupId: string,
  fieldId: string,
  targetGroupId: string,
  targetIndex: number,
) {
  if (!isMutating.value) state.moveGroupFieldToGroup(sourceGroupId, fieldId, targetGroupId, targetIndex);
}

function reorderGroup(groupId: string, targetIndex: number) {
  if (!isMutating.value) state.moveFormGroup(groupId, targetIndex);
}

function reorderRelationField(relationId: string, fieldId: string, targetIndex: number) {
  if (!isMutating.value) state.moveFormRelationField(relationId, fieldId, targetIndex);
}

function handleCompositionMetadataDrop(target: ComposerDropTarget, nativeEvent: DragEvent) {
  if (isMutating.value) return;
  const metadata = parseMetadataDragPayload(nativeEvent.dataTransfer) ?? activeMetadataDragPayload.value;
  if (!metadata) return;
  if (metadata.kind === 'field') {
    const field = metadataFields.value.find((candidate) => candidate.id === metadata.fieldId);
    if (!field) return;
    if (target.kind === 'list') state.addField(field, 'list');
    else if (target.kind === 'form') state.addField(field, 'form');
    else {
      state.addField(field, 'form');
      state.moveFormFieldToGroup(field.id, target.groupId);
    }
  } else if (metadata.kind === 'relation' && target.kind === 'form') {
    addRelationById(metadata.relationId);
  } else if (metadata.kind === 'relationField' && target.kind === 'form') {
    addRelationFieldById(metadata.relationId, metadata.fieldId);
  }
  activeMetadataDragPayload.value = undefined;
}

function handlePreviewMetadataDrop(target: 'list' | 'form', nativeEvent: DragEvent) {
  handleCompositionMetadataDrop({ kind: target }, nativeEvent);
}

type MetadataDragPayload =
  | { kind: 'field'; fieldId: string }
  | { kind: 'relation'; relationId: string }
  | { kind: 'relationField'; relationId: string; fieldId: string };

function metadataDragPayload(node: UiTreeNode): MetadataDragPayload | undefined {
  const mainField = fieldOfMetadataNode(node);
  if (mainField) return { kind: 'field', fieldId: mainField.id };
  const relationMatch = /^metadata:relation:(.+)$/.exec(node.key);
  if (relationMatch) return { kind: 'relation', relationId: relationMatch[1] };
  const childFieldMatch = /^metadata:relation-field:(.+):(.+)$/.exec(node.key);
  return childFieldMatch
    ? { kind: 'relationField', relationId: childFieldMatch[1], fieldId: childFieldMatch[2] }
    : undefined;
}

function parseMetadataDragPayload(dataTransfer?: DataTransfer | null): MetadataDragPayload | undefined {
  const raw =
    dataTransfer?.getData('application/x-muyun-page-composer') || dataTransfer?.getData('text/plain');
  if (!raw) return undefined;
  try {
    const payload = JSON.parse(raw) as MetadataDragPayload;
    if (payload.kind === 'field' && payload.fieldId) return payload;
    if (payload.kind === 'relation' && payload.relationId) return payload;
    if (payload.kind === 'relationField' && payload.relationId && payload.fieldId) return payload;
  } catch {
    // Ignore native drops whose text payload is not created by this composer.
  }
  return undefined;
}

function addRelationById(relationId: string) {
  const selected = metadataRelations.value.find(
    (candidate) => (candidate.id ?? candidate.metadataId) === relationId,
  );
  if (!selected?.relationAlias || selected.parentMetadataId !== relation.value?.metadataId) return;
  state.addFormRelation({
    id: selected.id ?? selected.metadataId ?? selected.relationAlias,
    relationCode: selected.relationAlias,
    title: selected.title ?? selected.relationAlias,
    fields: [],
  });
}

function addRelationFieldById(relationId: string, fieldId: string) {
  const selected = metadataRelations.value.find(
    (candidate) => (candidate.id ?? candidate.metadataId) === relationId,
  );
  const field = childMetadataFields.value.get(relationId)?.find((candidate) => candidate.id === fieldId);
  if (!selected?.relationAlias || !field || selected.parentMetadataId !== relation.value?.metadataId) return;
  state.addFormRelationField(
    {
      id: selected.id ?? selected.metadataId ?? selected.relationAlias,
      relationCode: selected.relationAlias,
      title: selected.title ?? selected.relationAlias,
      fields: [],
    },
    field,
  );
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
  | { kind: 'group'; groupId: string }
  | { kind: 'groupField'; groupId: string; fieldId: string }
  | { kind: 'template' }
  | { kind: 'relation'; relationId: string }
  | { kind: 'relationField'; relationId: string; fieldId: string }
  | { kind: 'field'; slot: PageComposerSlot; fieldId: string }
  | undefined {
  if (key === 'ui:root') return { kind: 'root' };
  if (key === 'ui:template:list:quick-search') return { kind: 'template' };
  const groupMatch = /^ui:group:form:(.+)$/.exec(key);
  if (groupMatch) return { kind: 'group', groupId: groupMatch[1] };
  const groupFieldMatch = /^ui:group-field:form:(.+):(.+)$/.exec(key);
  if (groupFieldMatch)
    return { kind: 'groupField', groupId: groupFieldMatch[1], fieldId: groupFieldMatch[2] };
  const relationMatch = /^ui:relation:form:(.+)$/.exec(key);
  if (relationMatch) return { kind: 'relation', relationId: relationMatch[1] };
  const relationFieldMatch = /^ui:relation-field:form:(.+):(.+)$/.exec(key);
  if (relationFieldMatch)
    return { kind: 'relationField', relationId: relationFieldMatch[1], fieldId: relationFieldMatch[2] };
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
  if (isMutating.value || !['list', 'query', 'detail', 'edit'].includes(key)) return;
  state.previewMode.value = key as typeof state.previewMode.value;
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

function canMoveSelectedRelationField(offset: -1 | 1) {
  const relation = selectedRelation.value;
  const field = selectedRelationField.value;
  if (!relation || !field) return false;
  const index = relation.fields.findIndex((candidate) => candidate.id === field.id);
  return index >= 0 && index + offset >= 0 && index + offset < relation.fields.length;
}

function moveSelectedRelationField(offset: -1 | 1) {
  const relation = selectedRelation.value;
  const field = selectedRelationField.value;
  if (isMutating.value || !relation || !field) return;
  const index = relation.fields.findIndex((candidate) => candidate.id === field.id);
  if (index < 0) return;
  state.moveFormRelationField(relation.id, field.id, index + offset);
}

function canMoveSelectedGroup(offset: -1 | 1) {
  const group = selectedGroupNode.value;
  if (!group) return false;
  const index = state.formGroups.value.findIndex((candidate) => candidate.id === group.id);
  return index >= 0 && index + offset >= 0 && index + offset < state.formGroups.value.length;
}

function moveSelectedGroup(offset: -1 | 1) {
  const group = selectedGroupNode.value;
  if (isMutating.value || !group) return;
  const index = state.formGroups.value.findIndex((candidate) => candidate.id === group.id);
  if (index < 0) return;
  state.moveFormGroup(group.id, index + offset);
}

function removeSelectedField() {
  if (isMutating.value) return;
  state.removeSelectedField();
}

function fieldDisplayTitle(field: PageComposerField) {
  return field.properties?.label ?? field.title;
}

function openPropertyDrawer() {
  if (isMutating.value || (!selectedField.value && !selectedQuickSearch.value && !selectedGroup.value))
    return;
  if (selectedQuickSearch.value) quickSearchPlaceholderDraft.value = state.quickSearchPlaceholder.value ?? '';
  else if (selectedField.value) propertyDraft.value = { ...(selectedField.value.properties ?? {}) };
  else if (selectedGroup.value) {
    groupTitleDraft.value = selectedGroup.value.title;
    groupSubtitleDraft.value = selectedGroup.value.subtitle ?? '';
  }
  propertyDrawerOpen.value = true;
}

function applyPropertyDraft() {
  if (isMutating.value) return;
  if (selectedQuickSearch.value) state.updateQuickSearchPlaceholder(quickSearchPlaceholderDraft.value);
  else if (selectedField.value) state.updateSelectedFieldProperties(propertyDraft.value);
  else if (selectedGroup.value)
    state.updateFormGroup(selectedGroup.value.id, groupTitleDraft.value, groupSubtitleDraft.value);
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
        search-placeholder="搜索字段"
        @refresh="loadMetadataTree"
      >
        <UiSpin v-if="loading" tip="加载主实体字段" />
        <UiEmpty v-else-if="!relation" description="页面编排仅面向已发布主元数据；当前模块暂无可编排主实体" />
        <div v-else class="metadata-tree" data-testid="page-composer-metadata-tree">
          <div class="metadata-tree__quick-add" aria-label="字段快速添加目标">
            <span>双击添加至</span>
            <UiButton
              size="small"
              :type="selectedSlot === 'list' ? 'primary' : 'default'"
              :disabled="isMutating"
              @click="selectQuickAddTarget('list')"
            >
              列表展示字段
            </UiButton>
            <UiButton
              size="small"
              :type="selectedSlot === 'form' ? 'primary' : 'default'"
              :disabled="isMutating"
              @click="selectQuickAddTarget('form')"
            >
              详情 / 表单字段
            </UiButton>
          </div>
          <UiTree
            v-model:expanded-keys="metadataExpandedKeys"
            :nodes="metadataTreeNodes"
            :selected-key="selectedMetadataTreeKey"
            :draggable="!isMutating"
            :native-drag-source="true"
            drag-payload-type="application/x-muyun-page-composer"
            :drag-payload-of="metadataDragPayload"
            :can-drag="canDragMetadataNode"
            :allow-drop="() => false"
            @select="selectMetadataNode"
            @drag-start="handleMetadataDragStart"
            @double-click="handleMetadataDoubleClick"
          />
          <div class="metadata-tree__selection" aria-live="polite">
            <span v-if="selectedMetadataField">已选：{{ fieldDisplayTitle(selectedMetadataField) }}</span>
            <span v-else-if="selectedChildMetadataField">
              已选子表字段：{{ fieldDisplayTitle(selectedChildMetadataField.field) }}
            </span>
            <span v-else-if="selectedMetadataRelation">已选子实体：{{ selectedMetadataRelation.title }}</span>
            <span v-else>选择字段后可快速添加；当前双击目标为：{{ quickAddTargetLabel }}</span>
            <div v-if="selectedMetadataField" class="metadata-tree__selection-actions">
              <UiButton size="small" :disabled="isMutating" @click="addSelectedMetadataField('list')">
                添加到列表
              </UiButton>
              <UiButton size="small" :disabled="isMutating" @click="addSelectedMetadataField('form')">
                添加到详情 / 表单
              </UiButton>
            </div>
            <div v-else-if="selectedMetadataRelation" class="metadata-tree__selection-actions">
              <UiButton size="small" :disabled="isMutating" @click="addSelectedMetadataRelation">
                添加关联子表到详情
              </UiButton>
            </div>
            <div v-else-if="selectedChildMetadataField" class="metadata-tree__selection-actions">
              <UiButton size="small" :disabled="isMutating" @click="addSelectedChildMetadataField">
                添加到
                {{
                  selectedChildMetadataField.relation.title ??
                  selectedChildMetadataField.relation.relationAlias
                }}
                子表
              </UiButton>
            </div>
          </div>
          <UiEmpty v-if="!visibleFields.length" description="暂无可编排字段" />
        </div>
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <ManagementExplorerColumn>
      <RecordExplorerPanel title="页面结构" :searchable="false">
        <div v-if="state.selectedNode.value" class="ui-tree__contextbar">
          <span>已选：{{ selectedFieldLabel }}</span>
          <div class="ui-tree__operations">
            <UiButton
              v-if="state.selectedNode.value?.slot === 'form'"
              size="small"
              :disabled="isMutating"
              @click="state.addFormGroup"
            >
              添加分组
            </UiButton>
            <UiButton size="small" :disabled="isMutating" @click="openPropertyDrawer">配置</UiButton>
            <template v-if="selectedField || (selectedRelation && !selectedRelationField)">
              <UiButton
                size="small"
                :disabled="isMutating || Boolean(selectedRelation) || !canMoveSelectedField(-1)"
                title="已在首位"
                @click="moveSelectedField(-1)"
              >
                上移
              </UiButton>
              <UiButton
                size="small"
                :disabled="isMutating || Boolean(selectedRelation) || !canMoveSelectedField(1)"
                title="已在末位"
                @click="moveSelectedField(1)"
              >
                下移
              </UiButton>
              <UiButton size="small" danger :disabled="isMutating" @click="removeSelectedField">
                移除
              </UiButton>
            </template>
            <template v-else-if="selectedGroupNode">
              <UiButton
                size="small"
                :disabled="isMutating || !canMoveSelectedGroup(-1)"
                title="已在首位"
                @click="moveSelectedGroup(-1)"
              >
                上移分组
              </UiButton>
              <UiButton
                size="small"
                :disabled="isMutating || !canMoveSelectedGroup(1)"
                title="已在末位"
                @click="moveSelectedGroup(1)"
              >
                下移分组
              </UiButton>
              <UiButton size="small" danger :disabled="isMutating" @click="removeSelectedField">
                移除分组
              </UiButton>
            </template>
            <template v-else-if="selectedRelationField && selectedRelation">
              <UiButton
                size="small"
                :disabled="isMutating || !canMoveSelectedRelationField(-1)"
                title="已在首位"
                @click="moveSelectedRelationField(-1)"
              >
                上移
              </UiButton>
              <UiButton
                size="small"
                :disabled="isMutating || !canMoveSelectedRelationField(1)"
                title="已在末位"
                @click="moveSelectedRelationField(1)"
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
          <PageCompositionTree
            :list-fields="state.listFields.value"
            :form-fields="state.formFields.value"
            :form-groups="state.formGroups.value"
            :form-relations="state.formRelations.value"
            :selected-key="selectedUiTreeKey"
            :disabled="isMutating"
            @select="selectUiTreeKey"
            @double-click="handleUiTreeDoubleClick"
            @reorder-list-field="reorderListField"
            @reorder-form-field="reorderFormField"
            @move-form-field-to-group="moveFormFieldToGroup"
            @move-group-field-to-form="moveGroupFieldToForm"
            @reorder-group-field="reorderGroupField"
            @move-group-field-to-group="moveGroupFieldToGroup"
            @reorder-group="reorderGroup"
            @reorder-relation-field="reorderRelationField"
            @metadata-drop="handleCompositionMetadataDrop"
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
      <div v-if="revision && hasUnsavedChanges" class="page-composition-status" aria-live="polite">
        未保存更改
      </div>
      <p v-if="hasUnsavedChanges" class="page-composition-change-summary">
        本次更改：{{ unsavedChangeSummary.join(' · ') }}
      </p>
      <UiTabs
        :active-key="state.previewMode.value"
        :tabs="previewTabs"
        @update:active-key="selectPreviewMode"
      />
      <div
        v-if="previewError"
        class="page-composition-preview-status page-composition-preview-status--error"
        aria-live="polite"
      >
        <span>草稿解析失败：{{ previewError }}</span>
        <span v-if="previewDescriptor">当前展示的是上一次成功解析结果，不代表当前草稿。</span>
        <UiButton size="small" :disabled="previewLoading" @click="retryPreviewDescriptor">
          重新解析
        </UiButton>
      </div>
      <PageCompositionDescriptorPreview
        v-if="previewDescriptor"
        :descriptor="previewDescriptor"
        :module-alias="props.moduleAlias"
        :mode="state.previewMode.value"
        :selected-field-name="selectedPreviewFieldName"
        :accept-external-drop="!isMutating"
        @select-field="(slot, fieldName) => selectDescriptorPreviewField(slot, fieldName)"
        @configure-field="(slot, fieldName) => selectDescriptorPreviewField(slot, fieldName, true)"
        @metadata-drop="handlePreviewMetadataDrop"
      />
      <UiEmpty
        v-else-if="revision && !previewLoading && !previewError"
        class="page-composition-preview-empty"
        :description="
          previewError ? '当前草稿未能解析；可重新解析，或修正页面结构后自动重试。' : '正在等待草稿解析结果。'
        "
      />
      <UiEmpty
        v-else-if="!revision"
        class="page-composition-preview-empty"
        description="初始化页面草稿后，即可查看页面预览。"
      />
    </RecordDetailPanel>

    <RecordDetailDrawer
      :open="propertyDrawerOpen"
      render-mode="inline"
      :title="propertyDrawerTitle"
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
      <div v-else-if="selectedGroup" class="component-property-drawer">
        <label>
          <span>分组标题</span>
          <UiInput v-model:value="groupTitleDraft" placeholder="例如：基本信息" />
        </label>
        <label>
          <span>辅助说明</span>
          <UiInput v-model:value="groupSubtitleDraft" placeholder="可选，例如：填写考试基础资料" />
        </label>
        <p>Group 是标准表单的语义分段；仅已拖入的字段会在该分组中显示。</p>
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
.metadata-tree__quick-add,
.metadata-tree__selection,
.metadata-tree__selection-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.metadata-tree__quick-add,
.metadata-tree__selection {
  flex: 0 0 auto;
  color: var(--muyun-text-muted);
  font-size: 12px;
  line-height: 1.5;
}
.metadata-tree__selection {
  justify-content: space-between;
  padding-top: 8px;
  border-top: 1px solid var(--muyun-border-subtle);
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
.page-composition-status {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  margin-bottom: 6px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--muyun-warning-surface, var(--muyun-surface-muted));
  color: var(--muyun-warning-text, var(--muyun-text));
  font-size: 12px;
  line-height: 20px;
}
.page-composition-change-summary {
  margin: 0 0 6px;
  color: var(--muyun-warning-text, var(--muyun-text-muted));
  font-size: 12px;
  line-height: 1.5;
}
.page-composition-preview-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
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
</style>
const groupMatch = /^ui:group:form:(.+)$/.exec(key); if (groupMatch) return { kind: 'group', groupId:
groupMatch[1] }; const groupFieldMatch = /^ui:group-field:form:(.+):(.+)$/.exec(key); if (groupFieldMatch)
return { kind: 'groupField', groupId: groupFieldMatch[1], fieldId: groupFieldMatch[2] };
