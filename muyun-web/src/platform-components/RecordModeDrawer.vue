<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed } from 'vue';
import {
  UiActionButton,
  type UiDrawerCloseGuard,
  type UiDrawerDismissal,
  type UiDrawerWidth,
  type UiSidePanelScope,
} from '@muyun/vue-ui-antdv';
import RecordDetailDrawer from './RecordDetailDrawer.vue';
import RecordExternalChangeNotice from './RecordExternalChangeNotice.vue';
import type { DrawerPromotion } from './drawerPromotion';

const language = useLanguageContext();

defineOptions({ name: 'RecordModeDrawer' });

const props = withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    container?: HTMLElement | null;
    /** Explicit presentation is needed by embedded record-only surfaces. */
    renderMode?: 'inline' | 'portal';
    subtitle?: string;
    width?: UiDrawerWidth;
    scope?: UiSidePanelScope;
    mode: string;
    viewMode?: string;
    formModes?: string[];
    loading?: boolean;
    loadFailed?: boolean;
    dismissal?: UiDrawerDismissal;
    /** @deprecated Use `dismissal` and `beforeClose`. */
    closeOnOutside?: boolean;
    beforeClose?: UiDrawerCloseGuard;
    closeTitle?: string;
    promotion?: DrawerPromotion;
    errorTitle?: string;
    errorMessage?: string;
    retryTitle?: string;
    externallyChanged?: boolean;
    editAvailable?: boolean;
    saveAvailable?: boolean;
    saving?: boolean;
    editTitle?: string;
    saveTitle?: string;
    externalChangeTitle?: string;
    externalChangeMessage?: string;
    externalChangeReloadTitle?: string;
    externalChangeDismissTitle?: string;
  }>(),
  {
    container: undefined,
    renderMode: undefined,
    subtitle: undefined,
    width: 'standard',
    scope: 'tab',
    viewMode: 'view',
    formModes: () => ['edit', 'create'],
    loading: false,
    loadFailed: false,
    dismissal: undefined,
    closeOnOutside: undefined,
    beforeClose: undefined,
    closeTitle: undefined,
    promotion: undefined,
    errorTitle: undefined,
    errorMessage: undefined,
    retryTitle: undefined,
    externallyChanged: false,
    editAvailable: false,
    saveAvailable: false,
    saving: false,
    editTitle: undefined,
    saveTitle: undefined,
    externalChangeTitle: undefined,
    externalChangeMessage: undefined,
    externalChangeReloadTitle: undefined,
    externalChangeDismissTitle: undefined,
  },
);

const resolvedCloseTitle = computed(
  () => props.closeTitle ?? language.t('platform.screen.RecordModeDrawer.close', '关闭'),
);
const resolvedErrorTitle = computed(
  () =>
    props.errorTitle ?? language.t('platform.screen.RecordModeDrawer.could-not-load-details', '详情加载失败'),
);
const resolvedErrorMessage = computed(
  () =>
    props.errorMessage ?? language.t('platform.screen.RecordModeDrawer.errorMessage', '无法加载详情，请重试'),
);
const resolvedRetryTitle = computed(
  () => props.retryTitle ?? language.t('platform.screen.RecordModeDrawer.retry', '重试'),
);
const resolvedEditTitle = computed(() => props.editTitle ?? language.t('platform.action.edit', '编辑'));
const resolvedSaveTitle = computed(() => props.saveTitle ?? language.t('platform.action.save', '保存'));

defineSlots<{
  status(): unknown;
  'title-prefix'(): unknown;
  'title-actions'(): unknown;
  'header-actions'(): unknown;
  loading(): unknown;
  error(): unknown;
  externalChangeNotice(): unknown;
  view(): unknown;
  form(): unknown;
  viewOperation(): unknown;
  default(): unknown;
  operation(): unknown;
}>();

const emit = defineEmits<{
  close: [];
  afterClose: [];
  retry: [];
  reloadExternalChange: [];
  dismissExternalChange: [];
  edit: [];
  save: [];
}>();

const viewModeActive = computed(() => props.mode === props.viewMode);
const formModeActive = computed(() => props.formModes.includes(props.mode));
const legacyCloseOnOutside = computed(() => props.closeOnOutside ?? viewModeActive.value);
</script>

<template>
  <RecordDetailDrawer
    :open="open"
    :title="title"
    :render-mode="renderMode ?? (container ? 'inline' : 'portal')"
    :subtitle="subtitle"
    :width="width"
    :scope="scope"
    :dismissal="dismissal"
    :close-on-outside="legacyCloseOnOutside"
    :before-close="beforeClose"
    :close-title="resolvedCloseTitle"
    :promotion="promotion"
    @close="emit('close')"
    @after-close="emit('afterClose')"
  >
    <template v-if="$slots['title-prefix']" #title-prefix>
      <slot name="title-prefix" />
    </template>
    <template #status><slot name="status" /></template>
    <template v-if="$slots['title-actions']" #title-actions>
      <slot name="title-actions" />
    </template>
    <template v-if="$slots['header-actions']" #header-actions>
      <slot name="header-actions" />
    </template>
    <template v-if="$slots.operation || $slots.viewOperation || editAvailable || saveAvailable" #operation>
      <slot v-if="$slots.operation" name="operation" />
      <template v-else>
        <slot v-if="viewModeActive" name="viewOperation" />
        <UiActionButton v-if="editAvailable" icon-name="edit" @click="emit('edit')">
          {{ resolvedEditTitle }}
        </UiActionButton>
        <UiActionButton v-if="saveAvailable" emphasis="primary" :loading="saving" @click="emit('save')">
          {{ resolvedSaveTitle }}
        </UiActionButton>
      </template>
    </template>
    <slot />
    <template v-if="loading"><slot name="loading" /></template>
    <template v-else-if="loadFailed">
      <slot name="error">
        <div class="record-mode-drawer-state">
          <strong>{{ resolvedErrorTitle }}</strong
          ><span>{{ resolvedErrorMessage }}</span>
          <UiActionButton emphasis="primary" icon-name="reload" @click="emit('retry')">
            {{ resolvedRetryTitle }}
          </UiActionButton>
        </div>
      </slot>
    </template>
    <template v-else-if="viewModeActive"><slot name="view" /></template>
    <template v-else-if="formModeActive">
      <slot v-if="externallyChanged" name="externalChangeNotice">
        <RecordExternalChangeNotice
          :title="externalChangeTitle"
          :message="externalChangeMessage"
          :reload-title="externalChangeReloadTitle"
          :dismiss-title="externalChangeDismissTitle"
          @reload="emit('reloadExternalChange')"
          @dismiss="emit('dismissExternalChange')"
        />
      </slot>
      <slot name="form" />
    </template>
  </RecordDetailDrawer>
</template>

<style scoped>
.record-mode-drawer-state {
  display: grid;
  justify-items: start;
  gap: 10px;
  color: var(--muyun-text);
}
.record-mode-drawer-state span {
  color: var(--muyun-text-muted);
  font-size: 13px;
}
</style>
