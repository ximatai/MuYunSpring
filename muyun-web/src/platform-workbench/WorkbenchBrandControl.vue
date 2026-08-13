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
    logoSrc?: string;
    showTitleArea?: boolean;
    brandTitle?: string;
    brandSubtitle?: string;
    expandedMenuDepth?: 1 | 2 | 3;
    presentationToggleVisible?: boolean;
  }>(),
  {
    compactOpen: false,
    tenantLabel: '系统工作区',
    logoSrc: undefined,
    showTitleArea: true,
    brandTitle: 'MuYun',
    brandSubtitle: undefined,
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
  <div
    class="workbench-brand-control"
    :class="[
      `workbench-brand-control--${presentation}`,
      { 'workbench-brand-control--with-title': showTitleArea },
    ]"
  >
    <component
      :is="presentation === 'compact' ? 'button' : 'div'"
      class="workbench-brand-identity"
      :class="{
        'workbench-brand-identity--open': compactOpen,
        'workbench-brand-identity--with-title': showTitleArea,
      }"
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
      <template v-if="logoSrc">
        <img class="workbench-brand-logo" :src="logoSrc" :alt="`${tenantLabel} 标志`" />
      </template>
      <template v-else>
        <span class="workbench-brand-mark"><UiIcon name="app" /></span>
      </template>
      <span v-if="showTitleArea" class="workbench-brand-copy">
        <strong>{{ brandTitle }}</strong>
        <small v-if="presentation === 'expanded'">{{ brandSubtitle ?? tenantLabel }}</small>
      </span>
    </component>
    <div class="workbench-brand-actions">
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

.workbench-brand-actions {
  display: grid;
  flex: 0 0 auto;
  justify-items: end;
  gap: 2px;
}

.workbench-brand-identity {
  display: inline-flex;
  flex: 1 1 auto;
  align-items: center;
  gap: 7px;
  min-width: 0;
  overflow: hidden;
  margin: -4px -6px;
  padding: 3px 5px;
  border: 1px solid transparent;
  border-radius: 3px 3px 0 0;
  background: transparent;
  color: var(--muyun-support-text);
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
  background: var(--muyun-theme-base);
  color: var(--muyun-support-surface);
}

.workbench-brand-logo {
  display: block;
  flex: 0 1 auto;
  min-width: 0;
  width: auto;
  max-width: min(164px, 100%);
  height: 30px;
  object-fit: contain;
  object-position: left center;
}

/* A square identity mark needs more visual weight beside the two-line title block. */
.workbench-brand-identity--with-title .workbench-brand-mark,
.workbench-brand-identity--with-title .workbench-brand-logo {
  flex: 0 0 35px;
  min-width: 35px;
  max-width: 35px;
  width: 35px;
  height: 35px;
}

.workbench-brand-copy {
  display: grid;
  min-width: 0;
  text-align: left;
}

.workbench-brand-copy strong {
  overflow: hidden;
  color: var(--muyun-support-text);
  font-size: 17px;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workbench-brand-copy small {
  overflow: hidden;
  margin-top: 2px;
  color: var(--muyun-support-text-muted);
  font-size: 12px;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workbench-brand-control--expanded .workbench-brand-identity {
  margin: 0;
  padding: 0;
}

.workbench-brand-control--expanded.workbench-brand-control--with-title {
  position: relative;
  display: flex;
  align-items: center;
  min-height: 46px;
}

.workbench-brand-control--expanded.workbench-brand-control--with-title .workbench-brand-identity {
  flex: 1 1 auto;
  align-items: center;
  min-height: 35px;
  padding-right: 24px;
}

.workbench-brand-control--expanded.workbench-brand-control--with-title .workbench-brand-copy {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  gap: 1px;
  min-width: 0;
}

.workbench-brand-control--expanded.workbench-brand-control--with-title .workbench-brand-copy strong {
  display: block;
  margin-right: 30px;
}

/* The subtitle follows the title naturally and only leaves room for the collapse button. */
.workbench-brand-control--expanded.workbench-brand-control--with-title .workbench-brand-copy small {
  margin-top: 0;
}

/* These controls remain pinned to the right and do not consume the subtitle row. */
.workbench-brand-control--expanded.workbench-brand-control--with-title .workbench-brand-actions {
  position: absolute;
  top: 50%;
  right: 0;
  transform: translateY(-50%);
}

.workbench-brand-control--compact .workbench-brand-identity {
  /* Keep the menu-toggle touch target outside the tenant brand area. */
  max-width: 132px;
}

.workbench-brand-control--compact .workbench-brand-copy strong {
  font-size: 18px;
}

.workbench-brand-control--compact .workbench-brand-identity--with-title .workbench-brand-mark,
.workbench-brand-control--compact .workbench-brand-identity--with-title .workbench-brand-logo {
  flex-basis: 33px;
  min-width: 33px;
  max-width: 33px;
  width: 33px;
  height: 33px;
}

.workbench-brand-control--compact .workbench-brand-identity:hover .workbench-brand-mark,
.workbench-brand-control--compact .workbench-brand-identity:focus-visible .workbench-brand-mark,
.workbench-brand-control--compact .workbench-brand-identity--open .workbench-brand-mark {
  background: var(--muyun-theme-base);
}

.workbench-brand-control--compact .workbench-brand-identity--open {
  position: relative;
  z-index: 1;
  margin-left: -8px;
  border-radius: 0;
  background: var(--muyun-support-surface);
  box-shadow: inset 0 -1px 0 var(--muyun-support-surface);
}

.workbench-brand-control--compact .workbench-brand-identity:focus-visible {
  outline: 2px solid var(--muyun-theme-focus);
  outline-offset: 3px;
  border-radius: 5px;
}

.workbench-brand-presentation-toggle {
  display: inline-flex;
  flex: 0 0 24px;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--muyun-support-text-muted);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

.workbench-brand-control--compact .workbench-brand-actions {
  display: contents;
}

.workbench-menu-depth {
  display: inline-flex;
  align-items: center;
  gap: 1px;
  padding: 1px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 5px;
  background: var(--muyun-support-surface);
}

.workbench-menu-depth-option {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--muyun-support-text-muted);
  font:
    700 10px/1 ui-monospace,
    SFMono-Regular,
    Menlo,
    monospace;
  cursor: pointer;
}

.workbench-menu-depth-option:hover,
.workbench-menu-depth-option:focus-visible {
  background: var(--muyun-brand-accent-soft);
  color: var(--muyun-brand-accent-active);
  outline: 0;
}

.workbench-menu-depth-option.selected {
  background: var(--muyun-brand-accent-base);
  color: var(--muyun-brand-accent-on-base);
  box-shadow: 0 1px 2px color-mix(in srgb, var(--muyun-brand-accent-base) 30%, transparent);
}

.workbench-brand-presentation-toggle:hover,
.workbench-brand-presentation-toggle:focus-visible {
  outline: 0;
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
}
</style>
