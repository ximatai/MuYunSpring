<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  UiActionButton,
  UiDropdown,
  UiTooltip,
  type UiDropdownItem,
  type UiIconName,
} from '@muyun/vue-ui-antdv';
import { resolveHeaderActionLayout, type HeaderActionLevel } from './adaptiveHeaderActionLayout';
import ActionControlHost from './ActionControlHost.vue';
import type { ActionControl } from './actionControl';

const language = useLanguageContext();

defineOptions({ name: 'AdaptiveHeaderActionBar' });

export interface HeaderActionItem {
  key: string;
  title: string;
  level?: HeaderActionLevel;
  disabled?: boolean;
  disabledReason?: string;
  loading?: boolean;
  danger?: boolean;
  iconName?: UiIconName;
  control?: ActionControl;
  placement?: 'after-primary';
}

const props = withDefaults(
  defineProps<{
    actions: HeaderActionItem[];
  }>(),
  { actions: () => [] },
);

const emit = defineEmits<{ action: [action: HeaderActionItem, event: MouseEvent] }>();

const root = ref<HTMLElement>();
const availableWidth = ref(Number.POSITIVE_INFINITY);
const actionWidths = ref<Record<string, number>>({});
const moreWidth = ref(72);
let resizeObserver: ResizeObserver | undefined;
let headerResizeObserver: ResizeObserver | undefined;
let measureFrame: number | undefined;
const observedControls = new Set<HTMLElement>();

const layout = computed(() => {
  const actions = props.actions.map((action) => ({
    ...action,
    keepDirect: action.control != null,
    width: actionWidths.value[action.key] ?? estimatedActionWidth(action.title),
  }));
  return resolveHeaderActionLayout(actions, availableWidth.value, moreWidth.value);
});
const directActions = computed(() => {
  return layout.value.directKeys.map((key) => props.actions.find((action) => action.key === key)!);
});
const overflowActions = computed(() => {
  return layout.value.overflowKeys.map((key) => props.actions.find((action) => action.key === key)!);
});
const overflowItems = computed<UiDropdownItem[]>(() =>
  overflowActions.value.map((action) => ({
    key: action.key,
    title: action.title,
    disabled: action.disabled || action.loading,
    danger: action.danger,
  })),
);

function estimatedActionWidth(title: string) {
  // Before the hidden ruler is available (SSR and lightweight test hosts),
  // retain direct actions instead of treating their unknown width as zero.
  return 40 + Array.from(title).length * 14;
}

function measure() {
  const element = root.value;
  if (!element) return;
  const actionContainer = element.closest<HTMLElement>(
    '.management-panel-header-actions, .drawer-operation-bar-actions',
  );
  if (actionContainer && actionContainer.clientWidth > 0) {
    // This container is the layout contract shared by tree cards and drawers.
    // Measuring it directly prevents the action content from claiming title
    // space and overflowing the right edge before it can collapse into 更多.
    const siblings = Array.from(actionContainer.children).filter(
      (child) => child !== element && !child.contains(element) && child.getBoundingClientRect().width > 0,
    );
    const style = getComputedStyle(actionContainer);
    const pixels = (value: string) => Number.parseFloat(value) || 0;
    const occupied = siblings.reduce((sum, sibling) => {
      const siblingStyle = getComputedStyle(sibling);
      return (
        sum +
        sibling.getBoundingClientRect().width +
        pixels(siblingStyle.marginLeft) +
        pixels(siblingStyle.marginRight)
      );
    }, 0);
    availableWidth.value = Math.max(
      0,
      actionContainer.clientWidth -
        occupied -
        siblings.length * pixels(style.columnGap) -
        pixels(style.paddingLeft) -
        pixels(style.paddingRight),
    );
  } else if (element.clientWidth > 0) {
    availableWidth.value = element.clientWidth;
  }
  const widths: Record<string, number> = {};
  element.querySelectorAll<HTMLElement>('[data-header-action-measure]').forEach((button) => {
    const width = Math.ceil(button.getBoundingClientRect().width);
    if (width > 0) widths[button.dataset.headerActionMeasure ?? ''] = width;
  });
  if (JSON.stringify(actionWidths.value) !== JSON.stringify(widths)) actionWidths.value = widths;
  const more = element.querySelector<HTMLElement>('[data-header-more-measure]');
  const measuredMoreWidth = more ? Math.ceil(more.getBoundingClientRect().width) : 0;
  if (measuredMoreWidth > 0) moreWidth.value = measuredMoreWidth;
}

function handleAction(action: HeaderActionItem, event: MouseEvent) {
  if (!action.control && !action.disabled && !action.loading) emit('action', action, event);
}

function handleOverflowAction(key: string) {
  const action = props.actions.find((item) => item.key === key);
  if (action && !action.control && !action.disabled && !action.loading)
    emit('action', action, new MouseEvent('click'));
}

function observeControls() {
  const controls = new Set(root.value?.querySelectorAll<HTMLElement>('[data-action-control]'));
  for (const element of observedControls) {
    if (!controls.has(element)) {
      resizeObserver?.unobserve(element);
      observedControls.delete(element);
    }
  }
  controls.forEach((element) => {
    resizeObserver?.observe(element);
    observedControls.add(element);
  });
}

function scheduleMeasure() {
  if (measureFrame !== undefined) return;
  // Layout writes must not resize an observed surface in the same delivery cycle.
  measureFrame = requestAnimationFrame(() => {
    measureFrame = undefined;
    measure();
  });
}

onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(scheduleMeasure);
    if (root.value) {
      resizeObserver.observe(root.value);
      const ruler = root.value.querySelector<HTMLElement>('.adaptive-header-action-bar__measure');
      if (ruler) resizeObserver.observe(ruler);
    }
    const region = root.value?.closest<HTMLElement>(
      '.management-panel-header-actions, .drawer-operation-bar-actions',
    );
    if (region) resizeObserver.observe(region);
    const header = root.value?.closest<HTMLElement>('.management-panel-header');
    const titleGroup = header?.querySelector<HTMLElement>('.management-panel-header-title-group');
    if (header) {
      headerResizeObserver = new ResizeObserver(scheduleMeasure);
      headerResizeObserver.observe(header);
      if (titleGroup) headerResizeObserver.observe(titleGroup);
    }
  }
  nextTick(() => {
    observeControls();
    measure();
  });
});
onBeforeUnmount(() => {
  if (measureFrame !== undefined) cancelAnimationFrame(measureFrame);
  resizeObserver?.disconnect();
  headerResizeObserver?.disconnect();
  observedControls.clear();
});
watch(
  // Observe presentation facts, never traverse business components or module contexts.
  () =>
    props.actions.map((action) => [
      action.key,
      action.title,
      action.level,
      action.loading,
      action.iconName,
      action.control?.component,
    ]),
  () =>
    nextTick(() => {
      observeControls();
      measure();
    }),
);
</script>

<template>
  <div ref="root" class="adaptive-header-action-bar">
    <div class="adaptive-header-action-bar__visible">
      <UiTooltip
        v-for="action in directActions"
        :key="action.key"
        :title="action.disabled && action.disabledReason ? `${action.title}：${action.disabledReason}` : ''"
      >
        <span
          class="adaptive-header-action-bar__trigger record-action-tooltip-trigger"
          :data-action-control="action.control ? action.key : undefined"
          :data-header-action-measure="action.control ? action.key : undefined"
        >
          <ActionControlHost
            v-if="action.control"
            :control="action.control"
            :action-key="action.key"
            :title="action.title"
            :disabled="action.disabled"
            :disabled-reason="action.disabledReason"
            :loading="action.loading"
          />
          <UiActionButton
            v-else
            :emphasis="action.level === 'primary' ? 'primary' : 'secondary'"
            :intent="action.danger ? 'danger' : 'normal'"
            :disabled="action.disabled"
            :loading="action.loading"
            :icon-name="action.iconName"
            @click="handleAction(action, $event)"
            >{{ action.title }}</UiActionButton
          >
        </span>
      </UiTooltip>
      <UiDropdown
        v-if="overflowActions.length"
        trigger="hover"
        :items="overflowItems"
        @select="handleOverflowAction"
      >
        <template #default>
          <UiActionButton>{{
            language.t('platform.screen.AdaptiveHeaderActionBar.more', '更多')
          }}</UiActionButton>
        </template>
      </UiDropdown>
    </div>
    <div class="adaptive-header-action-bar__measure" aria-hidden="true">
      <span
        v-for="action in actions.filter((item) => !item.control)"
        :key="action.key"
        :data-header-action-measure="action.key"
      >
        <UiActionButton
          :loading="action.loading"
          :emphasis="action.level === 'primary' ? 'primary' : 'secondary'"
          :intent="action.danger ? 'danger' : 'normal'"
          :icon-name="action.iconName"
          >{{ action.title }}</UiActionButton
        >
      </span>
      <span data-header-more-measure>
        <UiActionButton>{{
          language.t('platform.screen.AdaptiveHeaderActionBar.more', '更多')
        }}</UiActionButton>
      </span>
    </div>
  </div>
</template>

<style scoped>
.adaptive-header-action-bar {
  min-width: 0;
}
.adaptive-header-action-bar__visible {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  min-width: 0;
}
.adaptive-header-action-bar__trigger {
  display: inline-flex;
  max-width: 100%;
  min-width: 0;
}
.adaptive-header-action-bar__trigger > :deep(*) {
  max-width: 100%;
  min-width: 0;
}
.adaptive-header-action-bar__trigger > :deep(.ant-btn) {
  overflow: hidden;
}
.adaptive-header-action-bar__measure {
  position: fixed;
  visibility: hidden;
  display: inline-flex;
  gap: 8px;
  inset: -9999px auto auto -9999px;
  pointer-events: none;
  white-space: nowrap;
}
</style>
