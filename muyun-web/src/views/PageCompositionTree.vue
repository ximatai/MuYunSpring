<script setup lang="ts">
import { ref, watch } from 'vue';
import { VueDraggable } from 'vue-draggable-plus';
import type { SortableEvent } from 'sortablejs';
import type { PageComposerField, PageComposerGroup, PageComposerRelation } from './pageCompositionDraftState';

defineOptions({ name: 'PageCompositionTree' });

const props = withDefaults(
  defineProps<{
    listFields: PageComposerField[];
    formFields: PageComposerField[];
    formGroups: PageComposerGroup[];
    formRelations: PageComposerRelation[];
    selectedKey?: string;
    disabled?: boolean;
  }>(),
  { selectedKey: undefined, disabled: false },
);

const emit = defineEmits<{
  select: [key: string];
  'double-click': [key: string];
  'reorder-list-field': [fieldId: string, targetIndex: number];
  'reorder-form-field': [fieldId: string, targetIndex: number];
  'move-form-field-to-group': [fieldId: string, groupId: string, targetIndex: number];
  'move-group-field-to-form': [groupId: string, fieldId: string, targetIndex: number];
  'reorder-group-field': [groupId: string, fieldId: string, targetIndex: number];
  'move-group-field-to-group': [sourceGroupId: string, fieldId: string, targetGroupId: string, targetIndex: number];
  'reorder-group': [groupId: string, targetIndex: number];
  'reorder-relation-field': [relationId: string, fieldId: string, targetIndex: number];
  'metadata-drop': [target: ComposerDropTarget, nativeEvent: DragEvent];
}>();

export type ComposerDropTarget =
  | { kind: 'list' }
  | { kind: 'form' }
  | { kind: 'group'; groupId: string };

type LocalTree = {
  listFields: PageComposerField[];
  formFields: PageComposerField[];
  formGroups: PageComposerGroup[];
  formRelations: PageComposerRelation[];
};

const local = ref<LocalTree>(snapshot());
const expandedBranches = ref({ page: true, list: true, form: true });
const expandedGroups = ref<Record<string, boolean>>({});

watch(
  () => [props.listFields, props.formFields, props.formGroups, props.formRelations] as const,
  () => {
    local.value = snapshot();
  },
  { deep: true },
);

function snapshot(): LocalTree {
  return {
    listFields: [...props.listFields],
    formFields: [...props.formFields],
    formGroups: props.formGroups.map((group) => ({ ...group, fields: [...group.fields] })),
    formRelations: props.formRelations.map((relation) => ({ ...relation, fields: [...relation.fields] })),
  };
}

function select(key: string) {
  if (!props.disabled) emit('select', key);
}

function doubleClick(key: string) {
  if (!props.disabled) emit('double-click', key);
}

function isSelected(key: string) {
  return props.selectedKey === key;
}

function toggleBranch(branch: keyof typeof expandedBranches.value) {
  expandedBranches.value = { ...expandedBranches.value, [branch]: !expandedBranches.value[branch] };
}

function isGroupExpanded(groupId: string) {
  return expandedGroups.value[groupId] !== false;
}

function toggleGroup(groupId: string) {
  expandedGroups.value = { ...expandedGroups.value, [groupId]: !isGroupExpanded(groupId) };
}

function handleListEnd(event: SortableEvent) {
  const fieldId = fieldIdOf(event);
  if (fieldId != null && event.newIndex != null && event.from === event.to) {
    emit('reorder-list-field', fieldId, event.newIndex);
    return;
  }
  restoreLocalTree();
}

/** Form-field lists intentionally share one Sortable group.  The editor resolves every gesture
 * back through semantic state commands; the local list only supplies Sortable's transient DOM. */
function handleFormFieldEnd(event: SortableEvent) {
  const fieldId = fieldIdOf(event);
  const source = fieldContainerOf(event.from);
  const target = fieldContainerOf(event.to);
  if (!fieldId || !source || !target || event.newIndex == null) {
    restoreLocalTree();
    return;
  }
  if (source.kind === 'form' && target.kind === 'form') {
    emit('reorder-form-field', fieldId, event.newIndex);
  } else if (source.kind === 'form' && target.kind === 'group') {
    emit('move-form-field-to-group', fieldId, target.groupId, event.newIndex);
  } else if (source.kind === 'group' && target.kind === 'form') {
    emit('move-group-field-to-form', source.groupId, fieldId, event.newIndex);
  } else if (source.kind === 'group' && target.kind === 'group') {
    if (source.groupId === target.groupId) emit('reorder-group-field', source.groupId, fieldId, event.newIndex);
    else emit('move-group-field-to-group', source.groupId, fieldId, target.groupId, event.newIndex);
  } else {
    restoreLocalTree();
    return;
  }
}

function handleGroupEnd(event: SortableEvent) {
  const groupId = event.item?.dataset.groupId;
  if (groupId && event.newIndex != null && event.from === event.to) {
    emit('reorder-group', groupId, event.newIndex);
    return;
  }
  restoreLocalTree();
}

function handleRelationFieldEnd(relationId: string, event: SortableEvent) {
  const fieldId = fieldIdOf(event);
  if (fieldId && event.newIndex != null && event.from === event.to) {
    emit('reorder-relation-field', relationId, fieldId, event.newIndex);
    return;
  }
  restoreLocalTree();
}

/** Sortable owns a transient DOM list only.  Any rejected nested gesture must immediately return
 * to the authoritative page-draft snapshot instead of leaving a visually moved phantom node. */
function restoreLocalTree() {
  queueMicrotask(() => {
    local.value = snapshot();
  });
}

function fieldIdOf(event: SortableEvent) {
  return event.item?.dataset.fieldId;
}

function fieldContainerOf(element: HTMLElement) {
  const kind = element.dataset.composerFieldContainer;
  if (kind === 'form') return { kind: 'form' as const };
  if (kind === 'group' && element.dataset.groupId) return { kind: 'group' as const, groupId: element.dataset.groupId };
  return undefined;
}

function resolveDropTarget(target: EventTarget | null): ComposerDropTarget | undefined {
  const element = target instanceof Element ? target.closest<HTMLElement>('[data-composer-drop-target]') : undefined;
  if (!element) return undefined;
  const kind = element.dataset.composerDropTarget;
  if (kind === 'list') return { kind: 'list' };
  if (kind === 'form') return { kind: 'form' };
  if (kind === 'group' && element.dataset.groupId) return { kind: 'group', groupId: element.dataset.groupId };
  return undefined;
}

function handleExternalDragOver(event: DragEvent) {
  if (props.disabled || !resolveDropTarget(event.target)) return;
  event.preventDefault();
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
}

function handleExternalDrop(event: DragEvent) {
  const target = resolveDropTarget(event.target);
  if (props.disabled || !target) return;
  event.preventDefault();
  emit('metadata-drop', target, event);
}
</script>

<template>
  <section class="page-composition-tree" data-testid="page-composition-sortable-tree" @dragover="handleExternalDragOver" @drop="handleExternalDrop">
    <button
      class="page-composition-tree__node page-composition-tree__node--root"
      type="button"
      :aria-expanded="expandedBranches.page"
      @click="toggleBranch('page')"
    >
      <span class="page-composition-tree__caret">{{ expandedBranches.page ? '⌄' : '›' }}</span>
      <span>页面</span>
    </button>

    <template v-if="expandedBranches.page">
    <section class="page-composition-tree__branch" data-composer-drop-target="list">
      <button
        class="page-composition-tree__node page-composition-tree__node--branch-list"
        :class="{ 'is-selected': isSelected('ui:slot:list') }"
        type="button"
        :aria-expanded="expandedBranches.list"
        @click="toggleBranch('list')"
      >
        <span class="page-composition-tree__caret">{{ expandedBranches.list ? '⌄' : '›' }}</span><span>列表</span><small>标准列表</small>
      </button>
      <template v-if="expandedBranches.list">
      <button class="page-composition-tree__node page-composition-tree__node--template" :class="{ 'is-selected': isSelected('ui:template:list:quick-search') }" type="button" @click="select('ui:template:list:quick-search')" @dblclick="doubleClick('ui:template:list:quick-search')">
        <span class="page-composition-tree__indent" /><span>快速查询</span><small>可配置占位提示</small>
      </button>
      <div class="page-composition-tree__node page-composition-tree__node--container" :class="{ 'is-selected': isSelected('ui:slot:list:fields') }" role="treeitem" @click="select('ui:slot:list:fields')">
        <span class="page-composition-tree__indent" /><span>列表展示字段</span><small>{{ local.listFields.length ? '拖拽调整顺序' : '拖动字段到此处' }}</small>
      </div>
      <VueDraggable v-model="local.listFields" class="page-composition-tree__field-list" :class="{ 'is-disabled': disabled }" :disabled="disabled" :animation="150" :force-fallback="true" :fallback-on-body="true" draggable=".page-composition-tree__node--field" handle=".page-composition-tree__drag-handle" @end="handleListEnd">
        <button v-for="field in local.listFields" :key="field.id" class="page-composition-tree__node page-composition-tree__node--field" :class="{ 'is-selected': isSelected(`ui:field:list:${field.id}`) }" :data-field-id="field.id" type="button" @click="select(`ui:field:list:${field.id}`)" @dblclick="doubleClick(`ui:field:list:${field.id}`)">
          <span class="page-composition-tree__drag-handle" aria-label="拖拽调整顺序">⠿</span><span>{{ field.properties?.label ?? field.title }}</span><small>{{ field.fieldName }}</small>
        </button>
      </VueDraggable>
      </template>
    </section>

    <section class="page-composition-tree__branch" data-composer-drop-target="form">
      <button
        class="page-composition-tree__node page-composition-tree__node--branch-form"
        :class="{ 'is-selected': isSelected('ui:slot:form') }"
        type="button"
        :aria-expanded="expandedBranches.form"
        @click="toggleBranch('form')"
      >
        <span class="page-composition-tree__caret">{{ expandedBranches.form ? '⌄' : '›' }}</span><span>详情 / 表单</span>
      </button>
      <template v-if="expandedBranches.form">
      <VueDraggable v-model="local.formFields" class="page-composition-tree__field-list" :class="{ 'is-disabled': disabled }" :disabled="disabled" :animation="150" :force-fallback="true" :fallback-on-body="true" draggable=".page-composition-tree__node--field" :group="{ name: 'page-composer-form-fields', pull: true, put: true }" handle=".page-composition-tree__drag-handle" data-composer-field-container="form" @end="handleFormFieldEnd">
        <button v-for="field in local.formFields" :key="field.id" class="page-composition-tree__node page-composition-tree__node--field" :class="{ 'is-selected': isSelected(`ui:field:form:${field.id}`) }" :data-field-id="field.id" type="button" @click="select(`ui:field:form:${field.id}`)" @dblclick="doubleClick(`ui:field:form:${field.id}`)">
          <span class="page-composition-tree__drag-handle" aria-label="拖拽调整顺序">⠿</span><span>{{ field.properties?.label ?? field.title }}</span><small>{{ field.fieldName }}</small>
        </button>
      </VueDraggable>

      <VueDraggable v-model="local.formGroups" class="page-composition-tree__group-list" :class="{ 'is-disabled': disabled }" :disabled="disabled" :animation="150" :force-fallback="true" :fallback-on-body="true" draggable=".page-composition-tree__group" handle=".page-composition-tree__group-handle" @end="handleGroupEnd">
        <section v-for="group in local.formGroups" :key="group.id" class="page-composition-tree__group" data-composer-drop-target="group" :data-group-id="group.id">
          <button class="page-composition-tree__node page-composition-tree__node--group" :class="{ 'is-selected': isSelected(`ui:group:form:${group.id}`) }" type="button" :aria-expanded="isGroupExpanded(group.id)" @click="toggleGroup(group.id)" @dblclick="doubleClick(`ui:group:form:${group.id}`)">
            <span class="page-composition-tree__group-handle" aria-label="拖拽调整分组">⠿</span><span class="page-composition-tree__caret">{{ isGroupExpanded(group.id) ? '⌄' : '›' }}</span><span>{{ group.title }}</span><small>{{ group.fields.length ? `${group.fields.length} 个字段` : '空分组 · 可拖入字段' }}</small>
          </button>
          <VueDraggable v-if="isGroupExpanded(group.id)" v-model="group.fields" class="page-composition-tree__field-list page-composition-tree__field-list--group" :class="{ 'is-disabled': disabled }" :disabled="disabled" :animation="150" :force-fallback="true" :fallback-on-body="true" draggable=".page-composition-tree__node--field" :group="{ name: 'page-composer-form-fields', pull: true, put: true }" handle=".page-composition-tree__drag-handle" data-composer-field-container="group" :data-group-id="group.id" @end="handleFormFieldEnd">
            <button v-for="field in group.fields" :key="field.id" class="page-composition-tree__node page-composition-tree__node--field" :class="{ 'is-selected': isSelected(`ui:group-field:form:${group.id}:${field.id}`) }" :data-field-id="field.id" type="button" @click="select(`ui:group-field:form:${group.id}:${field.id}`)" @dblclick="doubleClick(`ui:group-field:form:${group.id}:${field.id}`)">
              <span class="page-composition-tree__drag-handle" aria-label="拖拽调整字段">⠿</span><span>{{ field.properties?.label ?? field.title }}</span><small>{{ field.fieldName }}</small>
            </button>
          </VueDraggable>
        </section>
      </VueDraggable>

      <template v-for="relation in local.formRelations" :key="relation.id">
        <button class="page-composition-tree__node page-composition-tree__node--relation" :class="{ 'is-selected': isSelected(`ui:relation:form:${relation.id}`) }" type="button" @click="select(`ui:relation:form:${relation.id}`)">
          <span class="page-composition-tree__indent" /><span>{{ relation.title }}</span><small>{{ relation.fields.length ? `${relation.fields.length} 个展示字段` : '尚未选择字段' }}</small>
        </button>
        <VueDraggable
          v-model="relation.fields"
          class="page-composition-tree__field-list page-composition-tree__field-list--relation"
          :class="{ 'is-disabled': disabled }"
          :disabled="disabled"
          :animation="150"
          :force-fallback="true"
          :fallback-on-body="true"
          draggable=".page-composition-tree__node--relation-field"
          handle=".page-composition-tree__drag-handle"
          @end="handleRelationFieldEnd(relation.id, $event)"
        >
          <button v-for="field in relation.fields" :key="`${relation.id}:${field.id}`" class="page-composition-tree__node page-composition-tree__node--relation-field" :class="{ 'is-selected': isSelected(`ui:relation-field:form:${relation.id}:${field.id}`) }" :data-field-id="field.id" type="button" @click="select(`ui:relation-field:form:${relation.id}:${field.id}`)">
            <span class="page-composition-tree__drag-handle" aria-label="拖拽调整子表字段顺序">⠿</span><span>{{ field.title }}</span><small>{{ field.fieldName }}</small>
          </button>
        </VueDraggable>
      </template>
      </template>
    </section>
    </template>
  </section>
</template>

<style scoped>
.page-composition-tree { min-height: 260px; color: var(--ant-color-text); font-size: 14px; }
.page-composition-tree__branch { margin-top: 4px; }
.page-composition-tree__node { align-items: center; background: transparent; border: 0; border-radius: 5px; color: inherit; cursor: pointer; display: flex; gap: 7px; min-height: 30px; padding: 4px 8px; text-align: left; width: 100%; }
.page-composition-tree__node:hover, .page-composition-tree__node.is-selected { background: var(--muyun-hover); }
.page-composition-tree__node small { color: var(--muyun-text-muted); font-size: 12px; font-weight: 400; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.page-composition-tree__node > span:not(.page-composition-tree__drag-handle):not(.page-composition-tree__group-handle):not(.page-composition-tree__caret):not(.page-composition-tree__indent) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.page-composition-tree__node--root { cursor: default; font-weight: 600; }
.page-composition-tree__node--template, .page-composition-tree__node--container, .page-composition-tree__node--field, .page-composition-tree__node--group, .page-composition-tree__node--relation { margin-left: 18px; width: calc(100% - 18px); }
.page-composition-tree__field-list { margin-left: 36px; min-height: 8px; }
.page-composition-tree__field-list--relation { margin-left: 36px; }
.page-composition-tree__field-list--group { align-items: center; border: 1px dashed transparent; border-radius: 4px; display: flex; flex-direction: column; justify-content: center; margin-left: 24px; min-height: 28px; }
.page-composition-tree__field-list--group:empty { border-color: var(--muyun-border-subtle); color: var(--muyun-text-muted); }
.page-composition-tree__field-list--group:empty::after { content: '拖拽字段到此分组'; font-size: 12px; line-height: 26px; }
.page-composition-tree__group-list { margin-left: 18px; min-height: 8px; }
.page-composition-tree__group { border-left: 1px solid var(--muyun-border-subtle); margin: 2px 0; }
.page-composition-tree__caret, .page-composition-tree__indent { color: var(--muyun-text-muted); display: inline-block; width: 12px; }
.page-composition-tree__indent--deep { width: 24px; }
.page-composition-tree__drag-handle, .page-composition-tree__group-handle { color: var(--muyun-text-muted); cursor: grab; font-size: 16px; line-height: 1; width: 12px; }
.page-composition-tree__drag-handle:active, .page-composition-tree__group-handle:active { cursor: grabbing; }
.page-composition-tree__node--relation-field { width: 100%; }
.is-disabled .page-composition-tree__drag-handle, .is-disabled .page-composition-tree__group-handle { cursor: not-allowed; }
</style>
