<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { Alert as AAlert } from 'ant-design-vue';

const language = useLanguageContext();

defineOptions({ name: 'UiError', inheritAttrs: false });

defineProps<{
  title?: string;
  message: string;
}>();
</script>

<template>
  <AAlert
    type="error"
    show-icon
    :message="title ?? language.t('platform.screen.UiError.could-not-load', '加载失败')"
    :description="message"
    :class="$attrs.class"
    :style="$attrs.style"
  />
</template>
