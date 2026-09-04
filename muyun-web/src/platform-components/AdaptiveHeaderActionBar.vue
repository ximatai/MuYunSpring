<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  UiActionButton,
  UiDropdown,
  UiTooltip,
  type UiDropdownItem,
  type UiIconName,
} from '@muyun/vue-ui-antdv';
import { resolveHeaderActionLayout, type HeaderActionLevel } from './adaptiveHeaderActionLayout';

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

const layout = computed(() => {
  const actions = props.actions.map((action) => ({
    ...action,
    width: actionWidths.value[action.key] ?? estimatedActionWidth(action.title),
  }));
  return resolveHeaderActionLayout(actions, availableWidth.value, moreWidth.value);
});
const directActions = computed(() => {
  const direct = new Set(layout.value.directKeys);
  return props.actions.filter((action) => direct.has(action.key));
});
const overflowActions = computed(() => {
  const overflow = new Set(layout.value.overflowKeys);
  return props.actions.filter((action) => overflow.has(action.key));
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
  const actionContainer = element.closest<HTMLElement>('.management-panel-header-actions');
  if (actionContainer && actionContainer.clientWidth > 0) {
    // This container is the layout contract shared by tree cards and drawers.
    // Measuring it directly prevents the action content from claiming title
    // space and overflowing the right edge before it can collapse into 更多.
    availableWidth.value = actionContainer.clientWidth;
  } else if (element.clientWidth > 0) {
    availableWidth.value = element.clientWidth;
  }
  const widths: Record<string, number> = {};
  element.querySelectorAll<HTMLElement>('[data-header-action-measure]').forEach((button) => {
    const width = Math.ceil(button.getBoundingClientRect().width);
    if (width > 0) widths[button.dataset.headerActionMeasure ?? ''] = width;
  });
  actionWidths.value = widths;
  const more = element.querySelector<HTMLElement>('[data-header-more-measure]');
  if (more) moreWidth.value = Math.ceil(more.getBoundingClientRect().width);
}

function handleAction(action: HeaderActionItem, event: MouseEvent) {
  if (!action.disabled && !action.loading) emit('action', action, event);
}

function handleOverflowAction(key: string) {
  const action = props.actions.find((item) => item.key === key);
  if (action && !action.disabled && !action.loading) emit('action', action, new MouseEvent('click'));
}

onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(measure);
    if (root.value) resizeObserver.observe(root.value);
    const header = root.value?.closest<HTMLElement>('.management-panel-header');
    const titleGroup = header?.querySelector<HTMLElement>('.management-panel-header-title-group');
    if (header) {
      headerResizeObserver = new ResizeObserver(measure);
      headerResizeObserver.observe(header);
      if (titleGroup) headerResizeObserver.observe(titleGroup);
    }
  }
  nextTick(measure);
});
onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  headerResizeObserver?.disconnect();
});
watch(
  () => props.actions,
  () => nextTick(measure),
  { deep: true },
);
</script>

<template>
  <div ref="root" class="adaptive-header-action-bar">
    <div class="adaptive-header-action-bar__visible">
      <UiTooltip
        v-for="action in directActions"
        :key="action.key"
        :title="action.disabled ? (action.disabledReason ?? '') : ''"
      >
        <span class="adaptive-header-action-bar__trigger record-action-tooltip-trigger">
          <UiActionButton
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
          <UiActionButton>更多</UiActionButton>
        </template>
      </UiDropdown>
    </div>
    <div class="adaptive-header-action-bar__measure" aria-hidden="true">
      <UiActionButton
        v-for="action in actions"
        :key="action.key"
        :data-header-action-measure="action.key"
        :emphasis="action.level === 'primary' ? 'primary' : 'secondary'"
        :intent="action.danger ? 'danger' : 'normal'"
        :icon-name="action.iconName"
        >{{ action.title }}</UiActionButton
      >
      <UiActionButton data-header-more-measure>更多</UiActionButton>
    </div>
  </div>
</template>

<style scoped>
.adaptive-header-action-bar {
  min-width: 0;
}
.adaptive-header-action-bar__visible {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  min-width: 0;
}
.adaptive-header-action-bar__trigger {
  display: inline-flex;
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
