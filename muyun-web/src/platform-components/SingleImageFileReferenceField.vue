<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiButton } from '@muyun/vue-ui-antdv';
import type { ModuleContext } from '@muyun/web-core';
import type { ResolvedFileReferenceFieldDescriptor } from '@muyun/web-contracts';
import RecordFileReferenceTransfer from './RecordFileReferenceTransfer.vue';
import { fileReferenceIds, issueFileReferenceAccess } from './fileReferenceTransfer';

defineOptions({ name: 'SingleImageFileReferenceField' });

const props = defineProps<{
  label: string;
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

const emit = defineEmits<{ 'update:value': [value: string | undefined] }>();
const fileId = computed(() => fileReferenceIds(props.value)[0]);
const previewUrl = ref<string>();
const previewLoading = ref(false);
const previewError = ref<string>();
const uploadText = computed(() => (fileId.value ? '替换图片' : '选择图片上传'));

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
  <div class="single-image-file-reference-field">
    <div class="single-image-file-reference-field__header">
      <span class="single-image-file-reference-field__label">
        {{ label }}
        <strong v-if="required" aria-hidden="true">*</strong>
      </span>
      <div v-if="fileId" class="single-image-file-reference-field__actions">
        <template v-if="definition.readAvailable">
          <UiButton type="link" :disabled="previewLoading" @click="viewOriginal">查看</UiButton>
          <UiButton type="link" @click="download">下载</UiButton>
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
          upload-button-type="link"
          :show-completed-upload-items="false"
          :release-completed-upload-on-bind="true"
          :upload-advisory="uploadAdvisory"
          :upload-validation="uploadValidation"
          @update:value="emit('update:value', typeof $event === 'string' ? $event : undefined)"
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
    />
    <div v-else class="single-image-file-reference-field__preview" :class="{ 'has-image': !!previewUrl }">
      <img v-if="previewUrl" :src="previewUrl" alt="已上传图片预览" />
      <template v-else>
        <span class="single-image-file-reference-field__state-icon" aria-hidden="true">{{
          fileId ? '◫' : '+'
        }}</span>
        <strong>{{ previewLoading ? '正在加载预览' : fileId ? '预览暂不可用' : '尚未配置图片' }}</strong>
        <span>{{
          previewLoading ? '请稍候' : (previewError ?? uploadHint ?? '可上传 PNG、JPG、GIF 或 WebP 图片')
        }}</span>
      </template>
    </div>
  </div>
</template>

<style scoped>
.single-image-file-reference-field {
  display: grid;
  gap: 10px;
}
.single-image-file-reference-field__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 22px;
  gap: 16px;
}
.single-image-file-reference-field__label {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 4px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.single-image-file-reference-field__label strong {
  color: var(--muyun-danger-base);
  font-weight: 600;
}
.single-image-file-reference-field__preview {
  display: grid;
  min-height: 156px;
  place-content: center;
  justify-items: center;
  gap: 5px;
  overflow: hidden;
  color: var(--muyun-support-text-muted);
  text-align: center;
  background: var(--muyun-theme-soft);
  border: 1px dashed var(--muyun-theme-border);
  border-radius: 10px;
}
.single-image-file-reference-field__preview.has-image {
  background: var(--muyun-support-surface);
  border-style: solid;
}
.single-image-file-reference-field__preview img {
  display: block;
  width: 100%;
  height: 154px;
  padding: 12px;
  object-fit: contain;
}
.single-image-file-reference-field__actions {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
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
.single-image-file-reference-field__actions :deep(.ant-btn-link) {
  height: auto;
  padding: 0;
}
</style>
