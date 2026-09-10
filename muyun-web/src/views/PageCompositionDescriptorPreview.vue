<script setup lang="ts">
import { pageActionEntryTitle, pageActionEntryVisible } from '@muyun/web-core';
import { computed, onBeforeUnmount, onBeforeUpdate, onUpdated, ref, watch } from 'vue';
import {
  RecordFormFields,
  RecordFormGrid,
  RecordContentSectionHeading,
  RecordDetailExtensionSection,
  RecordRelationTable,
  RecordRelationValue,
  resolveRecordFormFieldState,
  RecordDetailFields,
  RecordQueryListCell,
  RecordQueryListSurface,
  QueryGroupedSummary,
  defaultActionIcon,
  resolveRecordDetailFields,
  resolveRecordFormFields,
  resolveRecordQueryListColumns,
} from '@muyun/platform-components';
import {
  UiActionButton,
  UiButton,
  UiEmpty,
  type UiDataTableColumn,
  type UiDataTableRecord,
} from '@muyun/vue-ui-antdv';
import type { ModuleRuntimeAction } from '@muyun/web-core';
import type {
  ResolvedDetailRelationDescriptor,
  ResolvedModuleUiDescriptor,
  ResolvedViewFieldDescriptor,
} from '@muyun/web-contracts';
import type {
  QueryListRecord,
  RecordFormFieldDescriptor,
  RecordFormFieldValue,
  RecordFormRecord,
} from '@muyun/platform-components';
import {
  containerKey,
  type CompositionPlacementSource,
  type CompositionPlacementTarget,
  type PageCompositionStructure,
  type CompositionContainer,
} from './pageCompositionPlacement';
import {
  orderedFormItems,
  type PageComposerFormItem,
  type PageQuerySummary,
} from './pageCompositionDraftState';
import { canPlaceActionInAnchor, type PageCompositionActionPlacement } from './pageCompositionMode';
import { usePageCompositionPreviewDrag, type PreviewPlacementEntry } from './usePageCompositionPreviewDrag';
import { usePageCompositionActionPreviewDrag } from './usePageCompositionActionPreviewDrag';

defineOptions({ name: 'PageCompositionDescriptorPreview' });

type PreviewMode = 'list' | 'query' | 'detail' | 'edit';
type PreviewSlot = 'list' | 'form';

const props = defineProps<{
  descriptor: ResolvedModuleUiDescriptor;
  moduleAlias: string;
  mode: PreviewMode;
  selectedFieldName?: string;
  /** Allows the active preview surface to receive a compatible metadata payload. */
  acceptExternalDrop?: boolean;
  structure?: PageCompositionStructure;
  placementDisabled?: boolean;
  /** A failed async descriptor refresh must not leave its pre-drop layout on screen. */
  placementCommitFailed?: boolean;
  actionPlacements?: PageCompositionActionPlacement[];
  actionFormMode?: 'create' | 'edit';
  moduleActions?: ModuleRuntimeAction[];
  querySummaries?: PageQuerySummary[];
}>();

const emit = defineEmits<{
  selectField: [slot: PreviewSlot, fieldName: string];
  configureField: [slot: PreviewSlot, fieldName: string];
  configureRelationField: [relationCode: string, fieldName: string];
  configureAction: [anchor: 'page' | 'detail' | 'form', actionCode: string];
  configureSummaries: [];
  'placement-drop': [source: CompositionPlacementSource, target: CompositionPlacementTarget];
  'action-drop': [
    source: { actionCode: string; sourceAnchor?: 'page' | 'detail' | 'form' },
    target: { anchor: 'page' | 'detail' | 'form'; index: number },
  ];
}>();

const listColumns = computed(() => resolveRecordQueryListColumns(props.descriptor.page?.list?.fields));
const previewSummaryText = computed(() =>
  (props.querySummaries ?? []).map((summary) => ({
    ...summary,
    title: summary.label,
    value: summary.source === 'MATCHED_COUNT' ? '12' : summary.source === 'SUM' ? '1,280.00' : '示例值',
  })),
);
function summaryFieldTitle(key: string, fieldName: string | undefined, kind: 'group' | 'sum') {
  const summary = props.descriptor.page?.list?.querySummaries?.find((item) => item.key === key);
  const compiledTitle = kind === 'group' ? summary?.groupByTitle : summary?.sumFieldTitle;
  return (
    compiledTitle ??
    props.descriptor.page?.list?.fields?.fields?.find((field) => field.fieldRef.fieldName === fieldName)
      ?.label ??
    fieldName
  );
}
const transientExternalField = computed(() => {
  const source = transientPlacement.value?.source;
  return source?.kind === 'metadata' && source.metadata.kind === 'field' && source.metadata.fieldName
    ? source.metadata
    : undefined;
});
/**
 * A metadata field is not part of the server-compiled descriptor until it is dropped.  Give the
 * temporary item a small, source-neutral descriptor so it can participate in the same form grid
 * as persisted fields instead of falling back to a separate drop affordance.
 */
const transientExternalFieldDescriptor = computed<RecordFormFieldDescriptor | undefined>(() => {
  const field = transientExternalField.value;
  if (!field?.fieldName) return undefined;
  const alias = field.fieldSpecAlias?.toLowerCase() ?? '';
  const rendererType = /datetime|timestamp|zoned/.test(alias)
    ? 'DATETIME'
    : /date/.test(alias)
      ? 'DATE'
      : /bool|switch/.test(alias)
        ? 'SWITCH'
        : /number|decimal|integer|long|float|double/.test(alias)
          ? 'NUMBER'
          : 'TEXT';
  const valueType =
    rendererType === 'DATE'
      ? 'DATE'
      : rendererType === 'DATETIME'
        ? 'TIMESTAMP'
        : rendererType === 'SWITCH'
          ? 'BOOLEAN'
          : rendererType === 'NUMBER'
            ? 'DECIMAL'
            : 'STRING';
  return {
    fieldRef: { fieldId: field.fieldId, fieldName: field.fieldName },
    label: field.title ?? field.fieldName,
    required: field.required == null ? undefined : { constant: field.required },
    readOnly: field.readOnly ? { constant: true } : undefined,
    valueType,
    fieldControl: { alias: rendererType.toLowerCase(), rendererType, valueShape: 'SCALAR' },
  };
});
const dataTableColumns = computed<UiDataTableColumn[]>(() =>
  stagedFieldNames(
    { kind: 'list' },
    listColumns.value.map((column) => column.key),
  ).flatMap((fieldName) => {
    const column = listColumns.value.find((candidate) => candidate.key === fieldName);
    if (column) return [{ key: column.key, title: column.title, width: column.width, align: column.align }];
    const external = transientExternalField.value;
    return external?.fieldName === fieldName
      ? [{ key: fieldName, title: external.title ?? fieldName, width: undefined, align: undefined }]
      : [];
  }),
);
const listRecord = computed<QueryListRecord>(() =>
  previewRecord(props.descriptor.page?.list?.fields.fields ?? []),
);
const listSearchPlaceholder = computed(() => props.descriptor.page?.list?.searchPlaceholder);
const detailFields = computed(() => resolveRecordDetailFields(props.descriptor));
const detailFieldNames = computed(() => [...detailFields.value.keys()]);
const detailRecord = computed<UiDataTableRecord>(() => previewRecord([...detailFields.value.values()]));
const detailRelations = computed(() => props.descriptor.detailRelations ?? []);
const formFields = computed(() => resolveRecordFormFields(props.descriptor));
const detailFieldsWithTransient = computed(() => {
  const external = transientExternalFieldDescriptor.value;
  return external
    ? new Map([...detailFields.value, [external.fieldRef.fieldName, external]])
    : detailFields.value;
});
const formFieldsWithTransient = computed(() => {
  const external = transientExternalFieldDescriptor.value;
  return external
    ? new Map([...formFields.value, [external.fieldRef.fieldName, external]])
    : formFields.value;
});
function externalPreviewValue(field: RecordFormFieldDescriptor) {
  switch (field.valueType) {
    case 'DATE':
      return '2026-09-07';
    case 'TIMESTAMP':
    case 'ZONED_TIMESTAMP':
      return '2026-09-07T09:30';
    case 'BOOLEAN':
      return true;
    case 'INTEGER':
    case 'LONG':
    case 'DECIMAL':
      return 128;
    default:
      return '示例内容';
  }
}
const detailRecordWithTransient = computed<UiDataTableRecord>(() => {
  const field = transientExternalFieldDescriptor.value;
  return field
    ? { ...detailRecord.value, [field.fieldRef.fieldName]: externalPreviewValue(field) }
    : detailRecord.value;
});
const formRecordWithTransient = computed<RecordFormRecord>(() => {
  const field = transientExternalFieldDescriptor.value;
  return field
    ? { ...formRecord.value, [field.fieldRef.fieldName]: externalPreviewValue(field) }
    : formRecord.value;
});
const formFieldNames = computed(() => [...formFields.value.keys()]);
const formRecord = ref<RecordFormRecord>(previewRecord([]));
const relationEditorRecords = ref<Record<string, UiDataTableRecord[]>>({});
const previewRoot = ref<HTMLElement>();
const previousLayout = new Map<string, DOMRect>();
const selectedDetailFieldName = computed(() =>
  props.selectedFieldName?.startsWith('form:') ? props.selectedFieldName.slice('form:'.length) : undefined,
);
const isListEmpty = computed(() => listColumns.value.length === 0);
const isDetailEmpty = computed(
  () =>
    detailFieldNames.value.length === 0 &&
    detailRelations.value.length === 0 &&
    structure.value.groups.length === 0,
);
const isFormEmpty = computed(() => formFieldNames.value.length === 0);
const isEditEmpty = computed(() => isFormEmpty.value && detailRelations.value.length === 0);
const actionsAt = (anchor: PageCompositionActionPlacement['anchor']) =>
  computed(() =>
    (props.actionPlacements ?? [])
      .filter(
        (placement) =>
          placement.anchor === anchor &&
          !(
            anchor === 'detail' &&
            placement.actionCode === 'enable' &&
            (props.actionPlacements ?? []).some(
              (entry) => entry.anchor === anchor && entry.actionCode === 'disable' && !entry.hidden,
            )
          ) &&
          pageActionEntryVisible(
            placement,
            props.mode === 'edit' ? (props.actionFormMode ?? 'edit') : 'view',
          ),
      )
      .map((placement) => {
        const action = props.moduleActions?.find((action) => action.actionCode === placement.actionCode);
        return action ? { ...action, title: pageActionEntryTitle(placement) } : undefined;
      })
      .filter((action) => action !== undefined),
  );
const pageActions = actionsAt('page');
const detailActions = actionsAt('detail');
const formActions = actionsAt('form');
const pageActionRoot = ref<HTMLElement>();
const detailActionRoot = ref<HTMLElement>();
const formActionRoot = ref<HTMLElement>();
const actionDropEnabled = computed(() => !!props.acceptExternalDrop && !props.placementDisabled);
const actionCanOccupyAnchor = (
  source: { actionCode: string; sourceAnchor?: string },
  anchor: PageCompositionActionPlacement['anchor'],
) => {
  const action = props.moduleActions?.find((action) => action.actionCode === source.actionCode);
  return (
    (!source.sourceAnchor || source.sourceAnchor === anchor || action?.category === 'CUSTOM') &&
    canPlaceActionInAnchor(action, anchor)
  );
};
const pageActionDrag = usePageCompositionActionPreviewDrag(
  pageActionRoot,
  'page',
  pageActions,
  actionDropEnabled,
  (source) => actionCanOccupyAnchor(source, 'page'),
  (source, target) => emit('action-drop', source, target),
);
const detailActionDrag = usePageCompositionActionPreviewDrag(
  detailActionRoot,
  'detail',
  detailActions,
  actionDropEnabled,
  (source) => actionCanOccupyAnchor(source, 'detail'),
  (source, target) => emit('action-drop', source, target),
);
const formActionDrag = usePageCompositionActionPreviewDrag(
  formActionRoot,
  'form',
  formActions,
  actionDropEnabled,
  (source) => actionCanOccupyAnchor(source, 'form'),
  (source, target) => emit('action-drop', source, target),
);
function actionItems(actionCodes: readonly string[], anchor: PageCompositionActionPlacement['anchor']) {
  return actionCodes.map((actionCode) => ({
    actionCode,
    bindingPending: props.moduleActions?.find((action) => action.actionCode === actionCode)?.bindingPending,
    iconName: defaultActionIcon({ key: actionCode, actionCode, title: actionCode }),
    title: pageActionEntryTitle(
      props.actionPlacements?.find((entry) => entry.anchor === anchor && entry.actionCode === actionCode) ?? {
        actionCode,
        anchor,
        title: props.moduleActions?.find(
          (action) => action.actionCode === actionCode && action.category === 'CUSTOM',
        )?.title,
      },
    ),
  }));
}
function isTransientAction(drag: ReturnType<typeof usePageCompositionActionPreviewDrag>, actionCode: string) {
  return drag.transientPlacement.value?.actionCode === actionCode;
}
function actionDropClass(feedback?: { key?: string; rejected: boolean }) {
  return {
    // A whole-bar outline means append-to-end. Item targets use an insertion line instead.
    'page-composition-action-preview--drop-active': Boolean(feedback && !feedback.key),
    'page-composition-action-preview--drop-rejected': feedback?.rejected === true,
  };
}
function actionDropItemClass(
  feedback: { key?: string; position: 'before' | 'inside' | 'after'; rejected: boolean } | undefined,
  actionCode: string,
) {
  return {
    'page-composition-action-preview__button--drop-before':
      feedback?.key === actionCode && feedback.position === 'before' && !feedback.rejected,
    'page-composition-action-preview__button--drop-after':
      feedback?.key === actionCode && feedback.position === 'after' && !feedback.rejected,
  };
}
const structure = computed<PageCompositionStructure>(() => {
  if (props.structure) return props.structure;
  const field = (name: string) => ({ id: name, fieldName: name, title: name });
  const detail = props.descriptor.page?.detail;
  const view =
    props.mode === 'edit'
      ? (detail?.editor ?? detail?.display ?? props.descriptor.defaultEditor)
      : (detail?.display ?? detail?.editor ?? props.descriptor.defaultEditor);
  const groups = view?.formGroups ?? [];
  const names = props.mode === 'edit' ? formFieldNames.value : detailFieldNames.value;
  const grouped = new Set(groups.flatMap((group) => group.fields.map((field) => field.fieldName)));
  return {
    list: listColumns.value.map((column) => field(column.key)),
    form: names.filter((name) => !grouped.has(name)).map(field),
    order: [
      ...new Map<string, PageComposerFormItem>(
        names.map((name) => {
          const group = groups.find((group) => group.fields.some((field) => field.fieldName === name));
          return group
            ? ([`group:${group.groupCode}`, { kind: 'group' as const, id: group.groupCode }] as const)
            : ([`field:${name}`, { kind: 'field' as const, id: name }] as const);
        }),
      ).values(),
    ],
    groups: groups.map((group) => ({
      id: group.groupCode,
      groupCode: group.groupCode,
      title: group.title,
      subtitle: group.subtitle,
      fields: group.fields.map((ref) => field(ref.fieldName)),
    })),
    relations: detailRelations.value.map((relation) => ({
      id: relation.code,
      relationCode: relation.code,
      title: relation.title ?? relation.code,
      fields: (relation.listProjection?.fields ?? []).map((ref) => field(ref.fieldName)),
    })),
  };
});
const formSections = computed(() => [
  {
    key: 'form',
    title: '表单字段',
    container: { kind: 'form' } as CompositionContainer,
    fields: structure.value.form,
  },
  ...structure.value.groups.map((group) => ({
    key: `group:${group.id}`,
    title: group.title,
    subtitle: group.subtitle,
    container: { kind: 'group', groupId: group.id } as CompositionContainer,
    fields: group.fields,
  })),
]);
const placementEntries = computed(() => {
  const entries = new Map<string, PreviewPlacementEntry>();
  const mode = props.mode;
  for (const field of structure.value.list) {
    const entry: PreviewPlacementEntry = {
      title: field.properties?.label ?? field.title,
      container: { kind: 'list' },
      nodeId: field.id,
      axis: 'x',
    };
    entries.set(`list:header:${field.fieldName}`, entry);
    entries.set(`list:field:${field.fieldName}`, entry);
  }
  entries.set('list:empty', { title: '空列表', container: { kind: 'list' } });
  if (mode === 'list') return entries;
  for (const section of formSections.value) {
    entries.set(`${mode}:container:${section.key}`, { title: section.title, container: section.container });
    for (const field of section.fields)
      entries.set(`${mode}:field:${field.fieldName}`, {
        title: field.properties?.label ?? field.title,
        container: section.container,
        nodeId: field.id,
        // Both detail and edit surfaces are two-column field grids. Their left/right receiver
        // semantics must stay identical instead of making edit mode fall back to row-only hits.
        axis: mode === 'detail' || mode === 'edit' ? 'grid' : undefined,
        region: section.container.kind === 'form' ? rootRegion(field.id) : section.key,
      });
    if (section.container.kind === 'group')
      entries.set(`${mode}:${section.key}`, {
        title: section.title,
        container: { kind: 'groups' },
        nodeId: section.container.groupId,
        inside: section.container,
      });
  }
  for (const relation of structure.value.relations) {
    entries.set(`${mode}:relation:${relation.relationCode}`, {
      title: relation.title,
      container: { kind: 'relations' },
      nodeId: relation.id,
      inside: { kind: 'relation', relationId: relation.id },
    });
    entries.set(`${mode}:relation:${relation.relationCode}:end`, {
      title: `${relation.title}末列`,
      container: { kind: 'relation', relationId: relation.id },
    });
    for (const field of relation.fields)
      for (const part of ['header', 'field'])
        entries.set(`${mode}:relation:${relation.relationCode}:${part}:${field.fieldName}`, {
          title: field.title,
          container: { kind: 'relation', relationId: relation.id },
          nodeId: field.id,
          axis: 'x',
        });
  }
  entries.set('relations:end', { title: '子表区域末尾', container: { kind: 'relations' } });
  return entries;
});
const { handleProps, groupOutline, columnOutline, feedback, transientPlacement, abandonPendingPlacement } =
  usePageCompositionPreviewDrag(
    previewRoot,
    placementEntries,
    structure,
    computed(() => !!props.acceptExternalDrop && !props.placementDisabled),
    (source, target) => emit('placement-drop', source, target),
  );
watch(
  () => props.placementCommitFailed,
  (failed) => {
    if (failed) abandonPendingPlacement();
  },
);

/**
 * The descriptor remains authoritative until drop. These projections only reorder already-known
 * renderer fields, so the real cards/tables perform the same layout transition as the committed
 * draft without asking the server to compile an intermediate descriptor.
 */
function fieldNameOf(id: string) {
  for (const field of structure.value.list) if (field.id === id) return field.fieldName;
  for (const field of structure.value.form) if (field.id === id) return field.fieldName;
  for (const group of structure.value.groups)
    for (const field of group.fields) if (field.id === id) return field.fieldName;
  for (const relation of structure.value.relations)
    for (const field of relation.fields) if (field.id === id) return field.fieldName;
  return undefined;
}
function fieldContainerOf(id: string): CompositionContainer | undefined {
  if (structure.value.list.some((field) => field.id === id)) return { kind: 'list' };
  if (structure.value.form.some((field) => field.id === id)) return { kind: 'form' };
  const group = structure.value.groups.find((candidate) => candidate.fields.some((field) => field.id === id));
  if (group) return { kind: 'group', groupId: group.id };
  const relation = structure.value.relations.find((candidate) =>
    candidate.fields.some((field) => field.id === id),
  );
  return relation ? { kind: 'relation', relationId: relation.id } : undefined;
}
function transientField() {
  const source = transientPlacement.value?.source;
  if (!source) return undefined;
  const id =
    source.kind === 'node'
      ? source.nodeId
      : source.metadata.kind === 'field' || source.metadata.kind === 'relationField'
        ? source.metadata.fieldId
        : undefined;
  return id
    ? {
        id,
        // External metadata has not been added to `structure` yet, so it must retain the field
        // fact carried by its drag payload. Looking it up only in the draft made live preview
        // impossible for exactly the new-field path this surface is meant to support.
        fieldName:
          source.kind === 'metadata' && source.metadata.kind === 'field'
            ? (source.metadata.fieldName ?? fieldNameOf(id))
            : fieldNameOf(id),
        container: fieldContainerOf(id),
      }
    : undefined;
}
function stagedFieldNames(container: CompositionContainer, fieldNames: readonly string[]) {
  const staged = transientPlacement.value;
  const field = transientField();
  if (!staged || !field?.fieldName) return [...fieldNames];
  const sourceContainer = staged.source.kind === 'node' ? staged.source.container : field.container;
  const targetContainer = staged.placement.container;
  let next = [...fieldNames];
  if (sourceContainer && containerKey(sourceContainer) === containerKey(container))
    next = next.filter((name) => name !== field.fieldName);
  if (containerKey(targetContainer) !== containerKey(container)) return next;
  next = next.filter((name) => name !== field.fieldName);
  next.splice(Math.min(staged.placement.index, next.length), 0, field.fieldName);
  return next;
}
function rootRegion(fieldId: string) {
  const items = orderedFormItems(structure.value.form, structure.value.groups, structure.value.order);
  let region = 0;
  for (const item of items) {
    if (item.kind === 'group') region++;
    else if (item.id === fieldId) return `root:${region}`;
  }
  return `root:${region}`;
}
const renderedForm = computed(() => {
  const form = [...structure.value.form];
  const groups = structure.value.groups.map((group) => ({ ...group, fields: [...group.fields] }));
  let order = orderedFormItems(form, groups, structure.value.order);
  const staged = transientPlacement.value;
  const moving = transientField();
  if (staged?.source.kind === 'node' && staged.source.container.kind === 'groups') {
    const id = staged.source.nodeId;
    order = order.filter((item) => !(item.kind === 'group' && item.id === id));
    order.splice(staged.placement.index, 0, { kind: 'group', id });
  } else if (staged && moving?.fieldName && ['form', 'group'].includes(staged.placement.container.kind)) {
    const field = form.find((field) => field.id === moving.id) ??
      groups.flatMap((group) => group.fields).find((field) => field.id === moving.id) ?? {
        id: moving.id,
        fieldName: moving.fieldName,
        title: moving.fieldName,
      };
    const rootIndex = form.findIndex((item) => item.id === field.id);
    if (rootIndex >= 0) form.splice(rootIndex, 1);
    groups.forEach((group) => {
      group.fields = group.fields.filter((item) => item.id !== field.id);
    });
    order = order.filter((item) => item.kind !== 'field' || item.id !== field.id);
    const container = staged.placement.container;
    if (container.kind === 'form') {
      form.push(field);
      order.splice(staged.placement.index, 0, { kind: 'field', id: field.id });
    } else if (container.kind === 'group')
      groups.find((group) => group.id === container.groupId)?.fields.splice(staged.placement.index, 0, field);
  }
  return { form, groups, order };
});
const renderedFieldNames = computed(() =>
  renderedForm.value.order.flatMap((item) =>
    item.kind === 'field'
      ? [renderedForm.value.form.find((field) => field.id === item.id)!.fieldName]
      : renderedForm.value.groups
          .find((group) => group.id === item.id)!
          .fields.map((field) => field.fieldName),
  ),
);
function withRenderedGroups(fields: Map<string, RecordFormFieldDescriptor>) {
  return new Map(
    [...fields].map(([name, field]) => {
      const group = renderedForm.value.groups.find((group) =>
        group.fields.some((field) => field.fieldName === name),
      );
      return [
        name,
        {
          ...field,
          formGroup: group
            ? {
                groupCode: group.id,
                title: group.title,
                subtitle: group.subtitle,
                fields: group.fields.map((field) => ({ fieldName: field.fieldName })),
              }
            : undefined,
        },
      ];
    }),
  );
}
const renderedFormFields = computed(() => withRenderedGroups(formFieldsWithTransient.value));
const renderedDetailFields = computed(() => withRenderedGroups(detailFieldsWithTransient.value));
const visiblePreviewFieldNames = computed(() => {
  const fields = props.mode === 'edit' ? renderedFormFields.value : renderedDetailFields.value;
  const record = props.mode === 'edit' ? formRecordWithTransient.value : detailRecordWithTransient.value;
  return new Set(
    renderedFieldNames.value.filter(
      (name) => fields.has(name) && resolveRecordFormFieldState(name, { fields, record }).visible,
    ),
  );
});
function emptyGroupsBefore(fieldName?: string) {
  const pending: typeof renderedForm.value.groups = [];
  for (const item of renderedForm.value.order) {
    const group =
      item.kind === 'group' ? renderedForm.value.groups.find((group) => group.id === item.id) : undefined;
    const names = group
      ? group.fields.map((field) => field.fieldName)
      : [renderedForm.value.form.find((field) => field.id === item.id)!.fieldName];
    if (group && !names.length) pending.push(group);
    else {
      // Invisible members do not consume an empty heading's next visible anchor.
      const visibleNames = names.filter((name) => visiblePreviewFieldNames.value.has(name));
      if (!visibleNames.length) continue;
      if (visibleNames.includes(fieldName ?? '')) return visibleNames[0] === fieldName ? pending : [];
      pending.length = 0;
    }
  }
  return fieldName ? [] : pending;
}

watch(
  () => props.descriptor,
  () => {
    // Preserve sample input while a new descriptor updates the placement; samples never enter the draft.
    const sample = previewRecord([...formFields.value.values()]);
    formRecord.value = retainPreviewValues(sample, formRecord.value);
    relationEditorRecords.value = Object.fromEntries(
      detailRelations.value.map((relation) => {
        const sample = relationRecord(relation);
        const previous = relationEditorRecords.value[relation.code]?.find((row) => row.id === sample.id);
        return [relation.code, [retainPreviewValues(sample, previous)]];
      }),
    );
  },
  { immediate: true },
);

function retainPreviewValues(sample: UiDataTableRecord, previous?: UiDataTableRecord): UiDataTableRecord {
  return Object.fromEntries(
    Object.entries(sample).map(([key, value]) => [
      key,
      previous && Object.hasOwn(previous, key) ? previous[key] : value,
    ]),
  );
}

function previewRecord(fields: readonly ResolvedViewFieldDescriptor[]): UiDataTableRecord {
  return {
    id: 'page-composition-preview-record',
    ...Object.fromEntries(fields.map((field) => [field.fieldRef.fieldName, previewValue(field)])),
  };
}

function previewValue(field: ResolvedViewFieldDescriptor): unknown {
  if (field.uiType === 'enabledStatus' || field.uiType === 'booleanStatus' || field.uiType === 'switch') {
    return true;
  }
  if (field.valuePresentation === 'FILE_SIZE') return 1024 * 256;
  if (field.valueType === 'INTEGER' || field.valueType === 'LONG' || field.valueType === 'DECIMAL')
    return 128;
  if (field.valueType === 'TIMESTAMP' || field.valueType === 'ZONED_TIMESTAMP') {
    return '2026-08-30T09:30:00+08:00';
  }
  if (field.uiType === 'tagList') {
    return [
      { id: 'tag-a', title: '示例标签 A', color: '#1677FF' },
      { id: 'tag-b', title: '示例标签 B', color: '#52C41A' },
    ];
  }
  if (field.fieldControl?.rendererType === 'COLOR_PICKER' || field.uiType === 'colorPicker') {
    return '#1677FF';
  }
  return '示例内容';
}

function relationColumns(relation: ResolvedDetailRelationDescriptor): UiDataTableColumn[] {
  const fields = relation.listProjection?.fields ?? [];
  const compositionRelation = structure.value.relations.find(
    (candidate) => candidate.relationCode === relation.code,
  );
  const container: CompositionContainer = compositionRelation
    ? { kind: 'relation', relationId: compositionRelation.id }
    : { kind: 'relation', relationId: relation.code };
  return stagedFieldNames(
    container,
    fields.map((field) => field.fieldName),
  ).flatMap((fieldName) => {
    const field = fields.find((candidate) => candidate.fieldName === fieldName);
    if (!field) return [];
    return [
      {
        key: field.fieldName,
        title: field.title ?? field.fieldName,
        ...(field.width ? { width: field.width } : {}),
        ...(field.align === 'left' || field.align === 'center' || field.align === 'right'
          ? { align: field.align }
          : {}),
      },
    ];
  });
}

function relationRecord(relation: ResolvedDetailRelationDescriptor): UiDataTableRecord {
  return {
    id: `page-composition-relation-preview:${relation.code}`,
    ...Object.fromEntries(
      (relation.listProjection?.fields ?? []).map((field) => [field.fieldName, relationPreviewValue(field)]),
    ),
  };
}

function relationEditorRows(relation: ResolvedDetailRelationDescriptor) {
  return relationEditorRecords.value[relation.code] ?? [];
}

function updateRelationEditorField(
  relation: ResolvedDetailRelationDescriptor,
  rowId: unknown,
  fieldName: string,
  value: unknown,
) {
  relationEditorRecords.value = {
    ...relationEditorRecords.value,
    [relation.code]: relationEditorRows(relation).map((row) =>
      row.id === rowId ? { ...row, [fieldName]: value } : row,
    ),
  };
}

function relationPreviewValue(field: { fieldName: string; title?: string; valueType?: string }) {
  if (field.valueType === 'BOOLEAN') return true;
  if (['INTEGER', 'LONG', 'DECIMAL'].includes(field.valueType ?? '')) return 96;
  if (field.valueType === 'DATE') return '2026-09-06';
  if (/(score|grade|amount|count|number)$/i.test(field.fieldName)) return 96;
  return `示例${field.title ?? field.fieldName}`;
}

function relationFields(relation: ResolvedDetailRelationDescriptor) {
  return resolveRecordFormFields(props.descriptor, relation.targetEntityAlias);
}

function isSelected(slot: PreviewSlot, fieldName: string) {
  return props.selectedFieldName === `${slot}:${fieldName}`;
}

function updateFormField(fieldName: string, value: RecordFormFieldValue) {
  formRecord.value = { ...formRecord.value, [fieldName]: value };
}

function updateReferenceProjections(fieldName: string, projections: Record<string, unknown>) {
  const prefix = `${fieldName}.`;
  formRecord.value = {
    ...Object.fromEntries(Object.entries(formRecord.value).filter(([name]) => !name.startsWith(prefix))),
    ...projections,
  };
}

function layoutKeyOf(element: HTMLElement) {
  return element.dataset.pageCompositionLayoutKey;
}

/**
 * The data table renders header and row cells in separate tables. Animate those physical cells,
 * while retaining the descriptor marker as the stable identity, so the column outline and table
 * content share one moving rectangle throughout a held placement.
 */
function layoutElements() {
  const elements: Array<readonly [string, HTMLElement]> = [];
  const listElements = new Map<string, HTMLElement>();
  previewRoot.value?.querySelectorAll<HTMLElement>('[data-page-composition-layout-key]').forEach((marker) => {
    const key = layoutKeyOf(marker);
    if (!key) return;
    if (!key.startsWith('list:')) {
      // Non-list layouts retain their marker-level FLIP behavior.
      elements.push([key, marker]);
      return;
    }
    const element = marker.closest<HTMLElement>('th, td') ?? marker;
    const previous = listElements.get(key);
    if (
      !previous ||
      element.getBoundingClientRect().width * element.getBoundingClientRect().height >
        previous.getBoundingClientRect().width * previous.getBoundingClientRect().height
    )
      listElements.set(key, element);
  });
  return [...elements, ...listElements];
}

// Hover feedback and sample input update the component too, but do not change its composition.
const layoutInputs = computed(() => [
  props.mode,
  transientPlacement.value,
  listColumns.value,
  detailFields.value,
  formFields.value,
  formSections.value,
  detailRelations.value,
]);
let renderedLayout = layoutInputs.value;
let layoutChanged = false;
const layoutAnimations = new Map<HTMLElement, Animation>();
const layoutAnimationTimers = new Map<HTMLElement, number>();
function cancelLayoutAnimations() {
  layoutAnimations.forEach((animation) => animation.cancel());
  layoutAnimations.clear();
  layoutAnimationTimers.forEach((timer) => window.clearTimeout(timer));
  layoutAnimationTimers.clear();
}
onBeforeUnmount(cancelLayoutAnimations);

onBeforeUpdate(() => {
  layoutChanged = renderedLayout !== layoutInputs.value;
  renderedLayout = layoutInputs.value;
  if (!layoutChanged) return;
  previousLayout.clear();
  layoutElements().forEach(([key, element]) => previousLayout.set(key, element.getBoundingClientRect()));
  // Capture the current visual position before removing a superseded composition animation.
  cancelLayoutAnimations();
});

onUpdated(() => {
  if (!layoutChanged) return;
  layoutChanged = false;
  if (typeof window === 'undefined' || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches)
    return;
  // Vue has patched the children here. Start FLIP in this same update, so a new tree order is never
  // exposed as stationary for a frame before its delayed animation starts underneath a second drag.
  layoutElements().forEach(([key, element]) => {
    const previous = previousLayout.get(key);
    if (!previous) return;
    const current = element.getBoundingClientRect();
    const x = previous.left - current.left;
    const y = previous.top - current.top;
    if (Math.abs(x) < 1 && Math.abs(y) < 1) return;
    animateLayoutElement(element, x, y);
  });
});

function animateLayoutElement(element: HTMLElement, x: number, y: number) {
  const duration = element.matches('th, td') ? 160 : 300;
  if (typeof element.animate === 'function') {
    const animation = element.animate(
      [
        { transform: `translate(${x}px, ${y}px)`, opacity: 0.72 },
        { transform: 'translate(0, 0)', opacity: 1 },
      ],
      { duration, easing: 'cubic-bezier(0.2, 0, 0, 1)' },
    );
    if (animation) {
      layoutAnimations.set(element, animation);
      const cleanup = () => {
        if (layoutAnimations.get(element) === animation) layoutAnimations.delete(element);
      };
      animation.onfinish = cleanup;
      animation.oncancel = cleanup;
    }
    return;
  }
  const originalTransition = element.style.transition;
  element.style.transition = 'none';
  element.style.transform = `translate(${x}px, ${y}px)`;
  element.style.opacity = '0.72';
  void element.offsetWidth;
  element.style.transition = `transform ${duration}ms cubic-bezier(0.2, 0, 0, 1), opacity ${duration}ms cubic-bezier(0.2, 0, 0, 1)`;
  element.style.transform = 'translate(0, 0)';
  element.style.opacity = '1';
  const timer = window.setTimeout(() => {
    element.style.transition = originalTransition;
    element.style.transform = '';
    element.style.opacity = '';
    layoutAnimationTimers.delete(element);
  }, duration + 20);
  layoutAnimationTimers.set(element, timer);
}
</script>

<template>
  <template v-if="mode === 'list'">
    <section
      ref="previewRoot"
      class="page-composition-descriptor-preview page-composition-descriptor-preview--list"
      data-testid="page-composer-list-preview"
      tabindex="0"
      data-composer-drop-target="list"
    >
      <div
        v-if="columnOutline"
        class="page-composer-column-drag-outline"
        :style="columnOutline"
        aria-hidden="true"
      />
      <div
        v-if="feedback && !transientPlacement"
        class="page-composer-drop-indicator"
        :class="{
          'page-composer-drop-indicator--rejected': feedback.rejected,
          'page-composer-drop-indicator--transient': transientPlacement,
        }"
        :style="{
          left: `${feedback.left}px`,
          top: `${feedback.top}px`,
          width: `${feedback.width}px`,
          height: `${feedback.height}px`,
        }"
        role="status"
      >
        <span>{{ feedback.title }}</span>
      </div>
      <RecordQueryListSurface
        class="page-composition-descriptor-preview__list-surface"
        :header-visible="true"
        :show-title="false"
        quick-search-visible
        quick-search-value=""
        :quick-search-placeholder="listSearchPlaceholder"
        quick-search-disabled
        :columns="dataTableColumns"
        :rows="[listRecord]"
        row-key="id"
        :table-visible="!isListEmpty"
        pageable
        :total="1"
        pagination-disabled
        embedded
      >
        <template #operations>
          <div
            ref="pageActionRoot"
            class="page-composition-action-preview page-composition-action-preview--list"
            :class="actionDropClass(pageActionDrag.feedback.value)"
            data-ui-drop-root
            tabindex="0"
          >
            <TransitionGroup
              name="page-composer-action-layout"
              tag="span"
              class="page-composer-action-layout"
            >
              <span
                v-for="action in actionItems(pageActionDrag.stagedActionCodes.value, 'page')"
                @dblclick.stop="emit('configureAction', 'page', action.actionCode)"
                :key="action.actionCode"
                :data-page-action-key="action.actionCode"
                :class="[
                  actionDropItemClass(pageActionDrag.feedback.value, action.actionCode),
                  {
                    'page-composition-action-preview__button--transient': isTransientAction(
                      pageActionDrag,
                      action.actionCode,
                    ),
                  },
                ]"
              >
                <span
                  v-bind="
                    pageActionDrag.dragHandleProps(action.actionCode, action.title ?? action.actionCode)
                  "
                  >⠿</span
                >
                <UiActionButton
                  :disabled="action.bindingPending"
                  :title="action.bindingPending ? '待绑定执行能力' : undefined"
                  :intent="action.actionCode === 'delete' ? 'danger' : 'normal'"
                  :icon-name="action.iconName"
                  >{{ action.title ?? action.actionCode }}</UiActionButton
                >
              </span>
            </TransitionGroup>
          </div>
        </template>
        <template #header="{ column }">
          <span
            class="page-composer-column-heading"
            :data-page-composition-layout-key="`list:header:${column.key}`"
            :data-ui-drop-key="`list:header:${column.key}`"
            tabindex="0"
          >
            <span v-if="acceptExternalDrop" v-bind="handleProps(`list:header:${column.key}`, column.title)"
              >⠿</span
            >{{ column.title }}
          </span>
        </template>
        <template #cell="{ column }">
          <button
            class="page-composition-descriptor-preview__field"
            :class="{
              'page-composition-descriptor-preview__field--selected': isSelected('list', column.key),
              'page-composer-external-field-preview--dragging':
                transientExternalField?.fieldName === column.key,
            }"
            type="button"
            :title="`配置${column.title}`"
            :data-page-composition-layout-key="`list:field:${column.key}`"
            @click="emit('selectField', 'list', column.key)"
            @dblclick="emit('configureField', 'list', column.key)"
            @keydown.space.prevent="emit('configureField', 'list', column.key)"
          >
            <RecordQueryListCell
              v-if="listColumns.find((item) => item.key === column.key)"
              :record="listRecord"
              :column="listColumns.find((item) => item.key === column.key)!"
            />
            <span v-else class="page-composer-external-field-preview">示例内容</span>
          </button>
        </template>
        <template #beforeTable>
          <div
            v-if="isListEmpty"
            data-composer-target="list:empty"
            data-ui-drop-key="list:empty"
            :tabindex="acceptExternalDrop ? 0 : -1"
          >
            <UiEmpty description="当前草稿尚未配置列表字段" />
          </div>
        </template>
        <template #footer>
          <div class="page-composer-summary-preview">
            <template v-for="summary in previewSummaryText" :key="summary.key">
              <QueryGroupedSummary
                v-if="summary.source === 'GROUPED'"
                :title="summary.title"
                :group-by-title="summaryFieldTitle(summary.key, summary.groupByField, 'group')"
                :sum-field-title="
                  summary.fieldName ? summaryFieldTitle(summary.key, summary.fieldName, 'sum') : undefined
                "
                :example-rows="[
                  { value: 'supplier-a', label: '示例分组 A', count: 2, sum: 120 },
                  { value: 'supplier-b', label: '示例分组 B', count: 1, sum: 80 },
                ]"
              />
              <span v-else>
                <span>{{ summary.title }}</span
                ><strong>示例 {{ summary.value }}</strong>
              </span>
            </template>
          </div>
        </template>
      </RecordQueryListSurface>
    </section>
    <UiButton class="page-composer-summary-configure" size="small" @click="emit('configureSummaries')">
      {{ previewSummaryText.length ? `汇总统计 · ${previewSummaryText.length} 项` : '配置汇总统计' }}
    </UiButton>
  </template>

  <section
    v-else-if="mode === 'query'"
    ref="previewRoot"
    class="page-composition-descriptor-preview"
    data-testid="page-composer-query-preview"
    tabindex="0"
    data-composer-drop-target="list"
  >
    <div
      v-if="feedback && !transientPlacement"
      class="page-composer-drop-indicator"
      :class="{
        'page-composer-drop-indicator--rejected': feedback.rejected,
        'page-composer-drop-indicator--transient': transientPlacement,
      }"
      :style="{
        left: `${feedback.left}px`,
        top: `${feedback.top}px`,
        width: `${feedback.width}px`,
        height: `${feedback.height}px`,
      }"
      role="status"
    >
      <span>{{ feedback.title }}</span>
    </div>
    <RecordQueryListSurface
      class="page-composition-descriptor-preview__query-surface"
      :show-title="false"
      quick-search-visible
      quick-search-value=""
      :quick-search-placeholder="listSearchPlaceholder"
      quick-search-disabled
      :columns="[]"
      :rows="[]"
      :table-visible="false"
      embedded
    />
  </section>

  <section
    v-else-if="mode === 'detail'"
    ref="previewRoot"
    class="page-composition-descriptor-preview"
    data-testid="page-composer-detail-preview"
    tabindex="0"
    data-composer-drop-target="form"
  >
    <div
      v-if="descriptor.page?.template !== 'LIST_DETAIL_CARD'"
      ref="pageActionRoot"
      class="page-composition-action-preview page-composition-action-preview--page"
      :class="actionDropClass(pageActionDrag.feedback.value)"
      data-ui-drop-root
      tabindex="0"
    >
      <TransitionGroup name="page-composer-action-layout" tag="span" class="page-composer-action-layout">
        <span
          v-for="action in actionItems(pageActionDrag.stagedActionCodes.value, 'page')"
          @dblclick.stop="emit('configureAction', 'page', action.actionCode)"
          :key="action.actionCode"
          :data-page-action-key="action.actionCode"
          :class="[
            actionDropItemClass(pageActionDrag.feedback.value, action.actionCode),
            {
              'page-composition-action-preview__button--transient': isTransientAction(
                pageActionDrag,
                action.actionCode,
              ),
            },
          ]"
        >
          <span v-bind="pageActionDrag.dragHandleProps(action.actionCode, action.title ?? action.actionCode)"
            >⠿</span
          >
          <UiActionButton
            :disabled="action.bindingPending"
            :title="action.bindingPending ? '待绑定执行能力' : undefined"
            :intent="action.actionCode === 'delete' ? 'danger' : 'normal'"
            :icon-name="action.iconName"
            >{{ action.title ?? action.actionCode }}</UiActionButton
          >
        </span>
      </TransitionGroup>
    </div>
    <div
      v-if="groupOutline"
      class="page-composer-group-drag-outline"
      :style="groupOutline"
      aria-hidden="true"
    />
    <div
      v-if="feedback && !transientPlacement"
      class="page-composer-drop-indicator"
      :class="{
        'page-composer-drop-indicator--rejected': feedback.rejected,
        'page-composer-drop-indicator--transient': transientPlacement,
      }"
      :style="{
        left: `${feedback.left}px`,
        top: `${feedback.top}px`,
        width: `${feedback.width}px`,
        height: `${feedback.height}px`,
      }"
      role="status"
    >
      <span>{{ feedback.title }}</span>
    </div>
    <div
      ref="detailActionRoot"
      class="page-composition-action-preview"
      :class="actionDropClass(detailActionDrag.feedback.value)"
      data-ui-drop-root
      tabindex="0"
    >
      <TransitionGroup name="page-composer-action-layout" tag="span" class="page-composer-action-layout">
        <span
          v-for="action in actionItems(detailActionDrag.stagedActionCodes.value, 'detail')"
          @dblclick.stop="emit('configureAction', 'detail', action.actionCode)"
          :key="action.actionCode"
          :data-page-action-key="action.actionCode"
          :class="[
            actionDropItemClass(detailActionDrag.feedback.value, action.actionCode),
            {
              'page-composition-action-preview__button--transient': isTransientAction(
                detailActionDrag,
                action.actionCode,
              ),
            },
          ]"
        >
          <span
            v-bind="detailActionDrag.dragHandleProps(action.actionCode, action.title ?? action.actionCode)"
            >⠿</span
          >
          <UiActionButton
            :disabled="action.bindingPending"
            :title="action.bindingPending ? '待绑定执行能力' : undefined"
            :intent="action.actionCode === 'delete' ? 'danger' : 'normal'"
            :icon-name="action.iconName"
            >{{ action.title ?? action.actionCode }}</UiActionButton
          >
        </span>
      </TransitionGroup>
    </div>
    <UiEmpty v-if="isDetailEmpty" description="当前草稿尚未配置详情字段或关联子表" />
    <RecordDetailFields
      interaction-mode="selectable"
      :record="detailRecordWithTransient"
      :fields="renderedDetailFields"
      :field-names="renderedFieldNames.filter((name) => detailFieldsWithTransient.has(name))"
      :selected-field-name="selectedDetailFieldName"
      layout-transition-prefix="detail"
      @select="(name) => emit('selectField', 'form', name)"
      @configure="(name) => emit('configureField', 'form', name)"
    >
      <template #before-field="{ field }">
        <RecordContentSectionHeading
          v-for="group in emptyGroupsBefore(field.fieldName)"
          :key="group.id"
          class="page-composer-empty-group"
          :title="group.title"
          :subtitle="group.subtitle"
          :data-page-composition-layout-key="`detail:group:${group.id}`"
          :data-composer-target="`detail:group:${group.id}`"
          :data-ui-drop-key="`detail:group:${group.id}`"
          tabindex="0"
        >
          <template #actions
            ><span v-if="acceptExternalDrop" v-bind="handleProps(`detail:group:${group.id}`, group.title)"
              >⠿</span
            ></template
          >
        </RecordContentSectionHeading>
      </template>
      <template #group-actions="{ group }">
        <span
          v-if="acceptExternalDrop && group"
          v-bind="handleProps(`detail:group:${group.groupCode}`, group.title)"
          >⠿</span
        >
      </template>
      <template #after-fields>
        <RecordContentSectionHeading
          v-for="group in emptyGroupsBefore()"
          :key="group.id"
          class="page-composer-empty-group"
          :title="group.title"
          :subtitle="group.subtitle"
          :data-page-composition-layout-key="`detail:group:${group.id}`"
          :data-composer-target="`detail:group:${group.id}`"
          :data-ui-drop-key="`detail:group:${group.id}`"
          tabindex="0"
        >
          <template #actions
            ><span v-if="acceptExternalDrop" v-bind="handleProps(`detail:group:${group.id}`, group.title)"
              >⠿</span
            ></template
          >
        </RecordContentSectionHeading>
      </template>
      <template #field-actions="{ field }">
        <span
          v-if="acceptExternalDrop"
          v-bind="handleProps(`detail:field:${field.fieldName}`, field.label)"
          :data-ui-drop-key="`detail:field:${field.fieldName}`"
          >⠿</span
        >
      </template>
    </RecordDetailFields>
    <div
      v-if="acceptExternalDrop && !renderedFieldNames.length && !renderedForm.groups.length"
      class="page-composer-drop-zone"
      data-ui-drop-key="detail:container:form"
      data-composer-target="detail:container:form"
    >
      拖入字段
    </div>
    <RecordDetailExtensionSection
      v-for="relation in detailRelations"
      :key="relation.code"
      :title="relation.title ?? relation.code"
      kind="relation"
      :heading-attributes="{
        'data-composer-target': `detail:relation:${relation.code}`,
        'data-ui-drop-key': `detail:relation:${relation.code}`,
        tabindex: 0,
      }"
    >
      <template #actions>
        <span
          v-if="acceptExternalDrop"
          v-bind="handleProps(`detail:relation:${relation.code}`, relation.title ?? relation.code)"
          :data-ui-drop-key="`detail:relation:${relation.code}`"
          >⠿</span
        >
      </template>
      <RecordRelationTable
        v-if="relation.listProjection?.fields?.length"
        :columns="
          relationColumns(relation).map((column) => ({
            fieldName: column.key,
            title: column.title,
            width: typeof column.width === 'number' ? column.width : undefined,
            align: column.align,
          }))
        "
        :rows="relationEditorRows(relation)"
      >
        <template #header="{ column }">
          <span
            class="page-composer-column-heading"
            :data-page-composition-layout-key="`detail:relation:${relation.code}:header:${column.fieldName}`"
            :data-ui-drop-key="`detail:relation:${relation.code}:header:${column.fieldName}`"
            @dblclick.stop="emit('configureRelationField', relation.code, column.fieldName)"
            @keydown.enter.self.stop.prevent="emit('configureRelationField', relation.code, column.fieldName)"
            tabindex="0"
            ><span
              v-if="acceptExternalDrop"
              v-bind="
                handleProps(
                  `detail:relation:${relation.code}:header:${column.fieldName}`,
                  column.title ?? column.fieldName,
                )
              "
              >⠿</span
            >{{ column.title }}</span
          >
        </template>
        <template #cell="{ column, row }">
          <div
            :data-page-composition-layout-key="`detail:relation:${relation.code}:field:${column.fieldName}`"
          >
            <RecordRelationValue
              :field="
                resolveRecordFormFieldState(column.fieldName, {
                  fields: relationFields(relation),
                  record: row,
                })
              "
              :record="row"
            />
          </div>
        </template>
      </RecordRelationTable>
      <div
        v-if="acceptExternalDrop && !relation.listProjection?.fields?.length"
        class="page-composer-drop-zone"
        :data-composer-target="`detail:relation:${relation.code}:end`"
        :data-ui-drop-key="`detail:relation:${relation.code}:end`"
      >
        拖入此子表的字段
      </div>
      <UiEmpty v-if="!relation.listProjection?.fields?.length" description="尚未选择子表展示字段" />
    </RecordDetailExtensionSection>
    <div
      v-if="acceptExternalDrop"
      class="page-composer-drop-zone"
      data-composer-target="relations:end"
      data-ui-drop-key="relations:end"
      tabindex="0"
    >
      拖入子表
    </div>
  </section>

  <section
    v-else
    ref="previewRoot"
    class="page-composition-descriptor-preview"
    data-testid="page-composer-edit-preview"
    tabindex="0"
    data-composer-drop-target="form"
  >
    <div
      v-if="groupOutline"
      class="page-composer-group-drag-outline"
      :style="groupOutline"
      aria-hidden="true"
    />
    <div
      v-if="feedback && !transientPlacement"
      class="page-composer-drop-indicator"
      :class="{
        'page-composer-drop-indicator--rejected': feedback.rejected,
        'page-composer-drop-indicator--transient': transientPlacement,
      }"
      :style="{
        left: `${feedback.left}px`,
        top: `${feedback.top}px`,
        width: `${feedback.width}px`,
        height: `${feedback.height}px`,
      }"
      role="status"
    >
      <span>{{ feedback.title }}</span>
    </div>
    <div
      ref="formActionRoot"
      class="page-composition-action-preview page-composition-action-preview--form"
      :class="actionDropClass(formActionDrag.feedback.value)"
      data-ui-drop-root
      tabindex="0"
    >
      <UiActionButton disabled icon-name="close" title="模板固定入口：放弃编辑并退出表单"
        >取消</UiActionButton
      >
      <TransitionGroup name="page-composer-action-layout" tag="span" class="page-composer-action-layout">
        <span
          v-for="action in actionItems(formActionDrag.stagedActionCodes.value, 'form')"
          @dblclick.stop="emit('configureAction', 'form', action.actionCode)"
          :key="action.actionCode"
          :data-page-action-key="action.actionCode"
          :class="[
            actionDropItemClass(formActionDrag.feedback.value, action.actionCode),
            {
              'page-composition-action-preview__button--transient': isTransientAction(
                formActionDrag,
                action.actionCode,
              ),
            },
          ]"
        >
          <span v-bind="formActionDrag.dragHandleProps(action.actionCode, action.title ?? action.actionCode)"
            >⠿</span
          >
          <UiActionButton
            emphasis="primary"
            :disabled="action.bindingPending"
            :intent="action.actionCode === 'delete' ? 'danger' : 'normal'"
            :icon-name="action.iconName"
            >{{ action.title ?? action.actionCode }}</UiActionButton
          >
        </span>
      </TransitionGroup>
    </div>
    <UiEmpty v-if="isEditEmpty" description="当前草稿尚未配置编辑字段或关联子表" />
    <RecordFormGrid as="div" surface="record">
      <RecordFormFields
        :record="formRecordWithTransient"
        :fields="renderedFormFields"
        :field-names="renderedFieldNames.filter((name) => renderedFormFields.has(name))"
        :form-session-key="`page-composer:${moduleAlias}`"
        layout-transition-prefix="edit"
        @update:field="updateFormField"
        @reference-projections-change="updateReferenceProjections"
      >
        <template #before-field="{ field }">
          <template v-for="group in emptyGroupsBefore(field.fieldName)" :key="group.id">
            <RecordContentSectionHeading
              class="page-composer-empty-group"
              tabindex="0"
              :title="group.title"
              :subtitle="group.subtitle"
              :data-page-composition-layout-key="`edit:group:${group.id}`"
              :data-composer-target="`edit:group:${group.id}`"
              :data-ui-drop-key="`edit:group:${group.id}`"
            >
              <template #actions>
                <span v-if="acceptExternalDrop" v-bind="handleProps(`edit:group:${group.id}`, group.title)"
                  >⠿</span
                >
              </template>
            </RecordContentSectionHeading>
            <div
              v-if="acceptExternalDrop"
              class="page-composer-drop-zone page-composer-empty-group"
              tabindex="0"
              :data-composer-target="`edit:container:group:${group.id}`"
              :data-ui-drop-key="`edit:container:group:${group.id}`"
            >
              拖入字段
            </div>
          </template>
        </template>
        <template #group-actions="{ group }">
          <span
            v-if="acceptExternalDrop && group"
            v-bind="handleProps(`edit:group:${group.groupCode}`, group.title)"
            :data-ui-drop-key="`edit:group:${group.groupCode}`"
            >⠿</span
          >
        </template>
        <template #field-actions="{ field }">
          <span
            v-if="acceptExternalDrop"
            v-bind="handleProps(`edit:field:${field.fieldName}`, field.label)"
            :data-ui-drop-key="`edit:field:${field.fieldName}`"
            :class="{
              'page-composer-external-field-preview--dragging':
                transientExternalField?.fieldName === field.fieldName,
            }"
            >⠿</span
          >
        </template>
      </RecordFormFields>
      <template v-for="group in emptyGroupsBefore()" :key="group.id">
        <RecordContentSectionHeading
          class="page-composer-empty-group"
          tabindex="0"
          :title="group.title"
          :subtitle="group.subtitle"
          :data-page-composition-layout-key="`edit:group:${group.id}`"
          :data-composer-target="`edit:group:${group.id}`"
          :data-ui-drop-key="`edit:group:${group.id}`"
        >
          <template #actions>
            <span v-if="acceptExternalDrop" v-bind="handleProps(`edit:group:${group.id}`, group.title)"
              >⠿</span
            >
          </template>
        </RecordContentSectionHeading>
        <div
          v-if="acceptExternalDrop"
          class="page-composer-drop-zone page-composer-empty-group"
          tabindex="0"
          :data-composer-target="`edit:container:group:${group.id}`"
          :data-ui-drop-key="`edit:container:group:${group.id}`"
        >
          拖入字段
        </div>
      </template>
      <div
        v-if="acceptExternalDrop && !renderedFieldNames.length && !renderedForm.groups.length"
        class="page-composer-drop-zone page-composer-empty-group"
        tabindex="0"
        data-composer-target="edit:container:form"
        data-ui-drop-key="edit:container:form"
      >
        拖入字段
      </div>
    </RecordFormGrid>
    <RecordDetailExtensionSection
      v-for="relation in detailRelations"
      :key="relation.code"
      :title="relation.title ?? relation.code"
      kind="relation"
      :heading-attributes="{
        'data-composer-target': `edit:relation:${relation.code}`,
        'data-ui-drop-key': `edit:relation:${relation.code}`,
        tabindex: 0,
      }"
    >
      <template #actions>
        <span
          v-if="acceptExternalDrop"
          v-bind="handleProps(`edit:relation:${relation.code}`, relation.title ?? relation.code)"
          :data-ui-drop-key="`edit:relation:${relation.code}`"
          >⠿</span
        >
      </template>
      <RecordRelationTable
        v-if="relation.listProjection?.fields?.length"
        :columns="
          relationColumns(relation).map((column) => ({
            fieldName: column.key,
            title: column.title,
            width: typeof column.width === 'number' ? column.width : undefined,
            align: column.align,
          }))
        "
        :rows="relationEditorRows(relation)"
      >
        <template #header="{ column }">
          <span
            class="page-composer-column-heading"
            :data-page-composition-layout-key="`edit:relation:${relation.code}:header:${column.fieldName}`"
            :data-ui-drop-key="`edit:relation:${relation.code}:header:${column.fieldName}`"
            @dblclick.stop="emit('configureRelationField', relation.code, column.fieldName)"
            @keydown.enter.self.stop.prevent="emit('configureRelationField', relation.code, column.fieldName)"
            tabindex="0"
            ><span
              v-if="acceptExternalDrop"
              v-bind="
                handleProps(
                  `edit:relation:${relation.code}:header:${column.fieldName}`,
                  column.title ?? column.fieldName,
                )
              "
              >⠿</span
            >{{ column.title }}</span
          >
        </template>
        <template #cell="{ column, row }">
          <div :data-page-composition-layout-key="`edit:relation:${relation.code}:field:${column.fieldName}`">
            <RecordFormFields
              v-if="relationFields(relation).has(column.fieldName)"
              :record="row"
              :fields="relationFields(relation)"
              :field-names="[column.fieldName]"
              :form-session-key="String(row.id)"
              :show-labels="false"
              compact
              @update:field="(name, value) => updateRelationEditorField(relation, row.id, name, value)"
            /><RecordRelationValue
              v-else
              :field="
                resolveRecordFormFieldState(column.fieldName, {
                  fields: relationFields(relation),
                  record: row,
                })
              "
              :record="row"
            />
          </div>
        </template>
      </RecordRelationTable>
      <div
        v-if="acceptExternalDrop && !relation.listProjection?.fields?.length"
        class="page-composer-drop-zone"
        :data-composer-target="`edit:relation:${relation.code}:end`"
        :data-ui-drop-key="`edit:relation:${relation.code}:end`"
      >
        拖入此子表的字段
      </div>
      <UiEmpty v-if="!relation.listProjection?.fields?.length" description="尚未选择子表展示字段" />
    </RecordDetailExtensionSection>
    <div
      v-if="acceptExternalDrop"
      class="page-composer-drop-zone"
      data-composer-target="relations:end"
      data-ui-drop-key="relations:end"
      tabindex="0"
    >
      拖入子表
    </div>
  </section>
</template>

<style scoped>
.page-composer-empty-group {
  grid-column: 1 / -1;
}
.page-composition-action-preview {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  min-height: 32px;
  position: relative;
}

.page-composition-action-preview__drag-handle {
  position: absolute;
  z-index: 1;
  top: -5px;
  right: -5px;
  padding: 1px 4px;
  cursor: grab;
  color: var(--ant-color-text-secondary);
  opacity: 0;
  background: var(--ant-color-bg-container);
  border-radius: 3px;
  transition: opacity 120ms ease-out;
  user-select: none;
}

.page-composition-action-preview [data-page-action-key] {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

/* Match field dragging: the item being carried has a thin, outward yellow boundary without
   painting over its label or button surface. */
.page-composition-action-preview [data-page-action-key].page-composer-action-drag-source {
  outline: 2px solid var(--ant-color-warning);
  outline-offset: 3px;
  border-radius: 3px;
}

.page-composer-action-layout {
  display: contents;
}
.page-composer-action-layout-move {
  transition: transform 220ms cubic-bezier(0.2, 0, 0, 1);
}

.page-composition-action-preview__drag-handle:focus-visible {
  opacity: 1;
  outline: 2px solid var(--ant-color-primary);
  outline-offset: 2px;
}

.page-composition-action-preview [data-page-action-key]:hover .page-composition-action-preview__drag-handle,
.page-composition-action-preview__drag-handle.is-dragging {
  opacity: 1;
}

.page-composition-action-preview__drag-handle.is-dragging {
  cursor: grabbing;
}

.page-composition-action-preview--page {
  grid-column: 1 / -1;
  margin-bottom: 0;
}

.page-composition-action-preview--list {
  align-items: center;
  min-height: auto;
  margin-bottom: 0;
}

.page-composition-action-preview--drop-active {
  border-radius: 4px;
  outline: 1px dashed var(--ant-color-primary);
  outline-offset: 3px;
  background: var(--muyun-primary-surface, color-mix(in srgb, var(--ant-color-primary) 7%, transparent));
}

.page-composition-action-preview--drop-rejected {
  outline: 1px dashed var(--ant-color-error);
  outline-offset: 3px;
  background: color-mix(in srgb, var(--ant-color-error) 7%, transparent);
}

.page-composition-action-preview [data-page-action-key].page-composition-action-preview__button--drop-before,
.page-composition-action-preview [data-page-action-key].page-composition-action-preview__button--drop-after {
  position: relative;
  overflow: visible;
}

.page-composition-action-preview
  [data-page-action-key].page-composition-action-preview__button--drop-before::before,
.page-composition-action-preview
  [data-page-action-key].page-composition-action-preview__button--drop-after::after {
  position: absolute;
  top: -4px;
  bottom: -4px;
  width: 3px;
  border-radius: 999px;
  background: var(--ant-color-primary);
  content: '';
}

.page-composition-action-preview
  [data-page-action-key].page-composition-action-preview__button--drop-before::before {
  left: -5px;
}
.page-composition-action-preview
  [data-page-action-key].page-composition-action-preview__button--drop-after::after {
  right: -5px;
}

.page-composition-action-preview__button--transient {
  animation: page-composer-transient-action 140ms ease-out;
}

@keyframes page-composer-transient-action {
  from {
    opacity: 0.38;
    transform: scale(0.96);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

.page-composition-descriptor-preview {
  position: relative;
  display: grid;
  align-content: start;
  gap: 16px;
  min-height: 280px;
  margin-top: 12px;
  padding: 16px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
}
.page-composition-descriptor-preview--list {
  align-content: stretch;
  grid-template-rows: minmax(280px, 1fr);
  min-height: 0;
}
.page-composition-descriptor-preview--list :deep(.page-composition-descriptor-preview__list-surface) {
  min-height: 0;
  height: 100%;
}
.page-composer-summary-configure {
  justify-self: start;
  margin-top: 8px;
}

.page-composition-descriptor-preview__runtime-note {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.page-composition-descriptor-preview__field {
  display: block;
  width: 100%;
  padding: 0;
  overflow: hidden;
  color: inherit;
  font: inherit;
  text-align: inherit;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  background: transparent;
  border: 0;
  outline: 1px solid transparent;
  outline-offset: -1px;
}

.page-composition-descriptor-preview__field:hover,
.page-composition-descriptor-preview__field:focus-visible,
.page-composition-descriptor-preview__field--selected {
  outline: 2px solid var(--muyun-primary);
  outline-offset: -2px;
  background: var(--muyun-primary-surface, var(--muyun-hover));
}

.page-composer-drop-zone {
  min-height: 28px;
  padding: 5px 8px;
  border: 1px dashed var(--muyun-border);
  color: var(--muyun-text-muted);
  font-size: 12px;
  border-radius: 4px;
}
.page-composer-column-heading {
  display: inline;
}

:deep(.page-composer-drag-handle) {
  display: inline-block;
  float: right;
  margin-left: 6px;
  padding: 0 4px;
  color: var(--muyun-text-muted);
  cursor: grab;
  user-select: none;
}
:deep(.page-composer-drag-handle:hover),
:deep(.page-composer-drag-handle:focus-visible) {
  color: var(--muyun-primary);
  background: var(--muyun-hover);
}
.page-composer-group-drag-outline {
  position: absolute;
  z-index: 4;
  box-sizing: border-box;
  border: 2px solid var(--muyun-brand-accent-base);
  border-radius: 6px;
  pointer-events: none;
}
.page-composer-column-drag-outline {
  position: absolute;
  z-index: 10;
  box-sizing: border-box;
  border: 2px solid var(--muyun-brand-accent-base);
  border-radius: 2px;
  pointer-events: none;
}
.page-composition-descriptor-preview:has(> .page-composer-group-drag-outline)
  :deep(.page-composer-drag-source::after) {
  display: none;
}
.page-composition-descriptor-preview:has(> .page-composer-column-drag-outline)
  :deep(.page-composer-drag-source::after),
.page-composition-descriptor-preview:has(> .page-composer-column-drag-outline)
  :deep(.page-composer-external-field-preview--dragging::after) {
  display: none;
}
.page-composition-descriptor-preview:has(> .page-composer-column-drag-outline)
  :deep(.page-composition-descriptor-preview__field--selected) {
  outline: none;
  background: transparent;
}
:deep(.page-composer-drag-source) {
  position: relative !important;
  z-index: 2;
  outline: none !important;
  background: transparent !important;
}
:deep(.page-composer-drag-source::after) {
  position: absolute;
  z-index: 3;
  inset: -3px;
  border: 2px solid var(--muyun-brand-accent-base);
  border-radius: inherit;
  pointer-events: none;
  content: '';
}
/* Table scrollports clip overflow; draw column highlights inside the header shell. */
:deep(.page-composer-column-heading.page-composer-drag-source::after) {
  inset: 0;
  border-radius: 2px;
}
:deep(.page-composition-descriptor-preview__field.page-composer-external-field-preview--dragging),
:deep(.record-detail-field:has(.page-composer-external-field-preview--dragging)),
:deep(.record-form-field-host:has(.page-composer-external-field-preview--dragging)) {
  position: relative;
  z-index: 2;
  outline: none !important;
  background: transparent !important;
  animation: page-composer-external-field-enter 140ms ease-out both;
}
:deep(.page-composition-descriptor-preview__field.page-composer-external-field-preview--dragging::after),
:deep(.record-detail-field:has(.page-composer-external-field-preview--dragging)::after),
:deep(.record-form-field-host:has(.page-composer-external-field-preview--dragging)::after) {
  position: absolute;
  z-index: 3;
  inset: -3px;
  border: 2px solid var(--muyun-brand-accent-base);
  border-radius: inherit;
  pointer-events: none;
  content: '';
}
@keyframes page-composer-external-field-enter {
  from {
    opacity: 0.58;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
:deep(.page-composer-drag-handle.is-dragging) {
  color: var(--ant-color-warning);
  cursor: grabbing;
}
.page-composer-drop-indicator {
  position: absolute;
  z-index: 5;
  border: 2px solid var(--muyun-primary);
  background: color-mix(in srgb, var(--muyun-primary) 8%, transparent);
  pointer-events: none;
}
.page-composer-drop-indicator--transient {
  display: grid;
  min-width: 88px;
  place-items: center start;
  padding: 0 10px;
  overflow: hidden;
  border-style: dashed;
  border-radius: 6px;
  color: var(--muyun-primary);
  background: color-mix(in srgb, var(--muyun-primary) 14%, var(--muyun-surface));
  box-shadow: 0 4px 14px color-mix(in srgb, var(--muyun-primary) 16%, transparent);
}
.page-composer-drop-indicator > span {
  position: absolute;
  top: -23px;
  left: 0;
  padding: 1px 6px;
  white-space: nowrap;
  font-size: 12px;
  color: white;
  background: var(--muyun-primary);
}
.page-composer-drop-indicator--transient > span {
  position: static;
  max-width: 100%;
  overflow: hidden;
  color: inherit;
  font-weight: 600;
  text-overflow: ellipsis;
  background: transparent;
}
.page-composer-drop-indicator--rejected {
  border-color: var(--muyun-danger-base);
}
.page-composer-drop-indicator--rejected > span {
  background: var(--muyun-danger-base);
}
.page-composer-summary-preview {
  display: flex;
  flex: 1 1 auto;
  flex-wrap: wrap;
  gap: 8px 14px;
  min-width: 0;
  padding: 0;
  color: inherit;
  background: transparent;
  text-align: left;
}
.page-composer-summary-preview > span {
  display: inline-flex;
  gap: 4px;
  white-space: nowrap;
}
.page-composer-summary-preview strong {
  font-weight: 600;
}
</style>
