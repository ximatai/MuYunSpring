<script setup lang="ts">
import {
  normalizeError,
  userFacingErrorMessage,
  useLanguageContext,
  type ModuleContext,
} from '@muyun/web-core';

import RecordFieldLabel from './RecordFieldLabel.vue';
import { computed, ref, watch } from 'vue';
import { UiButton } from '@muyun/vue-ui-antdv';

import type { ResolvedFileReferenceFieldDescriptor } from '@muyun/web-contracts';
import RecordFileReferenceTransfer from './RecordFileReferenceTransfer.vue';
import { fileReferenceIds, issueFileReferenceAccess } from './fileReferenceTransfer';

const language = useLanguageContext();

defineOptions({ name: 'SingleImageFileReferenceField' });

const props = defineProps<{
  label: string;
  /** Hides the field title and places the available actions below the image. */
  hideLabel?: boolean;
  required?: boolean;
  value: unknown;
  record: Record<string, unknown>;
  context: ModuleContext<unknown>;
  definition: ResolvedFileReferenceFieldDescriptor;
  uploadHint?: string;
  uploadAdvisory?: (file: File) => string | undefined | Promise<string | undefined>;
  uploadValidation?: (file: File) => string | undefined | Promise<string | undefined>;
  formSessionKey?: string | number;
  disabled?: boolean;
  disabledHint?: string;
}>();

const emit = defineEmits<{
  'update:value': [value: string | undefined];
  'update:record-id': [recordId: string];
}>();
const fileId = computed(() => fileReferenceIds(props.value)[0]);
const previewUrl = ref<string>();
const previewLoading = ref(false);
const previewError = ref<() => string>();
let previewRevision = 0;
const uploadText = computed(() =>
  fileId.value
    ? language.t('platform.screen.SingleImageFileReferenceField.replace-image', '替换图片')
    : language.t('platform.screen.SingleImageFileReferenceField.select-image-to-upload', '选择图片上传'),
);

watch(
  [fileId, () => props.formSessionKey],
  () => {
    const revision = ++previewRevision;
    const requestedFileId = fileId.value;
    const requestedRecord = props.record;
    previewUrl.value = undefined;
    previewError.value = undefined;
    previewLoading.value = false;
    if (requestedFileId && props.definition.readAvailable)
      void loadPreview(revision, requestedFileId, requestedRecord);
  },
  { immediate: true },
);

async function loadPreview(
  revision = ++previewRevision,
  requestedFileId = fileId.value,
  requestedRecord = props.record,
) {
  if (!requestedFileId) return;
  previewLoading.value = true;
  previewError.value = undefined;
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
  } catch (error) {
    if (revision === previewRevision) {
      previewError.value = () =>
        error instanceof Error
          ? userFacingErrorMessage(normalizeError(error), language)
          : language.t(
              'platform.screen.SingleImageFileReferenceField.could-not-load-the-image-preview',
              '图片预览加载失败。',
            );
    }
  } finally {
    if (revision === previewRevision) previewLoading.value = false;
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

function viewOriginal() {
  if (!previewUrl.value) {
    void loadPreview();
    return;
  }
  const url = browserViewUrl(previewUrl.value);
  window.open(url, '_blank', 'noopener');
  if (url.startsWith('blob:')) {
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }
}

/** Clears only the draft binding; the enclosing record save decides whether to persist the unbind. */
function clearImage() {
  emit('update:value', undefined);
}

/** Chrome blocks script-initiated top-level data: navigation; Blob URLs retain the exact inline image bytes. */
function browserViewUrl(url: string) {
  const match = /^data:([^;,]+);base64,([A-Za-z0-9+/]+={0,2})$/i.exec(url);
  if (!match) return url;
  const binary = atob(match[2]);
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  return URL.createObjectURL(new Blob([bytes], { type: match[1] }));
}
</script>

<template>
  <div
    class="single-image-file-reference-field"
    :class="{ 'single-image-file-reference-field--hide-label': hideLabel }"
  >
    <div v-if="!hideLabel || fileId" class="single-image-file-reference-field__header">
      <RecordFieldLabel
        v-if="!hideLabel"
        class="single-image-file-reference-field__label"
        :required="required"
        >{{ label }}</RecordFieldLabel
      >
      <div v-if="fileId" class="single-image-file-reference-field__actions">
        <template v-if="definition.readAvailable">
          <UiButton
            type="text"
            size="small"
            icon-name="download"
            icon-only
            :title="language.t('platform.screen.SingleImageFileReferenceField.download', '下载')"
            :aria-label="language.t('platform.screen.SingleImageFileReferenceField.download', '下载')"
            @click="download"
          />
        </template>
        <RecordFileReferenceTransfer
          :value="value"
          :record="record"
          :context="context"
          :definition="definition"
          :form-session-key="formSessionKey"
          :disabled="disabled"
          :disabled-hint="disabledHint"
          :show-bound-files="false"
          uploader-presentation="button"
          :upload-text="uploadText"
          upload-button-type="text"
          upload-button-size="small"
          upload-button-icon-name="swap"
          :upload-button-title="
            language.t('platform.screen.SingleImageFileReferenceField.replace-image', '替换图片')
          "
          :show-completed-upload-items="false"
          :release-completed-upload-on-bind="true"
          :upload-advisory="uploadAdvisory"
          :upload-validation="uploadValidation"
          @update:value="emit('update:value', typeof $event === 'string' ? $event : undefined)"
          @update:record-id="emit('update:record-id', $event)"
        />
        <UiButton
          v-if="!disabled"
          type="text"
          size="small"
          danger
          icon-name="delete"
          icon-only
          :title="language.t('platform.screen.SingleImageFileReferenceField.clear', '清除')"
          :aria-label="language.t('platform.screen.SingleImageFileReferenceField.clear', '清除')"
          @click="clearImage"
        />
      </div>
    </div>
    <RecordFileReferenceTransfer
      v-if="!fileId"
      :value="value"
      :record="record"
      :context="context"
      :definition="definition"
      :form-session-key="formSessionKey"
      :disabled="disabled"
      :disabled-hint="disabledHint"
      :dropzone-hint="uploadHint"
      :show-bound-files="false"
      uploader-presentation="dropzone"
      :upload-text="uploadText"
      :show-completed-upload-items="false"
      :release-completed-upload-on-bind="true"
      :upload-advisory="uploadAdvisory"
      :upload-validation="uploadValidation"
      @update:value="emit('update:value', typeof $event === 'string' ? $event : undefined)"
      @update:record-id="emit('update:record-id', $event)"
    />
    <button
      v-else
      class="single-image-file-reference-field__preview"
      :class="{ 'has-image': !!previewUrl }"
      type="button"
      :disabled="!definition.readAvailable || previewLoading"
      :title="language.t('platform.screen.SingleImageFileReferenceField.view-original-image', '点击查看原图')"
      :aria-label="
        language.t('platform.screen.SingleImageFileReferenceField.view-original-image', '点击查看原图')
      "
      @click="viewOriginal"
    >
      <img
        v-if="previewUrl"
        :src="previewUrl"
        :alt="
          language.t('platform.screen.SingleImageFileReferenceField.uploaded-image-preview', '已上传图片预览')
        "
      />
      <template v-else>
        <span class="single-image-file-reference-field__state-icon" aria-hidden="true">{{
          fileId ? '◫' : '+'
        }}</span>
        <strong>{{
          previewLoading
            ? language.t('platform.screen.SingleImageFileReferenceField.loading-preview', '正在加载预览')
            : fileId
              ? language.t(
                  'platform.screen.SingleImageFileReferenceField.preview-unavailable',
                  '预览暂不可用',
                )
              : language.t(
                  'platform.screen.SingleImageFileReferenceField.no-image-configured',
                  '尚未配置图片',
                )
        }}</strong>
        <span>{{
          previewLoading
            ? language.t('platform.screen.SingleImageFileReferenceField.please-wait', '请稍候')
            : (previewError?.() ??
              uploadHint ??
              language.t(
                'platform.screen.SingleImageFileReferenceField.upload-a-png-jpg-gif-or-webp-image',
                '可上传 PNG、JPG、GIF 或 WebP 图片',
              ))
        }}</span>
      </template>
    </button>
  </div>
</template>

<style scoped>
.single-image-file-reference-field {
  --single-image-file-reference-area-height: 136px;
  display: grid;
  grid-template-rows: 30px var(--single-image-file-reference-area-height);
  align-content: start;
  gap: 10px;
}
.single-image-file-reference-field--hide-label {
  grid-template-rows: var(--single-image-file-reference-area-height) auto;
}
.single-image-file-reference-field--hide-label .single-image-file-reference-field__header {
  order: 1;
  grid-template-columns: 1fr;
}
.single-image-file-reference-field--hide-label .single-image-file-reference-field__actions {
  justify-self: center;
  gap: 0;
}
.single-image-file-reference-field__header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  height: 30px;
  gap: 8px;
}
.single-image-file-reference-field__label {
  display: inline-flex;
  min-width: max-content;
  align-items: center;
  gap: 4px;
  color: var(--muyun-text-muted);
  font-size: 13px;
  white-space: nowrap;
}

.single-image-file-reference-field__preview {
  display: grid;
  width: 100%;
  height: var(--single-image-file-reference-area-height);
  place-content: center;
  justify-items: center;
  gap: 5px;
  overflow: hidden;
  color: var(--muyun-support-text-muted);
  text-align: center;
  background: transparent;
  border: 1px dashed var(--muyun-theme-border);
  border-radius: 10px;
  padding: 0;
  cursor: zoom-in;
}
.single-image-file-reference-field :deep(.file-transfer-uploader__drop-zone) {
  height: var(--single-image-file-reference-area-height);
  min-height: var(--single-image-file-reference-area-height);
}
.single-image-file-reference-field__preview:disabled {
  cursor: default;
}
.single-image-file-reference-field__preview.has-image {
  border-style: solid;
}
.single-image-file-reference-field__preview img {
  display: block;
  width: 100%;
  height: 126px;
  padding: 12px;
  object-fit: contain;
}
.single-image-file-reference-field__actions {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  justify-self: end;
  gap: 14px;
}
.single-image-file-reference-field__state-icon {
  color: var(--muyun-theme-base);
  font-size: 24px;
  line-height: 1;
}
.single-image-file-reference-field__preview strong {
  color: var(--muyun-support-text-body);
  font-size: 13px;
  font-weight: 600;
}
.single-image-file-reference-field__preview > span:last-child {
  font-size: 12px;
}
.single-image-file-reference-field__actions :deep(.file-transfer-uploader) {
  display: contents;
}
</style>
