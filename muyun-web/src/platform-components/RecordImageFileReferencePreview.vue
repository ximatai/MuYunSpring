<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { ModuleContext } from '@muyun/web-core';
import type { ResolvedFileReferenceFieldDescriptor } from '@muyun/web-contracts';
import { fileReferenceIds, issueFileReferenceAccess } from './fileReferenceTransfer';

defineOptions({ name: 'RecordImageFileReferencePreview' });

const props = defineProps<{
  value: unknown;
  record: Record<string, unknown>;
  context: ModuleContext<unknown>;
  definition: ResolvedFileReferenceFieldDescriptor;
}>();

const fileId = computed(() => fileReferenceIds(props.value)[0]);
const previewUrl = ref<string>();
const loading = ref(false);
const error = ref<string>();
let previewRevision = 0;

watch(
  [fileId, () => props.record],
  () => {
    const revision = ++previewRevision;
    const requestedFileId = fileId.value;
    const requestedRecord = props.record;
    previewUrl.value = undefined;
    error.value = undefined;
    if (requestedFileId && props.definition.readAvailable)
      void loadPreview(revision, requestedFileId, requestedRecord);
  },
  { immediate: true },
);

async function loadPreview(
  revision: number,
  requestedFileId: string,
  requestedRecord: Record<string, unknown>,
) {
  loading.value = true;
  try {
    const url = await issueFileReferenceAccess(
      props.context.http,
      props.context.moduleAlias,
      props.definition,
      requestedRecord,
      requestedFileId,
      'preview',
    );
    if (revision === previewRevision) previewUrl.value = url;
  } catch (cause) {
    if (revision === previewRevision) {
      error.value = cause instanceof Error ? cause.message : '图片预览加载失败。';
    }
  } finally {
    if (revision === previewRevision) loading.value = false;
  }
}
</script>

<template>
  <div class="record-image-file-reference-preview">
    <img v-if="previewUrl" :src="previewUrl" alt="已上传图片预览" />
    <span v-else>{{ loading ? '正在加载预览' : (error ?? '图片预览不可用') }}</span>
  </div>
</template>

<style scoped>
.record-image-file-reference-preview {
  display: inline-grid;
  width: 100%;
  height: 156px;
  place-items: center;
  overflow: hidden;
  color: var(--muyun-support-text-muted);
  border: 1px solid var(--muyun-theme-border);
  border-radius: 8px;
}

img {
  display: block;
  width: 96px;
  height: 154px;
  padding: 12px;
  object-fit: contain;
}
</style>
