<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { ModuleContext } from '@muyun/web-core';
import type { ResolvedFileReferenceFieldDescriptor } from '@muyun/web-contracts';
import FileTransferUploader from './FileTransferUploader.vue';
import {
  acceptedMediaTypes,
  appendUploadedFileReference,
  fileReferenceIds,
  FileReferenceFormSaveSession,
  fileReferenceUploadIntent,
  issueFileReferenceUploadAccess,
  replacedFileReferenceId,
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
}>();

const emit = defineEmits<{
  'update:value': [value: string | string[] | undefined];
  'delete:persisted': [fileId: string];
}>();

const accept = computed(() => acceptedMediaTypes(props.definition));
const uploadedFileIds = ref(new Set<string>());
const releasedUploadedFileIds = ref<string[]>([]);
const formSession = new FileReferenceFormSaveSession();
const existingFileCount = computed(() =>
  props.definition.maxFiles === 1
    ? 0
    : fileReferenceIds(props.value).filter((fileId) => !uploadedFileIds.value.has(fileId)).length,
);

watch(
  () => props.formSessionKey,
  () => {
    formSession.begin(props.value);
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

function applyUploadedFile(receipt: FileTransferUploadReceipt) {
  const fileId = uploadedFileId(receipt.payload);
  const replacedFileId = replacedFileReferenceId(props.value, fileId, props.definition);
  if (replacedFileId) {
    const ownership = formSession.remove(replacedFileId);
    if (ownership === 'uploaded') releaseUploadedFile(replacedFileId);
    if (ownership === 'persisted') emit('delete:persisted', replacedFileId);
  }
  formSession.registerUploaded(fileId);
  uploadedFileIds.value = new Set([...uploadedFileIds.value, fileId]);
  emit('update:value', appendUploadedFileReference(props.value, fileId, props.definition));
}

function removeBoundFile(fileId: string) {
  const ownership = formSession.remove(fileId);
  if (ownership === 'uploaded') {
    releaseUploadedFile(fileId);
  }
  if (ownership === 'persisted') emit('delete:persisted', fileId);
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
  <div v-if="fileReferenceIds(value).length" class="record-file-reference-transfer__bound-files">
    <span
      v-for="fileId in fileReferenceIds(value)"
      :key="fileId"
      class="record-file-reference-transfer__bound-file"
    >
      已绑定文件：{{ fileId }}
      <button v-if="!disabled" type="button" @click="removeBoundFile(fileId)">移除</button>
    </span>
  </div>
  <FileTransferUploader
    :request-upload-access="requestUploadAccess"
    :accept="accept"
    :allowed-media-types="definition.allowedMediaTypes"
    :max-file-size-bytes="definition.maxFileSizeBytes"
    :multiple="definition.maxFiles > 1"
    :max-files="definition.maxFiles"
    :existing-file-count="existingFileCount"
    :disabled="disabled || !definition.uploadAvailable"
    :disabled-hint="definition.uploadAvailable ? disabledHint : '当前模块未配置文件上传策略'"
    :released-completed-file-ids="releasedUploadedFileIds"
    :completed-file-id="(receipt) => uploadedFileId(receipt.payload)"
    completion-hint="请保存业务记录以完成文件绑定。"
    :allow-completed-removal="false"
    @completed="(receipt) => applyUploadedFile(receipt)"
  />
</template>
