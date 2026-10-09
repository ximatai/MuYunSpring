<script setup lang="ts">
import { Modal as AModal } from 'ant-design-vue';
import { ref } from 'vue';
import { useUiBlockingOverlayVisibility } from '../blockingOverlays';

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
    confirmText: '确认',
    cancelText: '取消',
    confirmLoading: false,
    confirmDisabled: false,
    closable: true,
    width: 420,
  },
);

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
    :ok-text="confirmText"
    :cancel-text="cancelText"
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
