<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import {
  ManagementExplorerColumn,
  ManagementPanelHeader,
  ManagementWorkspace,
  RecordDetailDrawer,
  RecordDetailPanel,
  RecordExplorerPanel,
  presentPlatformError,
} from '@muyun/platform-components';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import {
  createStaticResourceCrudClient,
  normalizeError,
  platformErrorCodes,
  useModuleContext,
  type ModuleCrudClient,
} from '@muyun/web-core';
import {
  confirmAction,
  UiButton,
  UiEmpty,
  UiInput,
  UiSelect,
  UiSpin,
  UiSwitch,
  UiRadioGroup,
  UiTree,
  type UiRadioOption,
  type UiRecordInlineAction,
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
import {
  PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE,
  parseMetadataDragPayload,
  type MetadataDragPayload,
} from './pageCompositionDragPayload';

import {
  resolveCompositionPlacement,
  type PageCompositionStructure,
  type CompositionPlacementSource,
  type CompositionPlacementTarget,
} from './pageCompositionPlacement';

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
const previewStructure = ref<PageCompositionStructure>();
const previewLoading = ref(false);
const previewError = ref<string>();
/**
 * These fields are materialized for metadata governance but are intentionally omitted from the
 * dynamic main-entity runtime namespace. Keep this list aligned with
 * MetadataSystemFieldCatalog.isRuntimeReserved on the server; capability fields such as
 * `enabled` and `sortOrder` remain valid page fields and must not be filtered here.
 */
const runtimeReservedMetadataFieldNames = new Set([
  'id',
  'tenantId',
  'version',
  'deleted',
  'deletedAt',
  'deletedBy',
  'createdBy',
  'createdAt',
  'updatedBy',
  'updatedAt',
  'authUserId',
  'authAssigneeIds',
  'authMemberIds',
  'authOrganizationId',
  'authDepartmentId',
  'authModuleAlias',
]);
let previewRequestSequence = 0;
let previewDebounceTimer: ReturnType<typeof setTimeout> | undefined;
let workspaceLoadSequence = 0;
let metadataLoadSequence = 0;
const draftParseError = ref<string>();
const draftConflict = ref(false);
const compositionLoading = ref(false);
let compositionLoadSequence = 0;

const previewModes: UiRadioOption[] = [
  { value: 'list', label: '列表' },
  { value: 'detail', label: '详情' },
  { value: 'edit', label: '表单' },
];
const removedDraft = ref<{ before: string; after: string }>();
const propertyIssues = computed(() =>
  state.listFields.value.filter((field) => {
    const width = field.properties?.width?.trim();
    return width && !/^\d+(px|%)$/.test(width);
  }),
);
const visibleFields = computed(() => {
  const keyword = fieldKeyword.value.trim().toLowerCase();
  if (!keyword) return metadataFields.value;
  return metadataFields.value.filter(
    (field) => field.title.toLowerCase().includes(keyword) || field.fieldName.toLowerCase().includes(keyword),
  );
});
const selectedField = computed(() => state.selectedNode.value?.field);
const selectedRelation = computed(() => state.selectedNode.value?.relation);
const selectedRelationField = computed(() => state.selectedNode.value?.relationField);
const selectedGroup = computed(() => state.selectedNode.value?.group);
const selectedQuickSearch = computed(() => state.selectedNode.value?.id === 'template:list:quick-search');
const selectedFieldLabel = computed(() =>
  selectedQuickSearch.value
    ? '快速查询'
    : selectedField.value
      ? fieldDisplayTitle(selectedField.value)
      : selectedRelationField.value
        ? fieldDisplayTitle(selectedRelationField.value)
        : selectedGroup.value
          ? selectedGroup.value.title
          : (selectedRelation.value?.title ?? '组件'),
);
const propertyDrawerTitle = computed(() =>
  selectedQuickSearch.value ? '配置：快速查询占位提示' : `配置：${selectedFieldLabel.value}`,
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
const isMutating = computed(
  () => saving.value || publishing.value || loading.value || compositionLoading.value,
);
const unavailableSources = computed(() =>
  [
    ...state.listFields.value,
    ...state.formFields.value,
    ...state.formGroups.value.flatMap((group) => group.fields),
    ...state.formRelations.value.flatMap((relation) => relation.fields),
  ]
    .filter((field) => field.unavailable)
    .map((field) => field.fieldName)
    .concat(
      state.formRelations.value
        .filter((relation) => relation.unavailable)
        .map((relation) => relation.relationCode),
    ),
);
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
const composerTitle = computed(() => page.value?.title ?? props.moduleTitle ?? '页面编排');
const mainEntityTitle = computed(
  () => relation.value?.title ?? props.moduleTitle ?? relation.value?.relationAlias ?? '主实体',
);
const compositionSubtitle = computed(() => {
  if (!page.value) return '尚未初始化页面定义';
  return `Web · 全局 · ${revision.value ? `草稿 v${revision.value.revisionNo}` : '尚无可编辑草稿'} · 最近发布 ${publishedRevision.value ? `v${publishedRevision.value.revisionNo}` : '无'}`;
});
const metadataTreeNodes = computed<UiTreeNode[]>(() => [
  {
    key: 'metadata:root',
    title: mainEntityTitle.value,
    secondary: '主元数据',
    children: [
      ...visibleFields.value.map(
        (field): UiTreeNode => ({
          key: `metadata:field:${field.id}`,
          title: field.title,
          secondary: field.fieldName,
          actions: [
            {
              key: 'add',
              title: `添加 ${field.title} 到…`,
              iconName: 'plus',
              disabled: isMutating.value,
              items: [
                { key: 'add-list', title: '添加到列表' },
                { key: 'add-form', title: '添加到表单' },
              ],
            },
          ],
          isLeaf: true,
        }),
      ),
      ...childRelationNodes(relation.value?.metadataId),
    ],
  },
]);
watch(state.selectedNodeId, () => {
  propertyDraft.value = { ...(selectedField.value?.properties ?? {}) };
  quickSearchPlaceholderDraft.value = state.quickSearchPlaceholder.value ?? '';
  groupTitleDraft.value = selectedGroup.value?.title ?? '';
  groupSubtitleDraft.value = selectedGroup.value?.subtitle ?? '';
});
watch(currentUiTreeJson, (json) => {
  if (removedDraft.value && removedDraft.value.after !== json) removedDraft.value = undefined;
});

// Older transient drag sessions could place one form field in more than one group.  The editor
// repairs that impossible state before previewing, keeping the UI tree and server descriptor aligned.
watch([state.formFields, state.formGroups], () => state.normalizeFormFieldPlacements(), { immediate: true });

watch(
  () => props.moduleAlias,
  () => {
    resetPreviewDescriptor();
    relation.value = undefined;
    metadataRelations.value = [];
    metadataFields.value = [];
    childMetadataFields.value = new Map();
    revision.value = undefined;
    variant.value = undefined;
    draftParseError.value = undefined;
    draftConflict.value = false;
    removedDraft.value = undefined;
    saving.value = false;
    publishing.value = false;
    compositionLoading.value = false;
    propertyDrawerOpen.value = false;
    page.value = undefined;
    publishedRevision.value = undefined;
    state.replaceFields({ list: [], form: [] });
    state.updateQuickSearchPlaceholder(undefined);
    savedUiTreeJson.value = undefined;
    void loadWorkspace();
  },
  { immediate: true },
);

watch([currentUiTreeJson, unavailableSources, () => variant.value?.id, () => revision.value?.id], () =>
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
  const metadataSequence = ++metadataLoadSequence;
  const current = () =>
    requestSequence === workspaceLoadSequence && metadataSequence === metadataLoadSequence;
  loading.value = true;
  try {
    const relations = await loadAll<ModuleMetadataRelation>(
      `/platform.module/${encodeURIComponent(moduleAlias)}/metadata-relations/query`,
    );
    if (!current()) return;
    const main = relations.find((item) => item.relationRole === 'main' || item.relationRole === 'MAIN');
    const toFields = (fields: MetadataField[]) =>
      fields
        .filter((field) => field.enabled !== false && !isRuntimeReservedMetadataField(field))
        .map(toComposerField)
        .filter((field): field is PageComposerField => field != null);
    const fields = main?.metadataId
      ? await loadAll<MetadataField>(`/platform.metadata/${encodeURIComponent(main.metadataId)}/fields/query`)
      : [];
    if (!current()) return;
    const directChildren = relations.filter(
      (candidate) =>
        main?.metadataId && candidate.parentMetadataId === main.metadataId && Boolean(candidate.metadataId),
    );
    const childFieldEntries = await Promise.all(
      directChildren.map(
        async (child) =>
          [
            child.id ?? child.metadataId!,
            toFields(
              await loadAll<MetadataField>(
                `/platform.metadata/${encodeURIComponent(child.metadataId!)}/fields/query`,
              ),
            ),
          ] as const,
      ),
    );
    if (!current()) return;
    // Install a complete catalogue together. Refresh never empties the navigator or loses local edits.
    const treeJson = currentUiTreeJson.value;
    const selected = state.selectedNodeId.value;
    relation.value = main;
    metadataRelations.value = relations;
    metadataFields.value = toFields(fields);
    childMetadataFields.value = new Map(childFieldEntries);
    if (revision.value && !draftParseError.value) {
      hydrateDraft({ ...revision.value, uiTreeJson: treeJson }, false);
      if (state.nodes.value.some((node) => node.id === selected)) state.selectedNodeId.value = selected;
    }
  } catch (cause) {
    if (current()) presentPlatformError(cause, { source: 'page-composition', phase: 'load' });
  } finally {
    if (current()) loading.value = false;
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
        secondary: '子表',
        actions: [
          { key: 'add-form', title: '添加子表', iconName: 'plus' as const, disabled: isMutating.value },
        ],
        isLeaf: fields.length === 0,
        children: fields.map((field) => ({
          key: `metadata:relation-field:${relationId}:${field.id}`,
          title: field.title,
          secondary: field.fieldName,
          actions: [
            { key: 'add-form', title: '添加到子表', iconName: 'plus' as const, disabled: isMutating.value },
          ],
          isLeaf: true,
        })),
      };
    });
}

async function loadComposition(requestSequence = workspaceLoadSequence, moduleAlias = props.moduleAlias) {
  if (requestSequence !== workspaceLoadSequence) return;
  const sequence = ++compositionLoadSequence;
  const current = () => requestSequence === workspaceLoadSequence && sequence === compositionLoadSequence;
  compositionLoading.value = true;
  try {
    const pages = await loadAllFromClient(pageClient(moduleAlias), [
      { fieldName: 'alias', operator: 'EQ', values: ['management'] },
    ]);
    if (!current()) return;
    const nextPage = pages[0];
    const variants = nextPage?.id
      ? await loadAllFromClient(variantClient(moduleAlias, nextPage.id), [
          { fieldName: 'clientType', operator: 'EQ', values: [pageCompositionTransport.webClient] },
          { fieldName: 'scopeType', operator: 'EQ', values: [pageCompositionTransport.globalScope] },
        ])
      : [];
    if (!current()) return;
    const nextVariant = variants[0];
    const [drafts, published] = nextVariant?.id
      ? await Promise.all([
          loadAllFromClient(revisionClient(nextVariant.id), [
            { fieldName: 'status', operator: 'EQ', values: [pageCompositionTransport.draftRevision] },
          ]),
          loadAllFromClient(revisionClient(nextVariant.id), [
            { fieldName: 'status', operator: 'EQ', values: [pageCompositionTransport.publishedRevision] },
          ]),
        ])
      : [[], []];
    if (!current()) return;
    // Replace the working copy only after the complete snapshot arrives. A failed reload keeps local edits.
    resetPreviewDescriptor();
    page.value = nextPage;
    variant.value = nextVariant;
    revision.value = latestRevision(drafts);
    publishedRevision.value = latestRevision(published);
    draftConflict.value = false;
    removedDraft.value = undefined;
    draftParseError.value = undefined;
    state.replaceFields({ list: [], form: [] });
    state.updateQuickSearchPlaceholder(undefined);
    savedUiTreeJson.value = undefined;
    hydrateDraft(revision.value);
    propertyDrawerOpen.value = false;
  } catch (cause) {
    if (current()) presentPlatformError(cause, { source: 'page-composition', phase: 'load' });
  } finally {
    if (current()) compositionLoading.value = false;
  }
}

async function reloadComposition() {
  if (isMutating.value) return;
  const sequence = workspaceLoadSequence;
  if (hasUnsavedChanges.value) {
    const confirmed = await confirmAction({
      title: '加载最新草稿',
      content: '加载成功后将替换当前编排，并放弃尚未保存的本地修改。是否继续？',
      okText: '加载最新草稿',
    });
    if (!confirmed || sequence !== workspaceLoadSequence || isMutating.value) return;
  }
  await loadComposition();
}

function resetPreviewDescriptor() {
  previewRequestSequence += 1;
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = undefined;
  }
  previewDescriptor.value = undefined;
  previewStructure.value = undefined;
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
  if (unavailableSources.value.length || draftParseError.value || propertyIssues.value.length) {
    previewLoading.value = false;
    previewError.value =
      draftParseError.value ??
      (propertyIssues.value.length
        ? '请修正列宽格式后继续预览。'
        : '页面包含失效来源，请移除标记节点并重新选择可用字段。');
    return;
  }
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
    if (uiTreeJson !== currentUiTreeJson.value) return;
    previewStructure.value = {
      list: state.listFields.value,
      form: state.formFields.value,
      groups: state.formGroups.value,
      relations: state.formRelations.value,
    };
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

function hydrateDraft(current: PresentationRevision | undefined, markSaved = true) {
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
      if (!fieldName) return undefined;
      if (!source)
        return {
          id: `missing_${fieldName}`,
          title: fieldName,
          fieldName,
          unavailable: true,
          properties: typeof entry === 'string' ? undefined : entry.props,
        };
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
            unavailable: !relation,
            title: entry.title?.trim() || relation?.title || relation?.relationAlias || relationCode,
            fields: (entry.fields ?? []).flatMap((fieldName) => {
              const childField = relation
                ? childMetadataFields.value
                    .get(relation.id ?? relation.metadataId ?? '')
                    ?.find((candidate) => candidate.fieldName === fieldName)
                : undefined;
              return [
                {
                  ...(childField ?? {
                    id: `missing_${fieldName}`,
                    fieldName,
                    title: fieldName,
                    unavailable: true,
                  }),
                },
              ];
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
    if (markSaved) savedUiTreeJson.value = currentUiTreeJson.value;
    draftParseError.value = undefined;
  } catch {
    draftParseError.value = '草稿结构无法解析，当前内容不会覆盖已保存配置。请修复该修订后重新加载。';
  }
}

async function initializeComposition() {
  if (isMutating.value || !relation.value?.id) return;
  const sequence = workspaceLoadSequence;
  const current = () => sequence === workspaceLoadSequence;
  const moduleAlias = props.moduleAlias;
  saving.value = true;
  try {
    if (!page.value) {
      const createdPage = (
        await pageClient(moduleAlias).insert({
          alias: 'management',
          contractType: pageCompositionTransport.managementContract,
          mainRelationId: relation.value.id,
          title: `${props.moduleTitle ?? props.moduleAlias}管理页`,
          enabled: true,
        })
      ).record;
      if (!current()) return;
      page.value = createdPage;
    }
    if (!page.value?.id) return;
    if (!variant.value) {
      const createdVariant = (
        await variantClient(moduleAlias, page.value.id).insert({
          clientType: pageCompositionTransport.webClient,
          scopeType: pageCompositionTransport.globalScope,
          title: 'Web 全局呈现',
          enabled: true,
        })
      ).record;
      if (!current()) return;
      variant.value = createdVariant;
    }
    if (!variant.value?.id || revision.value) return;
    const revisions = await loadAllFromClient(revisionClient(variant.value.id));
    if (!current()) return;
    const latestPublished = latestRevision(
      revisions.filter((item) => item.status === pageCompositionTransport.publishedRevision),
    );
    const treeJsonToPersist = latestPublished?.uiTreeJson ?? currentUiTreeJson.value;
    const createdRevision = (
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
    if (!current()) return;
    revision.value = createdRevision;
    if (latestPublished) publishedRevision.value = latestPublished;
    hydrateDraft(revision.value);
  } catch (cause) {
    if (!current()) return;
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
  } finally {
    if (current()) saving.value = false;
  }
}

async function saveDraft(
  allowDuringPublish = false,
  treeJsonToPersist = currentUiTreeJson.value,
): Promise<boolean> {
  if (
    draftParseError.value ||
    propertyIssues.value.length > 0 ||
    draftConflict.value ||
    compositionLoading.value ||
    loading.value ||
    saving.value ||
    (!allowDuringPublish && publishing.value) ||
    !revision.value?.id ||
    !variant.value?.id
  )
    return false;
  const sequence = workspaceLoadSequence;
  const variantId = variant.value.id;
  const candidate = revision.value;
  const current = () => sequence === workspaceLoadSequence;
  saving.value = true;
  try {
    const result = await revisionClient(variantId).update(candidate.id!, {
      ...candidate,
      uiTreeJson: treeJsonToPersist,
    });
    if (!current()) return false;
    revision.value = result.record;
    savedUiTreeJson.value = treeJsonToPersist;
    removedDraft.value = undefined;
    return true;
  } catch (cause) {
    if (!current()) return false;
    if (normalizeError(cause).code === platformErrorCodes.conflictVersion) draftConflict.value = true;
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
    return false;
  } finally {
    if (current()) saving.value = false;
  }
}

async function publishDraft() {
  if (
    isMutating.value ||
    propertyIssues.value.length > 0 ||
    draftConflict.value ||
    unavailableSources.value.length ||
    draftParseError.value ||
    !revision.value?.id
  )
    return;
  const sequence = workspaceLoadSequence;
  const current = () => sequence === workspaceLoadSequence;
  const variantId = variant.value?.id;
  if (!variantId) return;
  const confirmed = await confirmAction({
    title: '发布页面修订',
    content: `将发布“${page.value?.title ?? '管理页'}”的草稿 v${revision.value.revisionNo}，目标为 Web · 全局，模板为 ${revision.value.templateAlias ?? 'management'} v${revision.value.templateVersion ?? 1}。发布会先保存并校验页面结构，随后替换该目标当前的已发布修订。是否继续？`,
    okText: '确认发布',
  });
  if (!confirmed || !current() || isMutating.value) return;
  const treeJsonToPublish = currentUiTreeJson.value;
  publishing.value = true;
  try {
    if (!(await saveDraft(true, treeJsonToPublish)) || !current()) return;
    const publicationCandidate = revision.value;
    await moduleContext.http.request<number>({
      method: 'POST',
      path: `/platform.presentation_publish/revisions/${encodeURIComponent(publicationCandidate.id!)}/publish`,
    });
    if (current()) {
      // A successful publication makes this revision immutable, even if the following read fails.
      publishedRevision.value = {
        ...publicationCandidate,
        status: pageCompositionTransport.publishedRevision,
      };
      revision.value = undefined;
      removedDraft.value = undefined;
      propertyDrawerOpen.value = false;
    }
    try {
      const nextDraft = await createFollowUpDraft(variantId, publicationCandidate, treeJsonToPublish);
      if (current()) {
        revision.value = nextDraft;
        hydrateDraft(nextDraft);
      }
    } catch {
      if (!current()) return;
      await loadComposition();
      if (!current()) return;
      presentPlatformError(
        new Error(
          `草稿 v${publicationCandidate.revisionNo ?? 1} 已发布，但未能生成后续草稿；请基于最近发布修订重新创建草稿。`,
        ),
        { source: 'page-composition', phase: 'action' },
      );
      return;
    }
    if (current()) await loadComposition();
  } catch (cause) {
    if (!current()) return;
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
  } finally {
    if (current()) publishing.value = false;
  }
}

async function discardUnsavedChanges() {
  if (!revision.value || !hasUnsavedChanges.value || isMutating.value) return;
  const sequence = workspaceLoadSequence;
  const confirmed = await confirmAction({
    title: '放弃本次更改',
    content: `将撤销当前草稿 v${revision.value.revisionNo} 尚未保存的本地调整，已保存的草稿内容不会受影响。是否继续？`,
    okText: '放弃更改',
  });
  if (!confirmed || sequence !== workspaceLoadSequence || isMutating.value) return;
  hydrateDraft(revision.value);
  removedDraft.value = undefined;
  propertyDrawerOpen.value = false;
}

/** Keeps a stable editable working copy after an immutable revision becomes published. */
async function createFollowUpDraft(
  variantId: string,
  publishedRevision: PresentationRevision,
  uiTreeJson: string,
) {
  const revisions = await loadAllFromClient(revisionClient(variantId));
  return (
    await revisionClient(variantId).insert({
      revisionNo: Math.max(0, ...revisions.map((item) => item.revisionNo ?? 0)) + 1,
      templateAlias: publishedRevision.templateAlias ?? 'management',
      templateVersion: publishedRevision.templateVersion ?? 1,
      uiTreeJson,
      status: pageCompositionTransport.draftRevision,
      title: `基于 v${publishedRevision.revisionNo ?? 1} 的草稿`,
      enabled: true,
    })
  ).record;
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
    groups?: Array<{ fields?: Array<string | PersistedUiField> }>;
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
      const node = tree.nodes?.find((node) => node.slot === slot);
      const fields = [
        ...(node?.fields ?? []),
        ...(node?.groups ?? []).flatMap((group) => group.fields ?? []),
      ];
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

function isRuntimeReservedMetadataField(field: MetadataField) {
  return field.systemManaged === true && runtimeReservedMetadataFieldNames.has(field.fieldName ?? '');
}

function slotTitle(slot: PageComposerSlot) {
  return slot === 'list' ? '列表' : '详情 / 表单';
}
function fieldsInSlot(slot: PageComposerSlot) {
  return slot === 'list' ? state.listFields.value : state.formFields.value;
}
function selectMetadataNode(node: UiTreeNode) {
  if (!isMutating.value) selectedMetadataTreeKey.value = node.key;
}
function addMetadataNode(action: UiRecordInlineAction, node: UiTreeNode) {
  if (isMutating.value || action.disabled) return;
  const payload = metadataDragPayload(node);
  if (payload) handleCompositionMetadataDrop({ kind: action.key === 'add-list' ? 'list' : 'form' }, payload);
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

function handleCompositionMetadataDrop(target: ComposerDropTarget, payload: unknown) {
  if (isMutating.value) return;
  const metadata = parseMetadataDragPayload(payload);
  if (!metadata) return;
  if (metadata.kind === 'field') {
    const field = metadataFields.value.find((candidate) => candidate.id === metadata.fieldId);
    if (!field || target.kind === 'relation') return;
    if (target.kind === 'group') placeMetadataFieldInGroup(field, target.groupId, target.index);
    else {
      const slot = target.kind;
      const sourceGroup =
        slot === 'form' &&
        state.formGroups.value.find((group) => group.fields.some((item) => item.id === field.id));
      if (sourceGroup && target.index !== undefined)
        state.moveGroupFieldToForm(sourceGroup.id, field.id, target.index);
      else {
        state.addField(field, slot, target.index);
        if (target.index !== undefined) state.moveField(field.id, slot, slot, target.index);
      }
    }
  } else if (metadata.kind === 'relation' && target.kind === 'form') {
    addRelationById(metadata.relationId);
  } else if (
    metadata.kind === 'relationField' &&
    (target.kind === 'form' || (target.kind === 'relation' && target.relationId === metadata.relationId))
  ) {
    addRelationFieldById(metadata.relationId, metadata.fieldId);
    if (target.index !== undefined)
      state.moveFormRelationField(metadata.relationId, metadata.fieldId, target.index);
  }
}

/**
 * A metadata field is a source that may be projected once in the form slot. Dropping it onto a
 * group therefore means "place it here": use the existing typed move commands when it already
 * has a form placement, and only add it before moving when it is new to the form.
 */
function placeMetadataFieldInGroup(field: PageComposerField, groupId: string, targetIndex?: number) {
  if (!state.formGroups.value.some((group) => group.id === groupId)) return;
  const sourceGroup = state.formGroups.value.find((group) =>
    group.fields.some((candidate) => candidate.id === field.id),
  );
  if (sourceGroup) {
    if (sourceGroup.id !== groupId)
      state.moveGroupFieldToGroup(sourceGroup.id, field.id, groupId, targetIndex);
    else if (targetIndex !== undefined) state.moveGroupField(groupId, field.id, targetIndex);
    else state.addField(field, 'form');
    return;
  }
  if (state.formFields.value.some((candidate) => candidate.id === field.id)) {
    state.moveFormFieldToGroup(field.id, groupId, targetIndex);
    return;
  }
  state.addField(field, 'form');
  state.moveFormFieldToGroup(field.id, groupId, targetIndex);
}

function handlePreviewPlacement(source: CompositionPlacementSource, target: CompositionPlacementTarget) {
  if (isMutating.value || previewLoading.value || previewError.value) return;
  const model = {
    list: state.listFields.value,
    form: state.formFields.value,
    groups: state.formGroups.value,
    relations: state.formRelations.value,
  };
  const placement = resolveCompositionPlacement(model, source, target);
  if (!placement) return;
  const { container, index } = placement;
  const mode = state.previewMode.value;
  if (source.kind === 'metadata') {
    const metadata = source.metadata;
    if (container.kind === 'list' || container.kind === 'form' || container.kind === 'group') {
      handleCompositionMetadataDrop({ ...container, index }, metadata);
    } else if (container.kind === 'relation' && metadata.kind === 'relationField') {
      addRelationFieldById(metadata.relationId, metadata.fieldId);
      state.moveFormRelationField(metadata.relationId, metadata.fieldId, index);
    } else if (container.kind === 'relations' && metadata.kind !== 'field') {
      if (metadata.kind === 'relation') addRelationById(metadata.relationId);
      else addRelationFieldById(metadata.relationId, metadata.fieldId);
      state.moveFormRelation(metadata.relationId, index);
    }
  } else {
    const from = source.container;
    if (container.kind === 'groups') state.moveFormGroup(source.nodeId, index);
    else if (container.kind === 'relations') state.moveFormRelation(source.nodeId, index);
    else if (container.kind === 'relation')
      state.moveFormRelationField(container.relationId, source.nodeId, index);
    else if (container.kind === 'group') {
      if (from.kind === 'group')
        state.moveGroupFieldToGroup(from.groupId, source.nodeId, container.groupId, index);
      else state.moveFormFieldToGroup(source.nodeId, container.groupId, index);
    } else if (container.kind === 'form' && from.kind === 'group')
      state.moveGroupFieldToForm(from.groupId, source.nodeId, index);
    else if (container.kind === 'list' || container.kind === 'form')
      state.moveField(source.nodeId, container.kind, container.kind, index);
  }
  // Detail and form are views of one form slot. A placement must not change the user's view.
  state.previewMode.value = mode;
}

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
}

function selectDescriptorPreviewField(slot: PageComposerSlot, fieldName: string, configure = false) {
  if (isMutating.value) return;
  const node = state.nodes.value.find(
    (candidate) => candidate.slot === slot && candidate.field?.fieldName === fieldName,
  );
  if (!node) return;
  // Selecting within the preview must keep that surface mounted between the two clicks.
  const mode = state.previewMode.value;
  selectNode(node);
  state.previewMode.value = mode;
  if (configure) openPropertyDrawer();
}

function selectPreviewMode(key: string) {
  if (isMutating.value || !['list', 'query', 'detail', 'edit'].includes(key)) return;
  state.previewMode.value = key as typeof state.previewMode.value;
}

function handleNodeAction(action: 'configure' | 'remove' | 'add-group', key: string) {
  if (isMutating.value) return;
  if (action === 'add-group') {
    state.addFormGroup();
    openPropertyDrawer();
    return;
  }
  selectUiTreeKey(key);
  if (selectedUiTreeKey.value !== key) return;
  if (action === 'configure') {
    openPropertyDrawer();
    return;
  }
  const before = currentUiTreeJson.value;
  state.removeSelectedField();
  propertyDrawerOpen.value = false;
  const after = currentUiTreeJson.value;
  if (before !== after) removedDraft.value = { before, after };
}
function undoRemoval() {
  if (isMutating.value || !removedDraft.value) return;
  const before = removedDraft.value.before;
  removedDraft.value = undefined;
  hydrateDraft({ uiTreeJson: before }, false);
}
function updateFieldProperty<K extends keyof PageComposerFieldProperties>(
  key: K,
  value: PageComposerFieldProperties[K],
) {
  if (isMutating.value) return;
  propertyDraft.value = { ...propertyDraft.value, [key]: value };
  state.updateSelectedFieldProperties(propertyDraft.value);
}
function updateQuickSearch(value: string) {
  if (isMutating.value) return;
  quickSearchPlaceholderDraft.value = value;
  state.updateQuickSearchPlaceholder(value);
}
function updateGroup(title: string, subtitle: string) {
  if (isMutating.value || !selectedGroup.value) return;
  groupTitleDraft.value = title;
  groupSubtitleDraft.value = subtitle;
  state.updateFormGroup(selectedGroup.value.id, title, subtitle);
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
</script>

<template>
  <section class="page-composition-workspace">
    <ManagementPanelHeader :title="composerTitle" :subtitle="compositionSubtitle">
      <template #actions>
        <div class="page-composition-actions">
          <UiButton v-if="removedDraft" :disabled="isMutating" @click="undoRemoval">撤销移除</UiButton>
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
              :disabled="isMutating || draftConflict || propertyIssues.length > 0 || !hasUnsavedChanges"
              @click="() => void saveDraft()"
            >
              保存草稿
            </UiButton>
            <UiButton v-if="hasUnsavedChanges" :disabled="isMutating" @click="discardUnsavedChanges">
              放弃本次更改
            </UiButton>
            <UiButton
              type="primary"
              :loading="publishing"
              :disabled="
                isMutating ||
                draftConflict ||
                propertyIssues.length > 0 ||
                unavailableSources.length > 0 ||
                Boolean(draftParseError)
              "
              @click="publishDraft"
            >
              发布草稿
            </UiButton>
          </template>
        </div>
      </template>
    </ManagementPanelHeader>
    <ManagementWorkspace class="page-composition-workspace__body" layout="composer" :explorer-count="2">
      <ManagementExplorerColumn collapsible title="可用字段">
        <RecordExplorerPanel
          v-model:search-keyword="fieldKeyword"
          title="可用字段"
          search-placeholder="搜索字段"
          :refresh-disabled="isMutating"
          @refresh="loadMetadataTree"
        >
          <UiSpin v-if="loading && !relation" tip="加载主实体字段" />
          <UiEmpty
            v-else-if="!relation"
            description="页面编排仅面向已发布主元数据；当前模块暂无可编排主实体"
          />
          <div v-else class="metadata-tree" data-testid="page-composer-metadata-tree">
            <UiTree
              v-model:expanded-keys="metadataExpandedKeys"
              :nodes="metadataTreeNodes"
              :selected-key="selectedMetadataTreeKey"
              :draggable="!isMutating"
              :drag-operations="['copy']"
              :drag-payload-type="PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE"
              :drag-payload-of="metadataDragPayload"
              :can-drag="canDragMetadataNode"
              :allow-drop="() => false"
              @select="selectMetadataNode"
              @action="addMetadataNode"
            />
            <UiEmpty v-if="!visibleFields.length" description="暂无可编排字段" />
          </div>
        </RecordExplorerPanel>
      </ManagementExplorerColumn>

      <ManagementExplorerColumn collapsible title="页面结构">
        <RecordExplorerPanel
          title="页面结构"
          :searchable="false"
          :refresh-disabled="isMutating"
          @refresh="reloadComposition"
        >
          <div class="ui-tree" data-testid="page-composer-ui-tree">
            <PageCompositionTree
              :list-fields="state.listFields.value"
              :form-fields="state.formFields.value"
              :form-groups="state.formGroups.value"
              :form-relations="state.formRelations.value"
              :selected-key="selectedUiTreeKey"
              :disabled="isMutating"
              @node-action="handleNodeAction"
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

      <RecordDetailPanel title="预览">
        <div v-if="propertyIssues.length" role="alert" class="page-composition-source-error">
          列宽格式有误，请修正后保存：
          <UiButton
            v-for="field in propertyIssues"
            :key="field.id"
            size="small"
            @click="handleNodeAction('configure', `ui:field:list:${field.id}`)"
          >
            {{ fieldDisplayTitle(field) }}
          </UiButton>
        </div>
        <div v-if="draftConflict" class="page-composition-conflict" role="alert">
          <span>草稿已被其他会话更新，本地修改已保留。请加载最新草稿后继续编辑。</span>
          <UiButton :disabled="isMutating" @click="reloadComposition">加载最新草稿</UiButton>
        </div>
        <p
          v-if="unavailableSources.length || draftParseError"
          class="page-composition-source-error"
          role="alert"
        >
          {{
            draftParseError ??
            `来源失效：${[...new Set(unavailableSources)].join('、')}。配置已保留，请在编排树中移除标记节点并重新选择；修正后才能发布。`
          }}
        </p>
        <div v-if="revision && hasUnsavedChanges" class="page-composition-status" aria-live="polite">
          未保存更改
        </div>
        <p v-if="hasUnsavedChanges" class="page-composition-change-summary">
          本次更改：{{ unsavedChangeSummary.join(' · ') }}
        </p>
        <UiRadioGroup
          :value="state.previewMode.value === 'query' ? 'list' : state.previewMode.value"
          :options="previewModes"
          :disabled="isMutating"
          @update:value="selectPreviewMode"
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
          :mode="state.previewMode.value === 'query' ? 'list' : state.previewMode.value"
          :selected-field-name="selectedPreviewFieldName"
          :accept-external-drop="true"
          :placement-disabled="isMutating || previewLoading || Boolean(previewError)"
          :structure="previewStructure"
          @select-field="(slot, fieldName) => selectDescriptorPreviewField(slot, fieldName)"
          @configure-field="(slot, fieldName) => selectDescriptorPreviewField(slot, fieldName, true)"
          @placement-drop="handlePreviewPlacement"
        />
        <UiEmpty
          v-else-if="revision && !previewLoading && !previewError"
          class="page-composition-preview-empty"
          :description="
            previewError
              ? '当前草稿未能解析；可重新解析，或修正页面结构后自动重试。'
              : '正在等待草稿解析结果。'
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
        subtitle="修改即时更新草稿；保存后保留，发布后生效。"
        :width="420"
        @close="propertyDrawerOpen = false"
      >
        <div v-if="selectedQuickSearch" class="component-property-drawer">
          <label>
            <span>搜索占位提示</span>
            <UiInput
              :value="quickSearchPlaceholderDraft"
              :disabled="isMutating"
              placeholder="例如：搜索名称、编码或 ID"
              @update:value="updateQuickSearch"
            />
          </label>
        </div>
        <div v-else-if="selectedField" class="component-property-drawer">
          <label>
            <span>展示标题</span>
            <UiInput
              :value="propertyDraft.label"
              :disabled="isMutating"
              :placeholder="selectedField.title"
              @update:value="updateFieldProperty('label', $event)"
            />
          </label>
          <template v-if="state.selectedNode.value?.slot === 'list'">
            <label>
              <span>列宽</span>
              <UiInput
                :value="propertyDraft.width"
                :disabled="isMutating"
                placeholder="例如 160px 或 25%"
                @update:value="updateFieldProperty('width', $event)"
              />
              <small v-if="propertyValidationMessage" class="component-property-drawer__error">
                {{ propertyValidationMessage }}
              </small>
            </label>
            <label>
              <span>对齐</span>
              <UiSelect
                :value="propertyDraft.align"
                :disabled="isMutating"
                :options="[
                  { label: '左对齐', value: 'left' },
                  { label: '居中', value: 'center' },
                  { label: '右对齐', value: 'right' },
                ]"
                placeholder="遵循平台默认"
                @update:value="
                  updateFieldProperty(
                    'align',
                    $event === 'left' || $event === 'center' || $event === 'right' ? $event : undefined,
                  )
                "
              />
            </label>
          </template>
          <template v-else>
            <label>
              <span>表单列宽度</span>
              <UiSelect
                :value="propertyDraft.columnSpan"
                :disabled="isMutating"
                :options="[
                  { label: '半行（1 列）', value: 1 },
                  { label: '整行（2 列）', value: 2 },
                ]"
                placeholder="遵循平台默认"
                @update:value="
                  updateFieldProperty('columnSpan', $event === 1 || $event === 2 ? $event : undefined)
                "
              />
            </label>
            <label class="component-property-drawer__switch">
              <span>只读展示</span>
              <UiSwitch
                :checked="propertyDraft.readOnly"
                :disabled="isMutating"
                @update:checked="updateFieldProperty('readOnly', $event)"
              />
            </label>
          </template>
        </div>
        <div v-else-if="selectedGroup" class="component-property-drawer">
          <label>
            <span>分组标题</span>
            <UiInput
              :value="groupTitleDraft"
              :disabled="isMutating"
              placeholder="例如：基本信息"
              @update:value="updateGroup($event, groupSubtitleDraft)"
            />
            <small v-if="!groupTitleDraft.trim()" class="component-property-drawer__error"
              >标题不能为空，当前保留原名称。</small
            >
          </label>
          <label>
            <span>辅助说明</span>
            <UiInput
              :value="groupSubtitleDraft"
              :disabled="isMutating"
              placeholder="可选，例如：填写考试基础资料"
              @update:value="updateGroup(groupTitleDraft, $event)"
            />
          </label>
        </div>
      </RecordDetailDrawer>
    </ManagementWorkspace>
  </section>
</template>

<style scoped>
.page-composition-conflict {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  color: var(--muyun-danger-base);
}
.page-composition-source-error {
  color: var(--muyun-danger-base);
}
.page-composition-workspace {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  gap: 10px;
}
.page-composition-workspace__body {
  flex: 1 1 auto;
  min-height: 0;
}
.page-composition-workspace > :deep(.management-panel-header) {
  flex: 0 0 auto;
}

.metadata-tree,
.ui-tree {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
  overflow: auto;
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
/* Keep node names legible beside stable inline action slots in the compact composer. */
.metadata-tree :deep(.ant-tree-indent-unit),
.ui-tree :deep(.ant-tree-indent-unit) {
  width: 16px;
}
.metadata-tree :deep(.ui-record-explorer-item-title),
.ui-tree :deep(.ui-record-explorer-item-title) {
  flex-shrink: 0;
}
.metadata-tree :deep(.ui-record-explorer-item-secondary),
.ui-tree :deep(.ui-record-explorer-item-secondary) {
  flex-shrink: 4;
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
  color: var(--muyun-danger-base);
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
  color: var(--muyun-danger-base);
  font-size: 12px;
  line-height: 1.4;
}
</style>
