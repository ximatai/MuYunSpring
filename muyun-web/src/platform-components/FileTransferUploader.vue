<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { UiButton } from '@muyun/vue-ui-antdv';
import {
  performBrowserFileTransferUpload,
  type FileTransferUploadAccess,
  type FileTransferUploadReceipt,
  type FileTransferUploadTask,
} from './fileTransferUpload';
import {
  presentPlatformError,
  presentPlatformSuccess,
  presentPlatformWarning,
} from './platformErrorFeedback';

defineOptions({ name: 'FileTransferUploader' });

type UploadState = 'ready' | 'requesting' | 'uploading' | 'confirming' | 'completed' | 'failed' | 'cancelled';

interface UploadItem {
  id: number;
  file: File;
  state: UploadState;
  progress: number;
  error?: string;
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
    showCompletedItems?: boolean;
    /** Non-blocking, browser-side guidance evaluated after file selection and before transfer. */
    uploadAdvisory?: (file: File) => string | undefined | Promise<string | undefined>;
    disabledHint?: string;
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
    uploadText: '选择文件上传',
    presentation: 'dropzone',
    uploadButtonType: 'default',
    showCompletedItems: true,
    uploadAdvisory: undefined,
    disabledHint: undefined,
    completionHint: undefined,
    allowCompletedRemoval: true,
    releasedCompletedFileIds: () => [],
    completedFileId: undefined,
  },
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
    const rejected = selected
      .map((file) => ({ file, error: validationError(file) }))
      .filter((candidate): candidate is { file: File; error: string } => candidate.error != null)
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
    const valid = selected.filter((file) => validationError(file) == null);
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

function validationError(file: File): string | undefined {
  if (
    props.allowedMediaTypes?.length &&
    !props.allowedMediaTypes.some((allowed) => matchesMediaType(allowed, file.type))
  ) {
    return `不支持文件类型：${file.type || file.name}`;
  }
  if (props.maxFileSizeBytes != null && file.size > props.maxFileSizeBytes) {
    return `文件超过单文件大小限制（${props.maxFileSizeBytes} 字节）。`;
  }
  return undefined;
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
      presentPlatformSuccess(`“${item.file.name}”上传成功。${props.completionHint ?? ''}`, {
        source: 'file-transfer',
        tone: 'success',
      });
      return;
    }
    if (!props.requestUploadAccess) {
      throw new Error('当前文件字段未配置上传 transport。');
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
    presentPlatformSuccess(`“${item.file.name}”上传成功。${props.completionHint ?? ''}`, {
      source: 'file-transfer',
      tone: 'success',
    });
  } catch (error) {
    item.task = undefined;
    item.state = error instanceof Error && error.message === '上传已取消。' ? 'cancelled' : 'failed';
    item.error = error instanceof Error ? error.message : '文件上传失败。';
    emit('failed', item.file, error);
    if (item.state === 'failed') {
      presentPlatformError(error, { source: 'file-transfer', phase: 'action' });
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

function retry(item: UploadItem) {
  if (item.state === 'failed' || item.state === 'cancelled') {
    const error = validationError(item.file);
    if (error) {
      item.error = error;
      return;
    }
    item.progress = 0;
    void upload(item);
  }
}

function stateText(item: UploadItem) {
  const labels: Record<UploadState, string> = {
    ready: '等待上传',
    // The upload ticket is an implementation detail.  Keep the user-facing
    // status focused on the file's progress rather than the authorization step.
    requesting: '上传准备中…',
    uploading: `正在上传 ${item.progress}%`,
    confirming: '校验文件中…',
    completed: '上传完成',
    failed: item.error ?? '上传失败',
    cancelled: '已取消',
  };
  return labels[item.state];
}
</script>

<template>
  <div class="file-transfer-uploader">
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
      :disabled="unavailable"
      @click="chooseFiles"
    >
      {{ uploadText }}
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
      <span class="file-transfer-uploader__drop-zone-title">{{ uploadText }}</span>
      <span class="file-transfer-uploader__drop-zone-hint">{{
        disabled
          ? (disabledHint ?? '当前不可上传')
          : atCapacity
            ? '已达该字段允许的文件数量上限'
            : '点击选择，或将文件拖拽到此处'
      }}</span>
    </div>
    <div v-if="visibleItems.length" class="file-transfer-uploader__list" aria-live="polite">
      <div v-for="item in visibleItems" :key="item.id" class="file-transfer-uploader__item">
        <div class="file-transfer-uploader__name" :title="item.file.name">{{ item.file.name }}</div>
        <div class="file-transfer-uploader__state" :class="`is-${item.state}`">{{ stateText(item) }}</div>
        <div class="file-transfer-uploader__actions">
          <UiButton v-if="item.state === 'ready'" type="link" @click="upload(item)"> 上传 </UiButton>
          <UiButton
            v-else-if="item.state === 'failed' || item.state === 'cancelled'"
            type="link"
            @click="retry(item)"
          >
            重试
          </UiButton>
          <UiButton
            v-if="['requesting', 'uploading'].includes(item.state)"
            type="link"
            danger
            @click="cancel(item)"
          >
            取消
          </UiButton>
          <UiButton
            v-else-if="!['confirming', 'completed'].includes(item.state) || allowCompletedRemoval"
            type="link"
            danger
            @click="remove(item)"
          >
            移除
          </UiButton>
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
.file-transfer-uploader__input {
  display: none;
}
.file-transfer-uploader__choose-button {
  width: fit-content;
}
.file-transfer-uploader__drop-zone {
  display: grid;
  justify-items: center;
  gap: 4px;
  min-height: 112px;
  padding: 20px;
  color: var(--ant-color-text-secondary);
  background: var(--ant-color-fill-quaternary);
  border: 1px dashed var(--ant-color-border-secondary);
  border-radius: 8px;
  cursor: pointer;
  outline: none;
  transition:
    border-color 160ms ease,
    background-color 160ms ease,
    box-shadow 160ms ease;
}
.file-transfer-uploader__drop-zone:hover,
.file-transfer-uploader__drop-zone:focus-visible,
.file-transfer-uploader__drop-zone.is-dragging {
  background: var(--ant-color-primary-bg);
  border-color: var(--ant-color-primary);
  box-shadow: 0 0 0 3px var(--ant-color-primary-bg);
}
.file-transfer-uploader__drop-zone.is-disabled {
  cursor: not-allowed;
  opacity: 0.65;
}
.file-transfer-uploader__drop-zone-icon {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  color: var(--ant-color-primary);
  font-size: 22px;
  font-weight: 300;
  line-height: 1;
  background: var(--ant-color-primary-bg);
  border-radius: 50%;
}
.file-transfer-uploader__drop-zone-title {
  color: var(--ant-color-text);
  font-size: 14px;
}
.file-transfer-uploader__drop-zone-hint {
  font-size: 12px;
}
.file-transfer-uploader__list {
  display: grid;
  gap: 6px;
}
.file-transfer-uploader__item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 12px;
  padding: 8px 10px;
  border: 1px solid var(--ant-color-border-secondary);
  border-radius: 6px;
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
.file-transfer-uploader__actions {
  display: flex;
  gap: 4px;
}
</style>
