<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiButton } from '@muyun/vue-ui-antdv';
import type { ModuleContext } from '@muyun/web-core';
import type { ResolvedFileReferenceFieldDescriptor } from '@muyun/web-contracts';
import FileTransferUploader from './FileTransferUploader.vue';
import {
  acceptedMediaTypes,
  appendUploadedFileReference,
  fileReferenceIds,
  fileReferenceUploadIntent,
  issueFileReferenceUploadAccess,
  uploadInlineFileReference,
  uploadedFileId,
} from './fileReferenceTransfer';
import type { FileTransferUploadReceipt } from './fileTransferUpload';

defineOptions({ name: 'RecordFileReferenceTransfer' });

const props = defineProps<{
  value: unknown;
  /** The surrounding draft is business-owned input to its upload-ticket policy. */
  record: Record<string, unknown>;
  context: ModuleContext<unknown>;
  definition: ResolvedFileReferenceFieldDescriptor;
  formSessionKey?: string | number;
  disabled?: boolean;
  disabledHint?: string;
  dropzoneHint?: string;
  showBoundFiles?: boolean;
  uploaderPresentation?: 'dropzone' | 'button';
  uploadText?: string;
  uploadButtonType?: 'default' | 'primary' | 'dashed' | 'link' | 'text';
  showCompletedUploadItems?: boolean;
  /** The enclosing field has already rendered the bound file and needs the uploader slot immediately reusable. */
  releaseCompletedUploadOnBind?: boolean;
  uploadAdvisory?: (file: File) => string | undefined | Promise<string | undefined>;
  uploadValidation?: (file: File) => string | undefined | Promise<string | undefined>;
}>();

const emit = defineEmits<{
  'update:value': [value: string | string[] | undefined];
}>();

const accept = computed(() => acceptedMediaTypes(props.definition));
const uploadedFileIds = ref(new Set<string>());
const releasedUploadedFileIds = ref<string[]>([]);
const existingFileCount = computed(() =>
  props.definition.maxFiles === 1
    ? 0
    : fileReferenceIds(props.value).filter((fileId) => !uploadedFileIds.value.has(fileId)).length,
);

watch(
  () => props.formSessionKey,
  () => {
    uploadedFileIds.value = new Set();
    releasedUploadedFileIds.value = [];
  },
  { immediate: true },
);

function requestUploadAccess(file: File) {
  return issueFileReferenceUploadAccess(
    props.context.http,
    props.context.moduleAlias,
    props.definition,
    props.record,
    file,
    fileReferenceUploadIntent(props.value, props.definition),
  );
}

function uploadFile(file: File) {
  return uploadInlineFileReference(
    props.context.http,
    props.context.moduleAlias,
    props.definition,
    props.record,
    file,
    fileReferenceUploadIntent(props.value, props.definition),
  );
}

function applyUploadedFile(receipt: FileTransferUploadReceipt) {
  const fileId = uploadedFileId(receipt.payload);
  uploadedFileIds.value = new Set([...uploadedFileIds.value, fileId]);
  emit('update:value', appendUploadedFileReference(props.value, fileId, props.definition));
  if (props.releaseCompletedUploadOnBind) {
    releaseUploadedFile(fileId);
  }
}

function removeBoundFile(fileId: string) {
  if (uploadedFileIds.value.has(fileId)) {
    releaseUploadedFile(fileId);
  }
  emit(
    'update:value',
    props.definition.maxFiles === 1
      ? undefined
      : fileReferenceIds(props.value).filter((candidate) => candidate !== fileId),
  );
}

function releaseUploadedFile(fileId: string) {
  uploadedFileIds.value = new Set([...uploadedFileIds.value].filter((candidate) => candidate !== fileId));
  releasedUploadedFileIds.value = [...new Set([...releasedUploadedFileIds.value, fileId])];
}
</script>

<template>
  <div
    v-if="showBoundFiles !== false && fileReferenceIds(value).length"
    class="record-file-reference-transfer__bound-files"
  >
    <span
      v-for="fileId in fileReferenceIds(value)"
      :key="fileId"
      class="record-file-reference-transfer__bound-file"
    >
      <span>已绑定文件：{{ fileId }}</span>
      <UiButton
        v-if="!disabled"
        class="record-file-reference-transfer__remove"
        type="text"
        danger
        icon-name="delete"
        aria-label="移除文件"
        title="移除文件"
        @click="removeBoundFile(fileId)"
      />
    </span>
  </div>
  <FileTransferUploader
    :request-upload-access="requestUploadAccess"
    :upload-file="definition.storagePolicy === 'DATABASE_INLINE' ? uploadFile : undefined"
    :accept="accept"
    :allowed-media-types="definition.allowedMediaTypes"
    :max-file-size-bytes="definition.maxFileSizeBytes"
    :multiple="definition.maxFiles > 1"
    :max-files="definition.maxFiles"
    :existing-file-count="existingFileCount"
    :disabled="disabled || !definition.uploadAvailable"
    :disabled-hint="definition.uploadAvailable ? disabledHint : '当前模块未配置文件上传策略'"
    :dropzone-hint="dropzoneHint"
    :released-completed-file-ids="releasedUploadedFileIds"
    :completed-file-id="(receipt) => uploadedFileId(receipt.payload)"
    :allow-completed-removal="false"
    :presentation="uploaderPresentation"
    :upload-text="uploadText"
    :upload-button-type="uploadButtonType"
    :show-completed-items="showCompletedUploadItems"
    :upload-advisory="uploadAdvisory"
    :upload-validation="uploadValidation"
    @completed="(receipt) => applyUploadedFile(receipt)"
  />
</template>

<style scoped>
.record-file-reference-transfer__bound-files {
  display: grid;
  gap: 6px;
}
.record-file-reference-transfer__bound-file {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  width: fit-content;
  max-width: 100%;
  color: var(--ant-color-text-secondary);
  font-size: 13px;
}
.record-file-reference-transfer__bound-file > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.record-file-reference-transfer__remove {
  flex: 0 0 18px;
  width: 18px;
  min-width: 18px;
  height: 18px;
  padding: 0;
  line-height: 18px;
}
.record-file-reference-transfer__remove:deep(.ant-btn-icon) {
  margin: 0;
}
</style>
