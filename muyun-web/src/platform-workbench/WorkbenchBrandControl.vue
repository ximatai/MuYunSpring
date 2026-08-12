<script setup lang="ts">
import { UiIcon } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'WorkbenchBrandControl' });

interface CompactMenuAnchor {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

const props = withDefaults(
  defineProps<{
    presentation: 'compact' | 'expanded';
    compactOpen?: boolean;
    tenantLabel?: string;
    expandedMenuDepth?: 1 | 2 | 3;
    presentationToggleVisible?: boolean;
  }>(),
  {
    compactOpen: false,
    tenantLabel: '系统工作区',
    expandedMenuDepth: 1,
    presentationToggleVisible: true,
  },
);

const emit = defineEmits<{
  openCompactMenu: [source: 'pointer' | 'focus' | 'click', anchor: CompactMenuAnchor];
  scheduleCompactMenuClose: [];
  closeCompactMenu: [];
  changePresentation: [presentation: 'compact' | 'expanded'];
  changeExpandedMenuDepth: [depth: 1 | 2 | 3];
}>();

function requestCompactMenuOpen(source: 'pointer' | 'focus' | 'click', event: MouseEvent | FocusEvent) {
  if (props.presentation !== 'compact' || !(event.currentTarget instanceof HTMLElement)) {
    return;
  }
  const rect = event.currentTarget.getBoundingClientRect();
  emit('openCompactMenu', source, {
    left: rect.left,
    top: rect.top,
    right: rect.right,
    bottom: rect.bottom,
  });
}

function scheduleCompactMenuClose() {
  if (props.presentation === 'compact') {
    emit('scheduleCompactMenuClose');
  }
}

function handleIdentityKeydown(event: KeyboardEvent) {
  if (props.presentation === 'compact' && event.key === 'Escape') {
    emit('closeCompactMenu');
  }
}

function togglePresentation() {
  emit('changePresentation', props.presentation === 'compact' ? 'expanded' : 'compact');
}

function changeExpandedMenuDepth(depth: 1 | 2 | 3) {
  emit('changeExpandedMenuDepth', depth);
}
</script>

<template>
  <div class="workbench-brand-control" :class="`workbench-brand-control--${presentation}`">
    <component
      :is="presentation === 'compact' ? 'button' : 'div'"
      class="workbench-brand-identity"
      :class="{ 'workbench-brand-identity--open': compactOpen }"
      :type="presentation === 'compact' ? 'button' : undefined"
      :aria-label="presentation === 'compact' ? '系统菜单' : undefined"
      :aria-expanded="presentation === 'compact' ? compactOpen : undefined"
      :aria-controls="presentation === 'compact' ? 'workbench-compact-menu' : undefined"
      @mouseenter="requestCompactMenuOpen('pointer', $event)"
      @mouseleave="scheduleCompactMenuClose"
      @focus="requestCompactMenuOpen('focus', $event)"
      @focusout="scheduleCompactMenuClose"
      @click="requestCompactMenuOpen('click', $event)"
      @keydown="handleIdentityKeydown"
    >
      <span class="workbench-brand-mark"><UiIcon name="app" /></span>
      <span class="workbench-brand-copy">
        <strong>MuYun</strong>
        <small>{{ tenantLabel }}</small>
      </span>
    </component>
    <button
      v-if="presentationToggleVisible"
      class="workbench-brand-presentation-toggle"
      type="button"
      :aria-label="presentation === 'compact' ? '展开侧栏菜单' : '收敛侧栏菜单'"
      :title="presentation === 'compact' ? '展开侧栏菜单' : '收敛侧栏菜单'"
      @click="togglePresentation"
    >
      <UiIcon :name="presentation === 'compact' ? 'menu-expand' : 'menu-collapse'" />
    </button>
    <div
      v-if="presentation === 'expanded'"
      class="workbench-menu-depth"
      role="group"
      aria-label="侧栏菜单层级"
    >
      <button
        v-for="depth in [1, 2, 3] as const"
        :key="depth"
        class="workbench-menu-depth-option"
        :class="{ selected: expandedMenuDepth === depth }"
        type="button"
        :aria-pressed="expandedMenuDepth === depth"
        :title="`侧栏显示至第 ${depth} 级菜单`"
        @click="changeExpandedMenuDepth(depth)"
      >
        {{ depth }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.workbench-brand-control {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.workbench-brand-identity {
  display: inline-flex;
  flex: 0 1 auto;
  align-items: center;
  gap: 7px;
  min-width: 0;
  margin: -4px -6px;
  padding: 3px 5px;
  border: 1px solid transparent;
  border-radius: 3px 3px 0 0;
  background: transparent;
  color: #172033;
  font: inherit;
}

button.workbench-brand-identity {
  cursor: pointer;
}

.workbench-brand-mark {
  display: inline-grid;
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 7px;
  background: #172033;
  color: #fff;
}

.workbench-brand-copy {
  display: grid;
  min-width: 0;
  text-align: left;
}

.workbench-brand-copy strong {
  color: #172033;
  font-size: 15px;
  line-height: 1.1;
}

.workbench-brand-copy small {
  overflow: hidden;
  margin-top: 2px;
  color: #64748b;
  font-size: 11px;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workbench-brand-control--expanded .workbench-brand-identity {
  margin: 0;
  padding: 0;
}

.workbench-brand-control--compact .workbench-brand-identity:hover .workbench-brand-mark,
.workbench-brand-control--compact .workbench-brand-identity:focus-visible .workbench-brand-mark,
.workbench-brand-control--compact .workbench-brand-identity--open .workbench-brand-mark {
  background: #0f766e;
}

.workbench-brand-control--compact .workbench-brand-identity--open {
  position: relative;
  z-index: 1;
  background: #fff;
  box-shadow: inset 0 -1px 0 #fff;
}

.workbench-brand-control--compact .workbench-brand-identity:focus-visible {
  outline: 2px solid #99d5cc;
  outline-offset: 3px;
  border-radius: 5px;
}

.workbench-brand-presentation-toggle {
  display: inline-flex;
  flex: 0 0 30px;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: #64748b;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
}

.workbench-menu-depth {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px;
  border: 1px solid #d8e1ea;
  border-radius: 6px;
  background: #fff;
}

.workbench-menu-depth-option {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #64748b;
  font:
    700 11px/1 ui-monospace,
    SFMono-Regular,
    Menlo,
    monospace;
  cursor: pointer;
}

.workbench-menu-depth-option:hover,
.workbench-menu-depth-option:focus-visible {
  background: #edf4f7;
  color: #0f766e;
  outline: 0;
}

.workbench-menu-depth-option.selected {
  background: #0f766e;
  color: #fff;
  box-shadow: 0 1px 2px rgb(15 118 110 / 22%);
}

.workbench-brand-presentation-toggle:hover,
.workbench-brand-presentation-toggle:focus-visible {
  outline: 0;
  background: #eaf5f2;
  color: #0f766e;
}
</style>
