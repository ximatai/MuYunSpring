import { computed, ref } from 'vue';

export type PageComposerSlot = 'list' | 'form';
/** Runtime states, not independent configuration slots.  management v1 still owns only list/form. */
export type PageComposerPreviewMode = 'list' | 'query' | 'detail' | 'edit';

export interface PageComposerField {
  id: string;
  title: string;
  fieldName: string;
  fieldSpecAlias?: string;
  required?: boolean;
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

/** A direct child relation placed as an association-list component in the detail slot. */
export interface PageComposerRelation {
  id: string;
  relationCode: string;
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

export interface ManagementUiTree {
  template: 'management';
  templateVersion: 1;
  props?: {
    list?: {
      searchPlaceholder: string;
    };
  };
  nodes: Array<{
    slot: PageComposerSlot;
    title: string;
    fields: Array<string | { field: string; props: PageComposerFieldProperties }>;
    relations?: Array<{ relation: string; title: string; fields?: string[] }>;
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
    ...formFields.value.map((field) => ({
      id: `form:${field.id}`,
      kind: 'field' as const,
      title: field.title,
      slot: 'form' as const,
      field,
    })),
    ...formGroups.value.flatMap((group) => [
      {
        id: `form:group:${group.id}`,
        kind: 'group' as const,
        title: group.title,
        slot: 'form' as const,
        group,
      },
      ...group.fields.map((field) => ({
        id: `form:group:${group.id}:field:${field.id}`,
        kind: 'groupField' as const,
        title: field.title,
        slot: 'form' as const,
        field,
        group,
      })),
    ]),
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
      next.splice(Math.max(0, Math.min(targetIndex ?? next.length, next.length)), 0, placedField(field));
      target.value = next;
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
    selectedNodeId.value = `form:group:${group.id}`;
    previewMode.value = 'edit';
  }

  function moveFormFieldToGroup(fieldId: string, groupId: string, targetIndex?: number) {
    const field = formFields.value.find((candidate) => candidate.id === fieldId);
    if (!field || !formGroups.value.some((group) => group.id === groupId)) return;
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
    const sourceIndex = formGroups.value.findIndex((group) => group.id === groupId);
    if (sourceIndex < 0) return;
    const groups = formGroups.value.filter((group) => group.id !== groupId);
    const index = Math.max(0, Math.min(targetIndex ?? groups.length, groups.length));
    groups.splice(index, 0, formGroups.value[sourceIndex]);
    formGroups.value = groups;
    selectedNodeId.value = `form:group:${groupId}`;
    previewMode.value = 'edit';
  }

  function addFormRelationField(relation: PageComposerRelation, field: PageComposerField) {
    const existing = formRelations.value.find(
      (candidate) => candidate.relationCode === relation.relationCode,
    );
    if (!existing) {
      formRelations.value = [...formRelations.value, { ...relation, fields: [placedField(field)] }];
    } else if (!existing.fields.some((candidate) => candidate.id === field.id)) {
      formRelations.value = formRelations.value.map((candidate) =>
        candidate.relationCode === relation.relationCode
          ? { ...candidate, fields: [...candidate.fields, placedField(field)] }
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
    else formFields.value = formFields.value.filter((field) => field.id !== node.field?.id);
    selectedNodeId.value = `slot:${node.slot}`;
  }

  function moveSelectedField(offset: -1 | 1) {
    const node = selectedNode.value;
    if (!node?.field) return;
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
    nextDestination.splice(index, 0, field);
    if (from === to) source.value = nextDestination;
    else {
      source.value = nextSource;
      destination.value = nextDestination;
    }
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

  /** Rehydrates the editor from the persisted template contract, not the legacy UI-set aggregate. */
  function replaceFields(next: {
    list: PageComposerField[];
    form: PageComposerField[];
    relations?: PageComposerRelation[];
    groups?: PageComposerGroup[];
  }) {
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
      nodes: [
        {
          slot: 'list',
          title: titles?.list ?? '列表',
          fields: listFields.value.map(toPersistedField),
        },
        {
          slot: 'form',
          title: titles?.form ?? '详情 / 表单',
          fields: formFields.value.map(toPersistedField),
          ...(formRelations.value.length
            ? {
                relations: formRelations.value.map((relation) => ({
                  relation: relation.relationCode,
                  title: relation.title,
                  ...(relation.fields.length
                    ? { fields: relation.fields.map((field) => field.fieldName) }
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
                  fields: group.fields.map(toPersistedField),
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
    addFormRelationField,
    moveFormRelationField,
    removeSelectedField,
    moveSelectedField,
    moveField,
    selectNode,
    updateSelectedFieldProperties,
    updateQuickSearchPlaceholder,
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

function placedField(field: PageComposerField): PageComposerField {
  return { ...field, properties: field.properties ? { ...field.properties } : undefined };
}

function compactProperties(properties: PageComposerFieldProperties): PageComposerFieldProperties | undefined {
  const compact = Object.fromEntries(
    Object.entries(properties).filter(([, value]) => value !== undefined && value !== '' && value !== false),
  ) as PageComposerFieldProperties;
  return Object.keys(compact).length ? compact : undefined;
}

function toPersistedField(
  field: PageComposerField,
): string | { field: string; props: PageComposerFieldProperties } {
  const properties = compactProperties(field.properties ?? {});
  return properties ? { field: field.fieldName, props: properties } : field.fieldName;
}
