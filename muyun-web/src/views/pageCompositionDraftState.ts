import { computed, ref } from 'vue';

export type PageComposerSlot = 'list' | 'form';
export type PageComposerPreviewMode = 'list' | 'detail' | 'card';

export interface PageComposerField {
  id: string;
  title: string;
  fieldName: string;
  fieldSpecAlias?: string;
  required?: boolean;
}

export interface PageComposerNode {
  id: string;
  kind: 'slot' | 'field';
  title: string;
  slot: PageComposerSlot;
  field?: PageComposerField;
}

export interface ManagementUiTree {
  template: 'management';
  templateVersion: 1;
  nodes: Array<{
    slot: PageComposerSlot;
    title: string;
    fields: string[];
  }>;
}

/**
 * The editor deliberately owns a small, serialisable draft.  It does not
 * project the legacy UiSet / UiConfig aggregate, so replacing the transport
 * with PageDefinition/PresentationRevision APIs will not change interaction
 * semantics.
 */
export function createPageCompositionDraftState() {
  const listFields = ref<PageComposerField[]>([]);
  const formFields = ref<PageComposerField[]>([]);
  const selectedNodeId = ref<string>();
  const previewMode = ref<PageComposerPreviewMode>('list');
  // 卡片是列表数据的另一种呈现，不建立第三份字段配置。
  const cardFields = computed(() => listFields.value);

  const nodes = computed<PageComposerNode[]>(() => [
    { id: 'slot:list', kind: 'slot', title: '列表', slot: 'list' },
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
  ]);

  const selectedNode = computed(() => nodes.value.find((node) => node.id === selectedNodeId.value));

  function addField(field: PageComposerField, slot: PageComposerSlot = 'list', targetIndex?: number) {
    const target = slot === 'list' ? listFields : formFields;
    if (!target.value.some((candidate) => candidate.id === field.id)) {
      const next = [...target.value];
      next.splice(Math.max(0, Math.min(targetIndex ?? next.length, next.length)), 0, field);
      target.value = next;
    }
    selectedNodeId.value = `${slot}:${field.id}`;
    previewMode.value = slot === 'list' ? 'list' : 'detail';
  }

  function removeSelectedField() {
    const node = selectedNode.value;
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
    const sourceIndex = source.value.findIndex((candidate) => candidate.id === fieldId);
    const nextSource = source.value.filter((candidate) => candidate.id !== fieldId);
    const nextDestination = from === to ? nextSource : [...destination.value];
    const requestedIndex = targetIndex ?? nextDestination.length;
    const adjustedIndex = from === to && sourceIndex < requestedIndex ? requestedIndex - 1 : requestedIndex;
    const index = Math.max(0, Math.min(adjustedIndex, nextDestination.length));
    nextDestination.splice(index, 0, field);
    if (from === to) source.value = nextDestination;
    else {
      source.value = nextSource;
      destination.value = nextDestination;
    }
    selectedNodeId.value = `${to}:${field.id}`;
    previewMode.value = to === 'list' ? 'list' : 'detail';
  }

  function selectNode(node: PageComposerNode) {
    selectedNodeId.value = node.id;
    previewMode.value = node.slot === 'list' ? 'list' : 'detail';
  }

  /** Rehydrates the editor from the persisted template contract, not the legacy UI-set aggregate. */
  function replaceFields(next: { list: PageComposerField[]; form: PageComposerField[] }) {
    listFields.value = [...next.list];
    formFields.value = [...next.form];
    selectedNodeId.value = undefined;
  }

  function toManagementUiTree(titles?: Partial<Record<PageComposerSlot, string>>): ManagementUiTree {
    return {
      template: 'management',
      templateVersion: 1,
      nodes: [
        {
          slot: 'list',
          title: titles?.list ?? '列表',
          fields: listFields.value.map((field) => field.fieldName),
        },
        {
          slot: 'form',
          title: titles?.form ?? '详情 / 表单',
          fields: formFields.value.map((field) => field.fieldName),
        },
      ],
    };
  }

  return {
    listFields,
    formFields,
    cardFields,
    nodes,
    selectedNodeId,
    selectedNode,
    previewMode,
    addField,
    removeSelectedField,
    moveSelectedField,
    moveField,
    selectNode,
    replaceFields,
    toManagementUiTree,
  };
}
