<script setup lang="ts">
import { normalizeError, userFacingErrorMessage, useLanguageContext } from '@muyun/web-core';

import { FileTransferUploadError, FileTransferUploadCancelledError } from './fileTransferUpload';
import { computed, reactive, ref, watch } from 'vue';
import { UiButton, UiIcon } from '@muyun/vue-ui-antdv';
import type { UiIconName } from '@muyun/vue-ui-antdv';
import {
  performBrowserFileTransferUpload,
  type FileTransferUploadAccess,
  type FileTransferUploadReceipt,
  type FileTransferUploadTask,
} from './fileTransferUpload';
import {
  presentPlatformError,
  presentPlatformMessage,
  presentPlatformSuccess,
  presentPlatformWarning,
} from './platformErrorFeedback';

const language = useLanguageContext();

defineOptions({ name: 'FileTransferUploader' });

type UploadState = 'ready' | 'requesting' | 'uploading' | 'confirming' | 'completed' | 'failed' | 'cancelled';

interface UploadItem {
  id: number;
  file: File;
  state: UploadState;
  progress: number;
  error?: () => string;
  task?: FileTransferUploadTask;
  completedFileId?: string;
}

const props = withDefaults(
  defineProps<{
    /** Business API callback: authorize this exact file and return a short-lived upload target. */
    requestUploadAccess?: (file: File) => Promise<FileTransferUploadAccess>;
    /** Storage-specific transport; ordinary FileServer fields continue to use upload access tickets. */
    uploadFile?: (file: File) => Promise<FileTransferUploadReceipt>;
    /** Optional business callback after upload. Standard file-reference fields leave this undefined. */
    confirmUpload?: (receipt: FileTransferUploadReceipt) => Promise<unknown>;
    accept?: string;
    allowedMediaTypes?: string[];
    maxFileSizeBytes?: number;
    multiple?: boolean;
    disabled?: boolean;
    maxFiles?: number;
    /** Files already bound by the surrounding record and therefore occupying this field's capacity. */
    existingFileCount?: number;
    autoUpload?: boolean;
    uploadText?: string;
    presentation?: 'dropzone' | 'button';
    uploadButtonType?: 'default' | 'primary' | 'dashed' | 'link' | 'text';
    uploadButtonSize?: 'small' | 'middle' | 'large';
    uploadButtonIconName?: UiIconName;
    uploadButtonTitle?: string;
    showCompletedItems?: boolean;
    /** Non-blocking, browser-side guidance evaluated after file selection and before transfer. */
    uploadAdvisory?: (file: File) => string | undefined | Promise<string | undefined>;
    /** Business-owned preflight validation; returning a message rejects the selected file before transfer. */
    uploadValidation?: (file: File) => string | undefined | Promise<string | undefined>;
    disabledHint?: string;
    /** Persistent guidance displayed in the dropzone before a file is selected. */
    dropzoneHint?: string;
    completionHint?: string;
    /** Completed items may be retained when the surrounding form owns deletion semantics. */
    allowCompletedRemoval?: boolean;
    /** Browser-owned uploaded files removed from an unsaved enclosing form. */
    releasedCompletedFileIds?: readonly string[];
    completedFileId?: (receipt: FileTransferUploadReceipt) => string | undefined;
  }>(),
  {
    confirmUpload: undefined,
    requestUploadAccess: undefined,
    uploadFile: undefined,
    accept: undefined,
    allowedMediaTypes: undefined,
    maxFileSizeBytes: undefined,
    multiple: false,
    disabled: false,
    maxFiles: undefined,
    existingFileCount: 0,
    autoUpload: true,
    uploadText: undefined,
    presentation: 'dropzone',
    uploadButtonType: 'default',
    uploadButtonSize: 'middle',
    uploadButtonIconName: undefined,
    uploadButtonTitle: undefined,
    showCompletedItems: true,
    uploadAdvisory: undefined,
    uploadValidation: undefined,
    disabledHint: undefined,
    dropzoneHint: undefined,
    completionHint: undefined,
    allowCompletedRemoval: true,
    releasedCompletedFileIds: () => [],
    completedFileId: undefined,
  },
);

const resolvedUploadText = computed(
  () => props.uploadText ?? language.t('platform.screen.FileTransferUploader.uploadText', '选择文件上传'),
);

const emit = defineEmits<{
  completed: [receipt: FileTransferUploadReceipt, result: unknown];
  failed: [file: File, error: unknown];
  changed: [files: readonly File[]];
}>();

const input = ref<HTMLInputElement>();
const items = ref<UploadItem[]>([]);
const dragging = ref(false);
const preparingSelection = ref(false);
let nextId = 1;

watch(
  () => props.releasedCompletedFileIds,
  (released) => {
    if (!released.length) return;
    const ids = new Set(released);
    items.value = items.value.filter((item) => !item.completedFileId || !ids.has(item.completedFileId));
  },
  { deep: true },
);

const active = computed(() =>
  items.value.some((item) => ['requesting', 'uploading', 'confirming'].includes(item.state)),
);
const occupiedCount = computed(
  () =>
    props.existingFileCount +
    items.value.filter((item) => !['failed', 'cancelled'].includes(item.state)).length,
);
const atCapacity = computed(() => props.maxFiles !== undefined && occupiedCount.value >= props.maxFiles);
const unavailable = computed(
  () => props.disabled || preparingSelection.value || active.value || atCapacity.value,
);
const visibleItems = computed(() =>
  props.showCompletedItems ? items.value : items.value.filter((item) => item.state !== 'completed'),
);

function chooseFiles() {
  if (unavailable.value) return;
  input.value?.click();
}

function selectFiles(event: Event) {
  void addFiles(Array.from((event.target as HTMLInputElement).files ?? []));
  // Selecting the same file again must still produce a change event.
  (event.target as HTMLInputElement).value = '';
}

async function addFiles(selected: readonly File[]) {
  if (unavailable.value) return;
  preparingSelection.value = true;
  try {
    const candidates = await Promise.all(
      selected.map(async (file) => ({ file, error: await validationError(file) })),
    );
    const rejected = candidates
      .filter((candidate): candidate is { file: File; error: () => string } => candidate.error != null)
      .map((candidate) =>
        reactive<UploadItem>({
          id: nextId++,
          file: candidate.file,
          state: 'failed',
          progress: 0,
          error: candidate.error,
        }),
      );
    if (rejected.length) {
      items.value.push(...rejected);
    }
    const valid = candidates
      .filter((candidate) => candidate.error == null)
      .map((candidate) => candidate.file);
    const capacity =
      props.maxFiles === undefined ? valid.length : Math.max(0, props.maxFiles - occupiedCount.value);
    const accepted = valid.slice(0, props.multiple ? capacity : Math.min(1, capacity));
    if (accepted.length) {
      await presentUploadAdvisories(accepted);
      // `upload()` mutates the item throughout its lifecycle. Keep the very same
      // reactive instance both in the rendered list and in that async workflow;
      // otherwise Vue cannot observe mutations made through the pre-insertion raw
      // object and the UI can remain stuck at its first state.
      const additions = accepted.map((file) =>
        reactive<UploadItem>({ id: nextId++, file, state: 'ready', progress: 0 }),
      );
      items.value.push(...additions);
      emit(
        'changed',
        items.value.map((item) => item.file),
      );
      if (props.autoUpload) {
        additions.forEach((item) => void upload(item));
      }
    }
  } finally {
    preparingSelection.value = false;
  }
}

async function presentUploadAdvisories(files: readonly File[]) {
  if (!props.uploadAdvisory) return;
  const advisories = await Promise.all(
    files.map(async (file) => {
      try {
        return await props.uploadAdvisory?.(file);
      } catch {
        return undefined;
      }
    }),
  );
  for (const advisory of advisories) {
    if (advisory) presentPlatformWarning(advisory);
  }
}

async function validationError(file: File): Promise<(() => string) | undefined> {
  if (
    props.allowedMediaTypes?.length &&
    !props.allowedMediaTypes.some((allowed) => matchesMediaType(allowed, file.type))
  ) {
    return () =>
      language.t(
        'platform.screen.FileTransferUploader.unsupported-file-type-value',
        '不支持文件类型：{value1}',
        { value1: file.type || file.name },
      );
  }
  if (props.maxFileSizeBytes != null && file.size > props.maxFileSizeBytes) {
    return () =>
      language.t(
        'platform.screen.FileTransferUploader.the-file-exceeds-the-size-limit-value-bytes',
        '文件超过单文件大小限制（{value1} 字节）。',
        { value1: props.maxFileSizeBytes },
      );
  }
  const customError = await props.uploadValidation?.(file);
  return customError === undefined ? undefined : () => customError;
}

/** Matches exact MIME types and the standard top-level wildcard form (for example image/*). */
function matchesMediaType(allowedMediaType: string, actualMediaType: string): boolean {
  const allowed = allowedMediaType.trim().toLowerCase();
  const actual = actualMediaType.trim().toLowerCase();
  if (!allowed || !actual) return false;
  if (allowed === actual) return true;
  const wildcard = /^([a-z0-9!#$&^_.+-]+)\/\*$/.exec(allowed);
  return wildcard?.[1] === actual.split('/', 1)[0];
}

function dragOver(event: DragEvent) {
  event.preventDefault();
  if (!unavailable.value) dragging.value = true;
}

function dragLeave(event: DragEvent) {
  // Moving over a child of the drop zone also emits dragleave; only reset when leaving it.
  const dropZone = event.currentTarget as HTMLElement;
  if (!dropZone.contains(event.relatedTarget as Node | null)) dragging.value = false;
}

function dropFiles(event: DragEvent) {
  event.preventDefault();
  dragging.value = false;
  void addFiles(Array.from(event.dataTransfer?.files ?? []));
}

function handleDropZoneKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    chooseFiles();
  }
}

function dropZoneHint() {
  if (props.disabled)
    return (
      props.disabledHint ??
      language.t('platform.screen.FileTransferUploader.uploads-are-currently-unavailable', '当前不可上传')
    );
  if (atCapacity.value)
    return language.t(
      'platform.screen.FileTransferUploader.the-maximum-number-of-files-for-this-field-has-been',
      '已达该字段允许的文件数量上限',
    );
  return (
    props.dropzoneHint ??
    language.t(
      'platform.screen.FileTransferUploader.click-to-select-files-or-drag-them-here',
      '点击选择，或将文件拖拽到此处',
    )
  );
}

async function upload(item: UploadItem) {
  if (props.disabled) {
    return;
  }
  item.error = undefined;
  try {
    if (props.uploadFile) {
      item.state = 'uploading';
      const receipt = await props.uploadFile(item.file);
      item.progress = 100;
      item.state = 'completed';
      item.completedFileId = props.completedFileId?.(receipt);
      emit('completed', receipt, receipt.payload);
      presentPlatformSuccess(
        language.t(
          'platform.screen.FileTransferUploader.value-uploaded-successfully-value',
          '“{value1}”上传成功。{value2}',
          { value1: item.file.name, value2: props.completionHint ?? '' },
        ),
        {
          source: 'file-transfer',
          tone: 'success',
        },
      );
      return;
    }
    if (!props.requestUploadAccess) {
      throw new FileTransferUploadError('missing-transport');
    }
    const { receipt, result } = await performBrowserFileTransferUpload(
      item.file,
      props.requestUploadAccess,
      props.confirmUpload,
      {
        stateChanged: (state) => {
          item.state = state;
        },
        progressChanged: (percent) => {
          item.progress = percent;
        },
        taskCreated: (task) => {
          item.task = task;
        },
        taskFinished: () => {
          item.task = undefined;
        },
      },
    );
    item.completedFileId = props.completedFileId?.(receipt);
    emit('completed', receipt, result);
    presentPlatformSuccess(
      language.t(
        'platform.screen.FileTransferUploader.value-uploaded-successfully-value',
        '“{value1}”上传成功。{value2}',
        { value1: item.file.name, value2: props.completionHint ?? '' },
      ),
      {
        source: 'file-transfer',
        tone: 'success',
      },
    );
  } catch (error) {
    item.task = undefined;
    item.state = error instanceof FileTransferUploadCancelledError ? 'cancelled' : 'failed';
    item.error = () => uploadFailureText(error);
    emit('failed', item.file, error);
    if (item.state === 'failed') {
      if (error instanceof FileTransferUploadError)
        presentPlatformMessage(uploadFailureText(error), {
          language,
          source: 'file-transfer',
          phase: 'action',
          tone: 'error',
        });
      else presentPlatformError(error, { language, source: 'file-transfer', phase: 'action' });
    }
  }
}

function cancel(item: UploadItem) {
  item.task?.cancel();
}

function remove(item: UploadItem) {
  if (['requesting', 'uploading', 'confirming'].includes(item.state)) {
    cancel(item);
    return;
  }
  items.value = items.value.filter((candidate) => candidate.id !== item.id);
  emit(
    'changed',
    items.value.map((candidate) => candidate.file),
  );
}

async function retry(item: UploadItem) {
  if (item.state === 'failed' || item.state === 'cancelled') {
    const error = await validationError(item.file);
    if (error) {
      item.error = error;
      return;
    }
    item.progress = 0;
    void upload(item);
  }
}

function uploadFailureText(cause: unknown) {
  if (cause instanceof FileTransferUploadError) {
    switch (cause.reason) {
      case 'missing-address':
        return language.t('platform.file-upload.missing-address', '上传凭证未提供上传地址。');
      case 'missing-transport':
        return language.t('platform.file-upload.missing-transport', '当前文件字段未配置上传 transport。');
      case 'network':
        return language.t('platform.file-upload.network', '文件服务网络连接失败。');
      case 'http':
        return language.t('platform.file-upload.http', '文件服务上传失败（HTTP {status}）。', {
          status: cause.httpStatus,
        });
    }
  }
  return cause instanceof Error
    ? userFacingErrorMessage(normalizeError(cause), language)
    : language.t('platform.screen.FileTransferUploader.file-upload-failed', '文件上传失败。');
}

function stateText(item: UploadItem) {
  const labels: Record<UploadState, string> = {
    ready: language.t('platform.screen.FileTransferUploader.waiting-to-upload', '等待上传'),
    // The upload ticket is an implementation detail.  Keep the user-facing
    // status focused on the file's progress rather than the authorization step.
    requesting: language.t('platform.screen.FileTransferUploader.preparing-upload', '上传准备中…'),
    uploading: language.t('platform.screen.FileTransferUploader.uploading-value', '正在上传 {value1}%', {
      value1: item.progress,
    }),
    confirming: language.t('platform.screen.FileTransferUploader.validating-file', '校验文件中…'),
    completed: language.t('platform.screen.FileTransferUploader.upload-complete', '上传完成'),
    failed: item.error?.() ?? language.t('platform.screen.FileTransferUploader.upload-failed', '上传失败'),
    cancelled: language.t('platform.workflow.canceled', '已取消'),
  };
  return labels[item.state];
}
</script>

<template>
  <div
    class="file-transfer-uploader"
    :class="{ 'file-transfer-uploader--dropzone': presentation === 'dropzone' }"
  >
    <input
      ref="input"
      class="file-transfer-uploader__input"
      type="file"
      :accept="accept"
      :multiple="multiple"
      :disabled="unavailable"
      @click.stop
      @change="selectFiles"
    />
    <UiButton
      v-if="presentation === 'button'"
      class="file-transfer-uploader__choose-button"
      :type="uploadButtonType"
      :size="uploadButtonSize"
      :icon-name="uploadButtonIconName"
      :icon-only="!!uploadButtonIconName"
      :title="uploadButtonTitle"
      :aria-label="uploadButtonTitle"
      :disabled="unavailable"
      @click="chooseFiles"
    >
      <span v-if="!uploadButtonIconName">{{ resolvedUploadText }}</span>
    </UiButton>
    <div
      v-else
      class="file-transfer-uploader__drop-zone"
      :class="{ 'is-dragging': dragging, 'is-disabled': unavailable }"
      :tabindex="unavailable ? -1 : 0"
      role="button"
      :aria-disabled="unavailable"
      @click="chooseFiles"
      @keydown="handleDropZoneKeydown"
      @dragenter.prevent="dragOver"
      @dragover="dragOver"
      @dragleave="dragLeave"
      @drop="dropFiles"
    >
      <span class="file-transfer-uploader__drop-zone-icon">+</span>
      <span class="file-transfer-uploader__drop-zone-title">{{ resolvedUploadText }}</span>
      <span class="file-transfer-uploader__drop-zone-hint">{{ dropZoneHint() }}</span>
    </div>
    <div v-if="visibleItems.length" class="file-transfer-uploader__list" aria-live="polite">
      <div v-for="item in visibleItems" :key="item.id" class="file-transfer-uploader__item">
        <div class="file-transfer-uploader__file">
          <span
            v-if="item.state === 'completed'"
            class="file-transfer-uploader__completed-icon"
            role="img"
            :aria-label="language.t('platform.screen.FileTransferUploader.upload-complete', '上传完成')"
          >
            <UiIcon name="check" />
          </span>
          <div class="file-transfer-uploader__name" :title="item.file.name">{{ item.file.name }}</div>
        </div>
        <div class="file-transfer-uploader__state" :class="`is-${item.state}`">
          <template v-if="item.state !== 'completed'">{{ stateText(item) }}</template>
        </div>
        <div class="file-transfer-uploader__actions">
          <UiButton v-if="item.state === 'ready'" type="link" @click="upload(item)">
            {{ language.t('platform.screen.FileTransferUploader.upload', '上传') }}
          </UiButton>
          <UiButton
            v-else-if="item.state === 'failed' || item.state === 'cancelled'"
            type="link"
            @click="retry(item)"
          >
            {{ language.t('platform.screen.FileTransferUploader.retry', '重试') }}
          </UiButton>
          <UiButton
            v-if="['requesting', 'uploading'].includes(item.state)"
            type="link"
            danger
            @click="cancel(item)"
          >
            {{ language.t('platform.action.cancel', '取消') }}
          </UiButton>
          <UiButton
            v-else-if="!['confirming', 'completed'].includes(item.state) || allowCompletedRemoval"
            class="file-transfer-uploader__remove-button"
            type="link"
            danger
            icon-name="close"
            icon-only
            :aria-label="
              language.t('platform.screen.FileTransferUploader.remove-from-upload-list', '从上传列表移除')
            "
            :title="
              language.t('platform.screen.FileTransferUploader.remove-from-upload-list', '从上传列表移除')
            "
            @click="remove(item)"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.file-transfer-uploader {
  display: grid;
  gap: 8px;
}
.file-transfer-uploader--dropzone {
  margin-block-end: 12px;
}
.file-transfer-uploader__input {
  display: none;
}
.file-transfer-uploader__choose-button:not(.ui-button--icon-only) {
  width: fit-content;
}
.file-transfer-uploader__drop-zone {
  display: grid;
  justify-items: center;
  gap: 5px;
  min-height: 128px;
  padding: 22px;
  color: var(--muyun-support-text-muted);
  background: var(--muyun-theme-soft);
  border: 1px dashed var(--muyun-theme-border);
  border-radius: 10px;
  cursor: pointer;
  outline: none;
  transition:
    border-color 160ms ease,
    background-color 160ms ease,
    box-shadow 160ms ease;
}
.file-transfer-uploader__drop-zone:not(.is-disabled):hover,
.file-transfer-uploader__drop-zone:focus-visible,
.file-transfer-uploader__drop-zone.is-dragging {
  background: var(--muyun-theme-focus);
  border-color: var(--muyun-theme-base);
  box-shadow: 0 0 0 3px var(--muyun-theme-focus);
}
.file-transfer-uploader__drop-zone.is-dragging {
  border-style: solid;
}
.file-transfer-uploader__drop-zone.is-disabled {
  background: var(--muyun-support-disabled);
  border-color: var(--muyun-support-border);
  cursor: not-allowed;
  opacity: 0.65;
}
.file-transfer-uploader__drop-zone-icon {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  color: var(--muyun-theme-base);
  font-size: 22px;
  font-weight: 300;
  line-height: 1;
  background: var(--muyun-support-surface);
  border: 1px solid var(--muyun-theme-border);
  border-radius: 50%;
}
.file-transfer-uploader__drop-zone-title {
  color: var(--muyun-support-text-body);
  font-size: 14px;
  font-weight: 600;
}
.file-transfer-uploader__drop-zone-hint {
  color: var(--muyun-support-text-muted);
  font-size: 12px;
  line-height: 1.5;
}
.file-transfer-uploader__list {
  display: grid;
  gap: 8px;
}
.file-transfer-uploader__item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 12px;
  min-height: 46px;
  padding: 9px 10px 9px 14px;
  background: var(--muyun-theme-soft);
  border: 1px solid var(--muyun-theme-border);
  border-radius: 8px;
  transition:
    background-color 160ms ease,
    border-color 160ms ease;
}
.file-transfer-uploader__item:hover {
  background: var(--muyun-theme-focus);
  border-color: var(--muyun-theme-base);
}
.file-transfer-uploader__file {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 10px;
}
.file-transfer-uploader__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-transfer-uploader__state {
  color: var(--ant-color-text-secondary);
  font-size: 12px;
}
.file-transfer-uploader__state.is-failed {
  color: var(--ant-color-error);
}
.file-transfer-uploader__state.is-completed {
  color: var(--ant-color-success);
}
.file-transfer-uploader__completed-icon {
  display: inline-grid;
  flex: 0 0 auto;
  width: 20px;
  height: 20px;
  place-items: center;
  color: var(--muyun-theme-on-base);
  font-size: 13px;
  font-weight: 700;
  line-height: 1;
  background: var(--ant-color-success);
  border-radius: 50%;
}
:deep(.ant-btn.file-transfer-uploader__remove-button) {
  color: var(--ant-color-text-secondary);
  background: var(--muyun-support-disabled);
  border-radius: 50%;
  transition:
    color 160ms ease,
    background-color 160ms ease,
    transform 160ms ease;
}
:deep(.ant-btn.file-transfer-uploader__remove-button:hover),
:deep(.ant-btn.file-transfer-uploader__remove-button:focus-visible) {
  color: var(--ant-color-error);
  background: color-mix(in srgb, var(--ant-color-error) 12%, transparent);
  transform: scale(1.06);
}
.file-transfer-uploader__actions {
  display: flex;
  gap: 4px;
}
</style>
