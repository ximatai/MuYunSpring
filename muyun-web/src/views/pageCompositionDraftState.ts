import { computed, ref } from 'vue';

export type PageComposerSlot = 'list' | 'form';

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
  const previewMode = ref<'list' | 'detail'>('list');

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

  function addField(field: PageComposerField, slot: PageComposerSlot = 'list') {
    const target = slot === 'list' ? listFields : formFields;
    if (!target.value.some((candidate) => candidate.id === field.id)) target.value = [...target.value, field];
    selectedNodeId.value = `${slot}:${field.id}`;
    previewMode.value = slot === 'list' ? 'list' : 'detail';
  }

  function removeSelectedField() {
    const node = selectedNode.value;
    if (!node?.field) return;
    if (node.slot === 'list') listFields.value = listFields.value.filter((field) => field.id !== node.field?.id);
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

  function selectNode(node: PageComposerNode) {
    selectedNodeId.value = node.id;
    previewMode.value = node.slot === 'list' ? 'list' : 'detail';
  }

  return {
    listFields,
    formFields,
    nodes,
    selectedNodeId,
    selectedNode,
    previewMode,
    addField,
    removeSelectedField,
    moveSelectedField,
    selectNode,
  };
}
