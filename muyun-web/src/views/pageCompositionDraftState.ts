import { computed, ref } from 'vue';

export type PageComposerSlot = 'list' | 'form';
/** Runtime states, not independent configuration slots.  management v1 still owns only list/form. */
export type PageComposerPreviewMode = 'list' | 'query' | 'detail' | 'edit';

export interface PageComposerField {
  id: string;
  title: string;
  fieldName: string;
  /** Editor-only source diagnostic; never serialized into the page declaration. */
  unavailable?: boolean;
  fieldSpecAlias?: string;
  required?: boolean;
  /** Source visibility only; never serialized into the page declaration. */
  systemManaged?: boolean;
  platformReadOnly?: boolean;
  referenceModuleAlias?: string;
  /** A derived path through a ONE reference is display-only by platform contract. */
  referenceCardinality?: 'ONE' | 'MANY';
  expandable?: boolean;
  /** Page-node presentation only; metadata field facts are never copied or edited here. */
  properties?: PageComposerFieldProperties;
}

export interface PageComposerFieldProperties {
  label?: string;
  width?: string;
  align?: 'left' | 'center' | 'right';
  columnSpan?: 1 | 2;
  readOnly?: boolean;
}

export type PageQuerySummarySource = 'MATCHED_COUNT' | 'SUM' | 'CONTRIBUTOR' | 'GROUPED';

/** A list footer aggregate. Keys are editor-generated and stable across title changes. */
export interface PageQuerySummary {
  key: string;
  label: string;
  source: PageQuerySummarySource;
  fieldName?: string;
  contributorKey?: string;
  groupByField?: string;
}

/** The catalogue is presentation-only source data; it is never persisted into the page draft. */
export interface PageQuerySummaryCatalogTitles {
  fields?: ReadonlyArray<{ fieldName: string; title: string }>;
  contributors?: ReadonlyArray<{ contributorKey: string; title: string }>;
  groupFields?: ReadonlyArray<{ fieldName: string; title: string }>;
}

function summaryCatalogTitle(
  entries: ReadonlyArray<{ fieldName: string; title: string }> | undefined,
  fieldName: string | undefined,
  fallback: string,
) {
  if (!fieldName) return fallback;
  return entries?.find((entry) => entry.fieldName === fieldName)?.title ?? fieldName;
}

/** A concise, business-facing explanation for the tree and the summary editor. */
export function pageQuerySummaryDescription(
  summary: PageQuerySummary,
  catalog: PageQuerySummaryCatalogTitles = {},
) {
  if (summary.source === 'MATCHED_COUNT') return '记录数';
  if (summary.source === 'SUM')
    return `合计 · ${summaryCatalogTitle(catalog.fields, summary.fieldName, '未选择数值字段')}`;
  if (summary.source === 'GROUPED') {
    const groupTitle = summaryCatalogTitle(catalog.groupFields, summary.groupByField, '未选择分组字段');
    const sumTitle = summary.fieldName
      ? ` · 记录数 · ${summaryCatalogTitle(catalog.fields, summary.fieldName, '数值字段')}合计`
      : ' · 记录数';
    return `按${groupTitle}分组${sumTitle}`;
  }
  const title = catalog.contributors?.find((entry) => entry.contributorKey === summary.contributorKey)?.title;
  return `业务指标 · ${title ?? summary.contributorKey ?? '未选择'}`;
}

/** Default titles follow a user-selected source while a custom title remains the user's intent. */
export function defaultPageQuerySummaryLabel(
  summary: PageQuerySummary,
  catalog: PageQuerySummaryCatalogTitles = {},
) {
  if (summary.source === 'MATCHED_COUNT') return '记录数';
  if (summary.source === 'SUM')
    return `${summaryCatalogTitle(catalog.fields, summary.fieldName, '数值字段')}合计`;
  if (summary.source === 'GROUPED') {
    const groupTitle = summaryCatalogTitle(catalog.groupFields, summary.groupByField, '字段');
    return summary.fieldName
      ? `按${groupTitle}分组${summaryCatalogTitle(catalog.fields, summary.fieldName, '数值字段')}合计`
      : `按${groupTitle}分组统计`;
  }
  return (
    catalog.contributors?.find((entry) => entry.contributorKey === summary.contributorKey)?.title ??
    '业务指标'
  );
}

export function hasDefaultPageQuerySummaryLabel(
  summary: PageQuerySummary,
  catalog: PageQuerySummaryCatalogTitles = {},
) {
  const label = summary.label.trim();
  if (label === defaultPageQuerySummaryLabel(summary, catalog)) return true;
  // Drafts created before the terminology consolidation remain generated titles until users edit them.
  if (summary.source === 'MATCHED_COUNT') return label === '匹配记录数';
  if (summary.source === 'SUM') {
    const fieldTitle = summaryCatalogTitle(catalog.fields, summary.fieldName, '数值字段');
    return label === `${fieldTitle}求和` || label === `${fieldTitle}合计`;
  }
  if (summary.source === 'GROUPED') {
    const groupTitle = summaryCatalogTitle(catalog.groupFields, summary.groupByField, '字段');
    const fieldTitle = summaryCatalogTitle(catalog.fields, summary.fieldName, '数值字段');
    return (
      label === `按${groupTitle}统计` ||
      (summary.fieldName != null && label === `按${groupTitle}统计${fieldTitle}合计`)
    );
  }
  return false;
}

/** A direct child relation placed as an association-list component in the detail slot. */
export interface PageComposerRelation {
  id: string;
  relationCode: string;
  unavailable?: boolean;
  title: string;
  /** The child-list projection is explicit: unplaced child fields do not appear at runtime. */
  fields: PageComposerField[];
}

/** A standard runtime FormGroup: only its explicitly placed fields are rendered inside it. */
export interface PageComposerGroup {
  id: string;
  groupCode: string;
  title: string;
  subtitle?: string;
  fields: PageComposerField[];
}

export interface PageComposerNode {
  id: string;
  kind: 'slot' | 'template' | 'field' | 'group' | 'groupField' | 'relation' | 'relationField';
  title: string;
  slot: PageComposerSlot;
  field?: PageComposerField;
  relation?: PageComposerRelation;
  relationField?: PageComposerField;
  group?: PageComposerGroup;
}

export type PageComposerFormItem = { kind: 'field' | 'group'; id: string };
export type ManagementFormOrder = Array<{ field: string } | { group: string }>;

/** Complete order, with legacy drafts defaulting to fields followed by groups. */
export function orderedFormItems(
  form: PageComposerField[],
  groups: PageComposerGroup[],
  order: PageComposerFormItem[] = [],
) {
  const available: PageComposerFormItem[] = [
    ...form.map((field) => ({ kind: 'field' as const, id: field.id })),
    ...groups.map((group) => ({ kind: 'group' as const, id: group.id })),
  ];
  const availableKeys = new Set(available.map((item) => `${item.kind}:${item.id}`));
  const seen = new Set<string>();
  return [...order, ...available].filter((item) => {
    const key = `${item.kind}:${item.id}`;
    if (seen.has(key) || !availableKeys.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export interface ManagementUiTree {
  template: 'management';
  templateVersion: 1;
  props?: {
    list?: {
      searchPlaceholder: string;
    };
  };
  querySummaries?: PageQuerySummary[];
  nodes: Array<{
    slot: PageComposerSlot;
    title: string;
    fields: Array<string | { field: string; props: PageComposerFieldProperties }>;
    order?: ManagementFormOrder;
    relations?: Array<{
      relation: string;
      title: string;
      fields?: Array<string | { field: string; props: PageComposerFieldProperties }>;
    }>;
    groups?: Array<{
      group: string;
      title: string;
      subtitle?: string;
      fields: Array<string | { field: string; props: PageComposerFieldProperties }>;
    }>;
  }>;
}

/**
 * The editor deliberately owns a small, serialisable draft. It is the
 * PageDefinition / PresentationRevision editing model rather than a
 * projection of another configuration aggregate.
 */
export function createPageCompositionDraftState() {
  const listFields = ref<PageComposerField[]>([]);
  const formFields = ref<PageComposerField[]>([]);
  const formRelations = ref<PageComposerRelation[]>([]);
  const formGroups = ref<PageComposerGroup[]>([]);
  const formOrder = ref<PageComposerFormItem[]>([]);
  const querySummaries = ref<PageQuerySummary[]>([]);
  const orderedForm = computed(() => orderedFormItems(formFields.value, formGroups.value, formOrder.value));
  function placeFormItem(kind: PageComposerFormItem['kind'], id: string, index?: number) {
    const next = orderedForm.value.filter((item) => item.kind !== kind || item.id !== id);
    next.splice(Math.max(0, Math.min(index ?? next.length, next.length)), 0, { kind, id });
    formOrder.value = next;
    formFields.value = next
      .filter((item) => item.kind === 'field')
      .map((item) => formFields.value.find((field) => field.id === item.id)!);
    formGroups.value = next
      .filter((item) => item.kind === 'group')
      .map((item) => formGroups.value.find((group) => group.id === item.id)!);
  }
  function forgetFormItem(kind: PageComposerFormItem['kind'], id: string) {
    formOrder.value = orderedForm.value.filter((item) => item.kind !== kind || item.id !== id);
  }
  /** management v1 only: a template-owned quick-search prompt, not a generic JSON node. */
  const quickSearchPlaceholder = ref<string>();
  const selectedNodeId = ref<string>();
  const previewMode = ref<PageComposerPreviewMode>('list');

  function formFieldPlaced(fieldId: string) {
    return (
      formFields.value.some((field) => field.id === fieldId) ||
      formGroups.value.some((group) => group.fields.some((field) => field.id === fieldId))
    );
  }

  const nodes = computed<PageComposerNode[]>(() => [
    { id: 'slot:list', kind: 'slot', title: '列表', slot: 'list' },
    { id: 'template:list:quick-search', kind: 'template', title: '快速查询', slot: 'list' },
    ...listFields.value.map((field) => ({
      id: `list:${field.id}`,
      kind: 'field' as const,
      title: field.title,
      slot: 'list' as const,
      field,
    })),
    { id: 'slot:form', kind: 'slot', title: '详情 / 表单', slot: 'form' },
    ...orderedForm.value.flatMap<PageComposerNode>((item) => {
      if (item.kind === 'field') {
        const field = formFields.value.find((field) => field.id === item.id)!;
        return [{ id: `form:${field.id}`, kind: 'field', title: field.title, slot: 'form', field }];
      }
      const group = formGroups.value.find((group) => group.id === item.id)!;
      return [
        { id: `form:group:${group.id}`, kind: 'group', title: group.title, slot: 'form', group },
        ...group.fields.map((field) => ({
          id: `form:group:${group.id}:field:${field.id}`,
          kind: 'groupField' as const,
          title: field.title,
          slot: 'form' as const,
          field,
          group,
        })),
      ];
    }),
    ...formRelations.value.map((relation) => ({
      id: `form:relation:${relation.id}`,
      kind: 'relation' as const,
      title: relation.title,
      slot: 'form' as const,
      relation,
    })),
    ...formRelations.value.flatMap((relation) =>
      relation.fields.map((field) => ({
        id: `form:relation:${relation.id}:field:${field.id}`,
        kind: 'relationField' as const,
        title: field.title,
        slot: 'form' as const,
        relation,
        relationField: field,
      })),
    ),
  ]);

  const selectedNode = computed(() => nodes.value.find((node) => node.id === selectedNodeId.value));

  function addField(field: PageComposerField, slot: PageComposerSlot = 'list', targetIndex?: number) {
    const target = slot === 'list' ? listFields : formFields;
    // A form field has exactly one presentation placement: root form or one FormGroup.
    // Copying a metadata source must never create a second projection that the template would reject.
    if (
      !target.value.some((candidate) => candidate.id === field.id) &&
      !(slot === 'form' && formFieldPlaced(field.id))
    ) {
      const next = [...target.value];
      next.splice(
        Math.max(0, Math.min(targetIndex ?? next.length, next.length)),
        0,
        placedField(field, slot === 'form'),
      );
      target.value = next;
      if (slot === 'form') placeFormItem('field', field.id, targetIndex);
    }
    const existingGroup =
      slot === 'form'
        ? formGroups.value.find((group) => group.fields.some((candidate) => candidate.id === field.id))
        : undefined;
    selectedNodeId.value = existingGroup
      ? `form:group:${existingGroup.id}:field:${field.id}`
      : `${slot}:${field.id}`;
    previewMode.value = slot === 'list' ? 'list' : 'edit';
  }

  function addFormRelation(relation: PageComposerRelation) {
    if (!formRelations.value.some((candidate) => candidate.relationCode === relation.relationCode)) {
      formRelations.value = [...formRelations.value, { ...relation, fields: [...relation.fields] }];
    }
    selectedNodeId.value = `form:relation:${relation.id}`;
    previewMode.value = 'detail';
  }

  function moveFormRelation(relationId: string, targetIndex: number) {
    const relation = formRelations.value.find((item) => item.id === relationId);
    if (!relation) return;
    const remaining = formRelations.value.filter((item) => item.id !== relationId);
    remaining.splice(Math.max(0, Math.min(targetIndex, remaining.length)), 0, relation);
    formRelations.value = remaining;
    selectedNodeId.value = `form:relation:${relationId}`;
  }

  function addFormGroup() {
    const usedIdentifiers = new Set(formGroups.value.flatMap((group) => [group.id, group.groupCode]));
    let number = 1;
    while (usedIdentifiers.has(`group_${number}`)) number += 1;
    const group = {
      id: `group_${number}`,
      groupCode: `group_${number}`,
      title: `分组 ${number}`,
      fields: [],
    };
    formGroups.value = [...formGroups.value, group];
    placeFormItem('group', group.id);
    selectedNodeId.value = `form:group:${group.id}`;
    previewMode.value = 'edit';
  }

  function moveFormFieldToGroup(fieldId: string, groupId: string, targetIndex?: number) {
    const field = formFields.value.find((candidate) => candidate.id === fieldId);
    if (!field || !formGroups.value.some((group) => group.id === groupId)) return;
    forgetFormItem('field', fieldId);
    formFields.value = formFields.value.filter((candidate) => candidate.id !== fieldId);
    formGroups.value = formGroups.value.map((group) => {
      if (group.id !== groupId || group.fields.some((candidate) => candidate.id === fieldId)) return group;
      const fields = [...group.fields];
      fields.splice(Math.max(0, Math.min(targetIndex ?? fields.length, fields.length)), 0, field);
      return { ...group, fields };
    });
    selectedNodeId.value = `form:group:${groupId}:field:${fieldId}`;
    previewMode.value = 'edit';
  }

  function moveGroupFieldToForm(groupId: string, fieldId: string, targetIndex?: number) {
    const group = formGroups.value.find((candidate) => candidate.id === groupId);
    const field = group?.fields.find((candidate) => candidate.id === fieldId);
    if (!group || !field) return;
    formGroups.value = formGroups.value.map((candidate) =>
      candidate.id === groupId
        ? { ...candidate, fields: candidate.fields.filter((item) => item.id !== fieldId) }
        : candidate,
    );
    const fields = [...formFields.value];
    fields.splice(Math.max(0, Math.min(targetIndex ?? fields.length, fields.length)), 0, field);
    formFields.value = fields;
    placeFormItem('field', fieldId, targetIndex);
    selectedNodeId.value = `form:${fieldId}`;
    previewMode.value = 'edit';
  }

  function moveGroupField(groupId: string, fieldId: string, targetIndex?: number) {
    const group = formGroups.value.find((candidate) => candidate.id === groupId);
    const index = group?.fields.findIndex((candidate) => candidate.id === fieldId) ?? -1;
    if (!group || index < 0) return;
    const fields = group.fields.filter((candidate) => candidate.id !== fieldId);
    fields.splice(Math.max(0, Math.min(targetIndex ?? fields.length, fields.length)), 0, group.fields[index]);
    formGroups.value = formGroups.value.map((candidate) =>
      candidate.id === groupId ? { ...candidate, fields } : candidate,
    );
    selectedNodeId.value = `form:group:${groupId}:field:${fieldId}`;
  }

  function moveGroupFieldToGroup(
    sourceGroupId: string,
    fieldId: string,
    targetGroupId: string,
    targetIndex?: number,
  ) {
    if (sourceGroupId === targetGroupId) {
      moveGroupField(sourceGroupId, fieldId, targetIndex);
      return;
    }
    const source = formGroups.value.find((group) => group.id === sourceGroupId);
    const field = source?.fields.find((candidate) => candidate.id === fieldId);
    const target = formGroups.value.find((group) => group.id === targetGroupId);
    if (!source || !field || !target || target.fields.some((candidate) => candidate.id === fieldId)) return;
    formGroups.value = formGroups.value.map((group) => {
      if (group.id === sourceGroupId)
        return { ...group, fields: group.fields.filter((candidate) => candidate.id !== fieldId) };
      if (group.id === targetGroupId) {
        const fields = [...group.fields];
        fields.splice(Math.max(0, Math.min(targetIndex ?? fields.length, fields.length)), 0, field);
        return { ...group, fields };
      }
      return group;
    });
    selectedNodeId.value = `form:group:${targetGroupId}:field:${fieldId}`;
    previewMode.value = 'edit';
  }

  function updateFormGroup(groupId: string, title: string, subtitle?: string) {
    const normalizedTitle = title.trim();
    if (!normalizedTitle) return;
    formGroups.value = formGroups.value.map((group) =>
      group.id === groupId
        ? { ...group, title: normalizedTitle, subtitle: subtitle?.trim() || undefined }
        : group,
    );
  }

  function moveFormGroup(groupId: string, targetIndex?: number) {
    if (!formGroups.value.some((group) => group.id === groupId)) return;
    placeFormItem('group', groupId, targetIndex);
    selectedNodeId.value = `form:group:${groupId}`;
    previewMode.value = 'edit';
  }

  function addFormRelationField(relation: PageComposerRelation, field: PageComposerField) {
    const existing = formRelations.value.find(
      (candidate) => candidate.relationCode === relation.relationCode,
    );
    if (!existing) {
      formRelations.value = [...formRelations.value, { ...relation, fields: [placedField(field, true)] }];
    } else if (!existing.fields.some((candidate) => candidate.id === field.id)) {
      formRelations.value = formRelations.value.map((candidate) =>
        candidate.relationCode === relation.relationCode
          ? { ...candidate, fields: [...candidate.fields, placedField(field, true)] }
          : candidate,
      );
    }
    selectedNodeId.value = `form:relation:${relation.id}:field:${field.id}`;
    previewMode.value = 'detail';
  }

  function moveFormRelationField(relationId: string, fieldId: string, targetIndex?: number) {
    const relation = formRelations.value.find((candidate) => candidate.id === relationId);
    const sourceIndex = relation?.fields.findIndex((candidate) => candidate.id === fieldId) ?? -1;
    if (!relation || sourceIndex < 0) return;
    const fields = relation.fields.filter((candidate) => candidate.id !== fieldId);
    fields.splice(
      Math.max(0, Math.min(targetIndex ?? fields.length, fields.length)),
      0,
      relation.fields[sourceIndex],
    );
    formRelations.value = formRelations.value.map((candidate) =>
      candidate.id === relationId ? { ...candidate, fields } : candidate,
    );
    selectedNodeId.value = `form:relation:${relationId}:field:${fieldId}`;
    // Reordering is an editor gesture. Do not force the preview back to detail
    // after the user intentionally opened the editable relation-table preview.
    previewMode.value = 'edit';
  }

  function removeSelectedField() {
    const node = selectedNode.value;
    if (node?.group) {
      if (node.kind === 'groupField' && node.field) {
        formGroups.value = formGroups.value.map((group) =>
          group.id === node.group?.id
            ? { ...group, fields: group.fields.filter((field) => field.id !== node.field?.id) }
            : group,
        );
        selectedNodeId.value = `form:group:${node.group.id}`;
        return;
      }
      forgetFormItem('group', node.group.id);
      formGroups.value = formGroups.value.filter((group) => group.id !== node.group?.id);
      selectedNodeId.value = 'slot:form';
      return;
    }
    if (node?.relation) {
      if (node.relationField) {
        formRelations.value = formRelations.value.map((relation) =>
          relation.id === node.relation?.id
            ? { ...relation, fields: relation.fields.filter((field) => field.id !== node.relationField?.id) }
            : relation,
        );
        selectedNodeId.value = `form:relation:${node.relation.id}`;
        return;
      }
      formRelations.value = formRelations.value.filter((relation) => relation.id !== node.relation?.id);
      selectedNodeId.value = 'slot:form';
      return;
    }
    if (!node?.field) return;
    if (node.slot === 'list')
      listFields.value = listFields.value.filter((field) => field.id !== node.field?.id);
    else {
      forgetFormItem('field', node.field.id);
      formFields.value = formFields.value.filter((field) => field.id !== node.field?.id);
    }
    selectedNodeId.value = `slot:${node.slot}`;
  }

  function moveSelectedField(offset: -1 | 1) {
    const node = selectedNode.value;
    if (!node?.field) return;
    if (node.slot === 'form' && node.kind === 'field') {
      const index = orderedForm.value.findIndex(
        (item) => item.kind === 'field' && item.id === node.field?.id,
      );
      const nextIndex = index + offset;
      if (index >= 0 && nextIndex >= 0 && nextIndex < orderedForm.value.length)
        placeFormItem('field', node.field.id, nextIndex);
      return;
    }
    const target = node.slot === 'list' ? listFields : formFields;
    const index = target.value.findIndex((field) => field.id === node.field?.id);
    const nextIndex = index + offset;
    if (index < 0 || nextIndex < 0 || nextIndex >= target.value.length) return;
    const next = [...target.value];
    [next[index], next[nextIndex]] = [next[nextIndex], next[index]];
    target.value = next;
  }

  /** Moves a component through the UI tree without allowing duplicates in one slot. */
  function moveField(fieldId: string, from: PageComposerSlot, to: PageComposerSlot, targetIndex?: number) {
    const source = from === 'list' ? listFields : formFields;
    const field = source.value.find((candidate) => candidate.id === fieldId);
    if (!field) return;
    const destination = to === 'list' ? listFields : formFields;
    if (from !== to && destination.value.some((candidate) => candidate.id === fieldId)) return;
    const nextSource = source.value.filter((candidate) => candidate.id !== fieldId);
    const nextDestination = from === to ? nextSource : [...destination.value];
    const index = Math.max(0, Math.min(targetIndex ?? nextDestination.length, nextDestination.length));
    nextDestination.splice(index, 0, placedField(field, to === 'form'));
    if (from === to) source.value = nextDestination;
    else {
      source.value = nextSource;
      destination.value = nextDestination;
    }
    if (to === 'form') placeFormItem('field', fieldId, targetIndex);
    else if (from === 'form') forgetFormItem('field', fieldId);
    selectedNodeId.value = `${to}:${field.id}`;
    previewMode.value = to === 'list' ? 'list' : 'edit';
  }

  function selectNode(node: PageComposerNode) {
    selectedNodeId.value = node.id;
    previewMode.value = previewModeFor(node);
  }

  /** Updates only the selected page-node properties; source metadata remains immutable in this workspace. */
  function updateSelectedFieldProperties(properties: PageComposerFieldProperties) {
    const node = selectedNode.value;
    if (node?.kind === 'relationField' && node.relation && node.relationField) {
      formRelations.value = formRelations.value.map((relation) =>
        relation.id === node.relation?.id
          ? {
              ...relation,
              fields: relation.fields.map((field) =>
                field.id === node.relationField?.id
                  ? { ...field, properties: compactProperties(properties) }
                  : field,
              ),
            }
          : relation,
      );
      return;
    }
    if (!node?.field) return;
    if (node.kind === 'groupField' && node.group) {
      formGroups.value = formGroups.value.map((group) => {
        if (group.id !== node.group?.id) return group;
        return {
          ...group,
          fields: group.fields.map((field) =>
            field.id === node.field?.id ? { ...field, properties: compactProperties(properties) } : field,
          ),
        };
      });
      return;
    }
    const target = node.slot === 'list' ? listFields : formFields;
    const index = target.value.findIndex((field) => field.id === node.field?.id);
    if (index < 0) return;
    const next = [...target.value];
    next[index] = { ...next[index], properties: compactProperties(properties) };
    target.value = next;
  }

  function updateQuickSearchPlaceholder(value: string | undefined) {
    quickSearchPlaceholder.value = value?.trim() || undefined;
  }

  function replaceQuerySummaries(summaries: readonly PageQuerySummary[]) {
    const keys = new Set<string>();
    querySummaries.value = summaries.map((summary, index) => {
      const key = summary.key.trim();
      const stableKey = key && !keys.has(key) ? key : `invalid_summary_${index + 1}`;
      keys.add(stableKey);
      return {
        key: stableKey,
        label: summary.label.trim(),
        source: summary.source,
        ...((summary.source === 'SUM' || summary.source === 'GROUPED') && summary.fieldName?.trim()
          ? { fieldName: summary.fieldName.trim() }
          : {}),
        ...(summary.source === 'CONTRIBUTOR' && summary.contributorKey?.trim()
          ? { contributorKey: summary.contributorKey.trim() }
          : {}),
        ...(summary.source === 'GROUPED' && summary.groupByField?.trim()
          ? { groupByField: summary.groupByField.trim() }
          : {}),
      };
    });
  }

  /** Rehydrates the editor from the persisted template contract, not the legacy UI-set aggregate. */
  function replaceFields(next: {
    list: PageComposerField[];
    form: PageComposerField[];
    relations?: PageComposerRelation[];
    groups?: PageComposerGroup[];
    order?: PageComposerFormItem[];
  }) {
    formOrder.value = next.order ?? [];
    listFields.value = uniqueFields(next.list);
    formFields.value = uniqueFields(next.form);
    formRelations.value = [...(next.relations ?? [])];
    formGroups.value = (next.groups ?? []).map((group) => ({
      ...group,
      fields: uniqueFields(group.fields),
    }));
    normalizeFormFieldPlacements();
    selectedNodeId.value = undefined;
  }

  /** Repairs impossible legacy/transient states by retaining the first visible form placement. */
  function normalizeFormFieldPlacements() {
    const seen = new Set<string>();
    const normalizedForm = formFields.value.filter((field) => {
      if (seen.has(field.id)) return false;
      seen.add(field.id);
      return true;
    });
    const normalizedGroups = formGroups.value.map((group) => ({
      ...group,
      fields: group.fields.filter((field) => {
        if (seen.has(field.id)) return false;
        seen.add(field.id);
        return true;
      }),
    }));
    const changed =
      normalizedForm.length !== formFields.value.length ||
      normalizedGroups.some((group, index) => group.fields.length !== formGroups.value[index]?.fields.length);
    if (!changed) return false;
    formFields.value = normalizedForm;
    formGroups.value = normalizedGroups;
    return true;
  }

  function toManagementUiTree(titles?: Partial<Record<PageComposerSlot, string>>): ManagementUiTree {
    const props = quickSearchPlaceholder.value
      ? { list: { searchPlaceholder: quickSearchPlaceholder.value } }
      : undefined;
    return {
      template: 'management',
      templateVersion: 1,
      ...(props ? { props } : {}),
      ...(querySummaries.value.length ? { querySummaries: querySummaries.value } : {}),
      nodes: [
        {
          slot: 'list',
          title: titles?.list ?? '列表',
          fields: listFields.value.map((field) => toPersistedField(field, false)),
        },
        {
          slot: 'form',
          title: titles?.form ?? '详情 / 表单',
          fields: orderedForm.value
            .filter((item) => item.kind === 'field')
            .map((item) => toPersistedField(formFields.value.find((field) => field.id === item.id)!)),
          ...(formGroups.value.length
            ? {
                order: orderedForm.value.map((item) =>
                  item.kind === 'field'
                    ? { field: formFields.value.find((field) => field.id === item.id)!.fieldName }
                    : { group: formGroups.value.find((group) => group.id === item.id)!.groupCode },
                ),
              }
            : {}),
          ...(formRelations.value.length
            ? {
                relations: formRelations.value.map((relation) => ({
                  relation: relation.relationCode,
                  title: relation.title,
                  ...(relation.fields.length
                    ? { fields: relation.fields.map((field) => toPersistedField(field, false)) }
                    : {}),
                })),
              }
            : {}),
          ...(formGroups.value.length
            ? {
                groups: formGroups.value.map((group) => ({
                  group: group.groupCode,
                  title: group.title,
                  ...(group.subtitle ? { subtitle: group.subtitle } : {}),
                  fields: group.fields.map((field) => toPersistedField(field, true)),
                })),
              }
            : {}),
        },
      ],
    };
  }

  return {
    listFields,
    formFields,
    formRelations,
    formGroups,
    formOrder,
    querySummaries,
    orderedForm,
    quickSearchPlaceholder,
    nodes,
    selectedNodeId,
    selectedNode,
    previewMode,
    addField,
    addFormRelation,
    addFormGroup,
    moveFormFieldToGroup,
    moveGroupFieldToForm,
    moveGroupField,
    moveGroupFieldToGroup,
    updateFormGroup,
    moveFormGroup,
    moveFormRelation,
    addFormRelationField,
    moveFormRelationField,
    removeSelectedField,
    moveSelectedField,
    moveField,
    selectNode,
    updateSelectedFieldProperties,
    updateQuickSearchPlaceholder,
    replaceQuerySummaries,
    replaceFields,
    normalizeFormFieldPlacements,
    toManagementUiTree,
  };
}

function uniqueFields(fields: PageComposerField[]) {
  const seen = new Set<string>();
  return fields.filter((field) => {
    if (seen.has(field.id)) return false;
    seen.add(field.id);
    return true;
  });
}

function previewModeFor(node: PageComposerNode): PageComposerPreviewMode {
  if (node.kind === 'template') return 'query';
  if (node.slot === 'list') return 'list';
  // Form fields, including fields placed inside groups, are edited through the real form renderer;
  // association lists remain a detail view.
  return node.kind === 'field' || node.kind === 'groupField' ? 'edit' : 'detail';
}

function placedField(field: PageComposerField, includeReadOnly = false): PageComposerField {
  const source = { ...field };
  delete source.properties;
  const properties = {
    ...(field.properties ?? {}),
    ...(includeReadOnly && field.platformReadOnly ? { readOnly: true } : {}),
  };
  if (!includeReadOnly) delete properties.readOnly;
  return {
    ...source,
    ...(Object.keys(properties).length ? { properties } : {}),
  };
}

function compactProperties(properties: PageComposerFieldProperties): PageComposerFieldProperties | undefined {
  const compact = Object.fromEntries(
    Object.entries(properties).filter(([, value]) => value !== undefined && value !== '' && value !== false),
  ) as PageComposerFieldProperties;
  return Object.keys(compact).length ? compact : undefined;
}

function toPersistedField(
  field: PageComposerField,
  includeReadOnly = true,
): string | { field: string; props: PageComposerFieldProperties } {
  const properties = { ...(field.properties ?? {}) };
  if (!includeReadOnly) delete properties.readOnly;
  const compact = compactProperties(properties);
  return compact ? { field: field.fieldName, props: compact } : field.fieldName;
}
