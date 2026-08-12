<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiButton } from '@muyun/vue-ui-antdv';
import type { ModuleContext } from '@muyun/web-core';
import type { ResolvedFileReferenceFieldDescriptor } from '@muyun/web-contracts';
import RecordFileReferenceTransfer from './RecordFileReferenceTransfer.vue';
import { fileReferenceIds, issueFileReferenceAccess } from './fileReferenceTransfer';

defineOptions({ name: 'SingleImageFileReferenceField' });

const props = defineProps<{
  value: unknown;
  record: Record<string, unknown>;
  context: ModuleContext<unknown>;
  definition: ResolvedFileReferenceFieldDescriptor;
  formSessionKey?: string | number;
  disabled?: boolean;
  disabledHint?: string;
}>();

const emit = defineEmits<{ 'update:value': [value: string | undefined] }>();
const fileId = computed(() => fileReferenceIds(props.value)[0]);
const previewUrl = ref<string>();
const previewLoading = ref(false);
const previewError = ref<string>();

watch(
  [fileId, () => props.formSessionKey],
  () => {
    previewUrl.value = undefined;
    previewError.value = undefined;
    if (fileId.value && props.definition.readAvailable) void loadPreview();
  },
  { immediate: true },
);

async function loadPreview() {
  if (!fileId.value) return;
  previewLoading.value = true;
  previewError.value = undefined;
  try {
    previewUrl.value = await issueFileReferenceAccess(
      props.context.http,
      props.context.moduleAlias,
      props.definition,
      props.record,
      fileId.value,
      'preview',
    );
  } catch (error) {
    previewError.value = error instanceof Error ? error.message : '图片预览加载失败。';
  } finally {
    previewLoading.value = false;
  }
}

async function download() {
  if (!fileId.value) return;
  const url = await issueFileReferenceAccess(
    props.context.http,
    props.context.moduleAlias,
    props.definition,
    props.record,
    fileId.value,
    'download',
  );
  const link = document.createElement('a');
  link.href = url;
  link.download = '';
  link.click();
}
</script>

<template>
  <div class="single-image-file-reference-field">
    <div v-if="fileId" class="single-image-file-reference-field__preview">
      <img v-if="previewUrl" :src="previewUrl" alt="已上传图片预览" />
      <span v-else-if="previewLoading">正在加载预览…</span>
      <span v-else>{{ previewError ?? '图片预览不可用' }}</span>
      <div v-if="definition.readAvailable" class="single-image-file-reference-field__actions">
        <UiButton type="link" :disabled="previewLoading" @click="loadPreview">查看</UiButton>
        <UiButton type="link" @click="download">下载</UiButton>
      </div>
    </div>
    <RecordFileReferenceTransfer
      :value="value"
      :record="record"
      :context="context"
      :definition="definition"
      :form-session-key="formSessionKey"
      :disabled="disabled"
      :disabled-hint="disabledHint"
      :show-bound-files="false"
      @update:value="emit('update:value', typeof $event === 'string' ? $event : undefined)"
    />
  </div>
</template>

<style scoped>
.single-image-file-reference-field {
  display: grid;
  gap: 8px;
}
.single-image-file-reference-field__preview {
  display: grid;
  justify-items: start;
  gap: 6px;
}
.single-image-file-reference-field__preview img {
  display: block;
  max-width: 100%;
  max-height: 120px;
  padding: 6px;
  background: var(--ant-color-fill-quaternary);
  border: 1px solid var(--ant-color-border-secondary);
  border-radius: 6px;
  object-fit: contain;
}
.single-image-file-reference-field__actions {
  display: flex;
  gap: 6px;
}
</style>
