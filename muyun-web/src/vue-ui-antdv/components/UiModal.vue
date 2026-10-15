<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { Modal as AModal } from 'ant-design-vue';
import { computed, ref } from 'vue';
import { useUiBlockingOverlayVisibility } from '../blockingOverlays';

const language = useLanguageContext();

defineOptions({ name: 'UiModal', inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    confirmText?: string;
    cancelText?: string;
    confirmLoading?: boolean;
    confirmDisabled?: boolean;
    closable?: boolean;
    width?: number | string;
  }>(),
  {
    confirmText: undefined,
    cancelText: undefined,
    confirmLoading: false,
    confirmDisabled: false,
    closable: true,
    width: 420,
  },
);

const resolvedConfirmText = computed(
  () => props.confirmText ?? language.t('platform.screen.UiModal.confirmText', '确认'),
);
const resolvedCancelText = computed(() => props.cancelText ?? language.t('platform.action.cancel', '取消'));

const overlayContent = ref<HTMLElement>();
const updateOverlayVisibility = useUiBlockingOverlayVisibility(() => props.open, overlayContent);

const emit = defineEmits<{
  confirm: [];
  cancel: [];
}>();
</script>

<template>
  <AModal
    :open="open"
    :title="title"
    :ok-text="resolvedConfirmText"
    :cancel-text="resolvedCancelText"
    :confirm-loading="confirmLoading"
    :ok-button-props="{ disabled: confirmDisabled }"
    :closable="closable"
    :mask-closable="false"
    :keyboard="closable"
    :width="width"
    :class="$attrs.class"
    :style="$attrs.style"
    @ok="emit('confirm')"
    @cancel="emit('cancel')"
    @after-close="updateOverlayVisibility(false)"
  >
    <div ref="overlayContent"><slot /></div>
  </AModal>
</template>
