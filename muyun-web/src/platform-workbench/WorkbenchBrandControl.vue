<script setup lang="ts">
import { UiIcon } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'WorkbenchBrandControl' });

const props = withDefaults(
  defineProps<{
    presentation: 'compact' | 'expanded';
    compactOpen?: boolean;
    tenantLabel?: string;
  }>(),
  {
    compactOpen: false,
    tenantLabel: '系统工作区',
  },
);

const emit = defineEmits<{
  openCompactMenu: [source: 'pointer' | 'focus' | 'click'];
  scheduleCompactMenuClose: [];
  closeCompactMenu: [];
  changePresentation: [presentation: 'compact' | 'expanded'];
  compactHoverExit: [];
}>();

function requestCompactMenuOpen(source: 'pointer' | 'focus' | 'click') {
  if (props.presentation === 'compact') {
    emit('openCompactMenu', source);
  }
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

function handleControlPointerLeave() {
  if (props.presentation === 'compact') {
    emit('compactHoverExit');
  }
}

function togglePresentation() {
  emit('changePresentation', props.presentation === 'compact' ? 'expanded' : 'compact');
}
</script>

<template>
  <div
    class="workbench-brand-control"
    :class="`workbench-brand-control--${presentation}`"
    @mouseleave="handleControlPointerLeave"
  >
    <component
      :is="presentation === 'compact' ? 'button' : 'div'"
      class="workbench-brand-identity"
      :class="{ 'workbench-brand-identity--open': compactOpen }"
      :type="presentation === 'compact' ? 'button' : undefined"
      :aria-label="presentation === 'compact' ? '系统菜单' : undefined"
      :aria-expanded="presentation === 'compact' ? compactOpen : undefined"
      :aria-controls="presentation === 'compact' ? 'workbench-compact-menu' : undefined"
      @mouseenter="requestCompactMenuOpen('pointer')"
      @mouseleave="scheduleCompactMenuClose"
      @focus="requestCompactMenuOpen('focus')"
      @focusout="scheduleCompactMenuClose"
      @click="requestCompactMenuOpen('click')"
      @keydown="handleIdentityKeydown"
    >
      <span class="workbench-brand-mark"><UiIcon name="app" /></span>
      <span class="workbench-brand-copy">
        <strong>MuYun</strong>
        <small>{{ tenantLabel }}</small>
      </span>
    </component>
    <button
      class="workbench-brand-presentation-toggle"
      type="button"
      :aria-label="presentation === 'compact' ? '展开侧栏菜单' : '收敛侧栏菜单'"
      :title="presentation === 'compact' ? '展开侧栏菜单' : '收敛侧栏菜单'"
      @click="togglePresentation"
    >
      <UiIcon :name="presentation === 'compact' ? 'menu-expand' : 'menu-collapse'" />
    </button>
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
  background: #fbfcfe;
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

.workbench-brand-presentation-toggle:hover,
.workbench-brand-presentation-toggle:focus-visible {
  outline: 0;
  background: #eaf5f2;
  color: #0f766e;
}
</style>
