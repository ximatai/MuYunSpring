<script setup lang="ts">
import { computed } from 'vue';
import { UiActionButton, type UiSidePanelScope } from '@muyun/vue-ui-antdv';
import RecordDetailDrawer from './RecordDetailDrawer.vue';
import RecordExternalChangeNotice from './RecordExternalChangeNotice.vue';
import type { DrawerPromotion } from './drawerPromotion';

defineOptions({ name: 'RecordModeDrawer' });

const props = withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    /** Secondary business identity rendered by the platform detail header. */
    subtitle?: string;
    width?: number | string;
    scope?: UiSidePanelScope;
    mode: string;
    viewMode?: string;
    formModes?: string[];
    loading?: boolean;
    loadFailed?: boolean;
    closeOnOutside?: boolean;
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
    subtitle: undefined,
    width: 520,
    scope: 'tab',
    viewMode: 'view',
    formModes: () => ['edit', 'create'],
    loading: false,
    loadFailed: false,
    closeOnOutside: undefined,
    closeTitle: '关闭',
    promotion: undefined,
    errorTitle: '详情加载失败',
    errorMessage: '无法加载详情，请重试',
    retryTitle: '重试',
    externallyChanged: false,
    editAvailable: false,
    saveAvailable: false,
    saving: false,
    editTitle: '编辑',
    saveTitle: '保存',
    externalChangeTitle: undefined,
    externalChangeMessage: undefined,
    externalChangeReloadTitle: undefined,
    externalChangeDismissTitle: undefined,
  },
);

defineSlots<{
  status(): unknown;
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
  retry: [];
  reloadExternalChange: [];
  dismissExternalChange: [];
  edit: [];
  save: [];
}>();

const viewModeActive = computed(() => props.mode === props.viewMode);
const formModeActive = computed(() => props.formModes.includes(props.mode));
const actualCloseOnOutside = computed(() => props.closeOnOutside ?? viewModeActive.value);
</script>

<template>
  <RecordDetailDrawer
    :open="open"
    :title="title"
    :subtitle="subtitle"
    :width="width"
    :scope="scope"
    :close-on-outside="actualCloseOnOutside"
    :close-title="closeTitle"
    :promotion="promotion"
    @close="emit('close')"
  >
    <template #status>
      <slot name="status" />
    </template>
    <template v-if="$slots.operation || $slots.viewOperation || editAvailable || saveAvailable" #operation>
      <slot v-if="$slots.operation" name="operation" />
      <template v-else>
        <slot v-if="viewModeActive" name="viewOperation" />
        <UiActionButton v-if="editAvailable" icon-name="edit" @click="emit('edit')">
          {{ editTitle }}
        </UiActionButton>
        <UiActionButton v-if="saveAvailable" emphasis="primary" :loading="saving" @click="emit('save')">
          {{ saveTitle }}
        </UiActionButton>
      </template>
    </template>

    <slot />

    <template v-if="loading">
      <slot name="loading" />
    </template>
    <template v-else-if="loadFailed">
      <slot name="error">
        <div class="record-mode-drawer-state">
          <strong>{{ errorTitle }}</strong>
          <span>{{ errorMessage }}</span>
          <UiActionButton emphasis="primary" icon-name="reload" @click="emit('retry')">
            {{ retryTitle }}
          </UiActionButton>
        </div>
      </slot>
    </template>
    <template v-else-if="viewModeActive">
      <slot name="view" />
    </template>
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
  color: #64748b;
  font-size: 13px;
}
</style>
