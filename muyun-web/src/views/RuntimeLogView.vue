<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import {
  DateTimeText,
  FileSizeText,
  RecordDetailDrawer,
  RecordQueryListPanel,
  presentPlatformError,
  type RecordQueryListColumn,
} from '@muyun/platform-components';
import { useModuleContext } from '@muyun/web-core';
import { UiButton, UiEmpty, UiError, UiActionButton } from '@muyun/vue-ui-antdv';
import { createRuntimeLogClient, type RuntimeLogFile } from './runtimeLogClient';
import { createRuntimeLogSseParser, type RuntimeLogSseEvent } from './runtimeLogSse';

defineOptions({ name: 'RuntimeLogView' });

const TAIL_LINES = 500;
const moduleContext = useModuleContext<RuntimeLogFile & Record<string, unknown>>({
  moduleAlias: 'platform.runtime_log',
});
const client = createRuntimeLogClient(moduleContext.http);
const listError = ref<string>();
const downloadingName = ref<string>();
const viewerOpen = ref(false);
const activeFileName = ref<string>();
const logText = ref('');
const streamState = ref<'connecting' | 'live' | 'rotated' | 'error' | 'closed'>('closed');
const streamNotice = ref<string>();
const logViewport = ref<HTMLElement>();
const followsTail = ref(true);
let streamRevision = 0;
let activeReader: ReadableStreamDefaultReader<Uint8Array> | undefined;
let activeAbortController: AbortController | undefined;
let rotationReconnects = 0;

const streamStatus = computed(() => {
  if (streamNotice.value) return streamNotice.value;
  return {
    connecting: '正在连接实时日志…',
    live: '正在实时更新',
    rotated: '日志文件已轮转，请重新连接。',
    error: '实时日志连接已中断。',
    closed: '实时日志已关闭。',
  }[streamState.value];
});
const canReconnect = computed(() => ['closed', 'error', 'rotated'].includes(streamState.value));

const fileColumns: RecordQueryListColumn[] = [
  { key: 'name', title: '文件名称', width: '360px' },
  { key: 'sizeBytes', title: '文件大小', width: '140px', align: 'right' as const },
  { key: 'lastModifiedAt', title: '最后修改时间', width: '208px' },
  { key: 'active', title: '状态', width: '132px' },
];

onBeforeUnmount(stopActiveStream);

watch(logText, () => {
  if (followsTail.value) void scrollToTail();
});

function openActiveLog() {
  viewerOpen.value = true;
  // The stream endpoint resolves the current active file itself. List search and
  // pagination only affect what is displayed; they must not disable this action.
  activeFileName.value = undefined;
  logText.value = '';
  followsTail.value = true;
  rotationReconnects = 0;
  connectActiveLog();
}

function reconnectActiveLog() {
  if (!viewerOpen.value) return;
  logText.value = '';
  followsTail.value = true;
  rotationReconnects = 0;
  connectActiveLog();
}

function closeViewer() {
  viewerOpen.value = false;
  activeFileName.value = undefined;
  stopActiveStream();
}

function connectActiveLog() {
  stopActiveStream();
  const revision = ++streamRevision;
  const controller = new AbortController();
  activeAbortController = controller;
  streamState.value = 'connecting';
  streamNotice.value = undefined;
  void consumeActiveLog(revision, controller);
}

async function consumeActiveLog(revision: number, controller: AbortController) {
  try {
    const stream = await client.activeStream(TAIL_LINES, controller.signal);
    if (controller.signal.aborted || revision !== streamRevision) {
      await stream.cancel();
      return;
    }
    const reader = stream.getReader();
    activeReader = reader;
    const decoder = new TextDecoder();
    const parser = createRuntimeLogSseParser((event) => handleStreamEvent(event, revision, controller));
    while (!controller.signal.aborted && revision === streamRevision) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value) parser.push(decoder.decode(value, { stream: true }));
    }
    parser.push(decoder.decode());
    parser.finish();
    if (!controller.signal.aborted && revision === streamRevision && streamState.value !== 'rotated') {
      streamState.value = 'closed';
      streamNotice.value = '实时日志连接已结束。';
    }
  } catch (error) {
    if (!controller.signal.aborted && revision === streamRevision) {
      streamState.value = 'error';
      streamNotice.value = errorMessage(error, '实时日志连接失败，请重新连接。');
      presentPlatformError(error, { source: 'runtime-log-stream', phase: 'load' });
    }
  } finally {
    if (revision === streamRevision) activeReader = undefined;
  }
}

function handleStreamEvent(event: RuntimeLogSseEvent, revision: number, controller: AbortController) {
  if (controller.signal.aborted || revision !== streamRevision) return;
  switch (event.event) {
    case 'snapshot':
    case 'reset': {
      const payload = eventPayload(event.data);
      logText.value = textOf(payload.text);
      activeFileName.value = optionalTextOf(payload.fileName) ?? activeFileName.value;
      streamState.value = 'live';
      streamNotice.value = event.event === 'reset' ? '日志内容已重置，正在继续实时更新。' : undefined;
      return;
    }
    case 'append': {
      const payload = eventPayload(event.data);
      logText.value += textOf(payload.text);
      streamState.value = 'live';
      streamNotice.value = undefined;
      return;
    }
    case 'rotated': {
      const payload = eventPayload(event.data);
      activeFileName.value = optionalTextOf(payload.fileName) ?? activeFileName.value;
      if (rotationReconnects < 1) {
        rotationReconnects += 1;
        streamState.value = 'connecting';
        streamNotice.value = '日志文件已轮转，正在重新连接最新文件…';
        void nextTick(() => connectActiveLog());
      } else {
        streamState.value = 'rotated';
        streamNotice.value = '日志文件再次轮转，请手动重新连接。';
        stopActiveStream();
      }
      return;
    }
    case 'error': {
      const payload = eventPayload(event.data);
      streamState.value = 'error';
      streamNotice.value = optionalTextOf(payload.message) ?? '实时日志连接出现错误，请重新连接。';
      stopActiveStream();
    }
  }
}

function stopActiveStream() {
  streamRevision += 1;
  activeAbortController?.abort();
  activeAbortController = undefined;
  const reader = activeReader;
  activeReader = undefined;
  if (reader) void reader.cancel().catch(() => undefined);
}

async function download(file: RuntimeLogFile) {
  if (downloadingName.value) return;
  downloadingName.value = file.name;
  try {
    const stream = await client.download(file.name);
    const bytes = await readStream(stream);
    const content = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
    const url = URL.createObjectURL(new Blob([content], { type: 'text/plain;charset=utf-8' }));
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = file.name;
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch (error) {
    listError.value = errorMessage(error, `下载 ${file.name} 失败，请稍后重试。`);
    presentPlatformError(error, { source: 'runtime-log-download', phase: 'load' });
  } finally {
    downloadingName.value = undefined;
  }
}

async function readStream(stream: ReadableStream<Uint8Array>): Promise<Uint8Array> {
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let totalLength = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value) {
        chunks.push(value);
        totalLength += value.length;
      }
    }
  } finally {
    reader.releaseLock();
  }
  const result = new Uint8Array(totalLength);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.length;
  }
  return result;
}

function handleLogScroll() {
  const viewport = logViewport.value;
  if (!viewport) return;
  followsTail.value = viewport.scrollTop + viewport.clientHeight >= viewport.scrollHeight - 24;
}

async function scrollToTail() {
  await nextTick();
  const viewport = logViewport.value;
  if (viewport) viewport.scrollTop = viewport.scrollHeight;
}

function eventPayload(data: string): Record<string, unknown> {
  try {
    const value: unknown = JSON.parse(data);
    return value && typeof value === 'object' && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
}

function textOf(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function optionalTextOf(value: unknown): string | undefined {
  return typeof value === 'string' && value ? value : undefined;
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}
</script>

<template>
  <section class="runtime-log-view">
    <UiError v-if="listError" class="runtime-log-view__error" :message="listError" />

    <RecordQueryListPanel
      class="runtime-log-view__list"
      :context="moduleContext"
      title="程序日志"
      :show-title="false"
      :columns="fileColumns"
      row-key="name"
      :show-recycle-bin="false"
      :standard-crud-actions="false"
      :page-size="20"
      :page-size-options="[10, 20, 50]"
      empty-description="当前日志目录中没有可用的日志文件"
      :row-actions-of="() => []"
      row-actions-title="操作"
      action-column-width="176"
    >
      <template #operations>
        <div class="runtime-log-view__header-actions">
          <UiButton type="primary" icon-name="eye" @click="openActiveLog"> 实时查看 </UiButton>
        </div>
      </template>
      <template #cell="{ column, record }">
        <FileSizeText
          v-if="column.key === 'sizeBytes'"
          :value="(record as unknown as RuntimeLogFile).sizeBytes"
        />
        <DateTimeText
          v-else-if="column.key === 'lastModifiedAt'"
          :value="(record as unknown as RuntimeLogFile).lastModifiedAt"
        />
        <span v-else-if="column.key === 'active'">{{
          (record as unknown as RuntimeLogFile).active ? '正在写入' : '已归档'
        }}</span>
        <span v-else>{{ (record as unknown as RuntimeLogFile)[column.key as keyof RuntimeLogFile] }}</span>
      </template>
      <template #rowActions="{ record }">
        <div class="runtime-log-view__row-actions">
          <UiButton
            v-if="record.active === true"
            type="link"
            size="small"
            icon-name="eye"
            @click="openActiveLog"
          >
            实时查看
          </UiButton>
          <UiButton
            type="link"
            size="small"
            icon-name="download"
            :loading="downloadingName === record.name"
            :disabled="Boolean(downloadingName) && downloadingName !== record.name"
            @click="download(record as unknown as RuntimeLogFile)"
          >
            下载
          </UiButton>
        </div>
      </template>
    </RecordQueryListPanel>

    <RecordDetailDrawer
      :open="viewerOpen"
      :title="activeFileName ? `实时日志 · ${activeFileName}` : '实时日志'"
      :subtitle="streamStatus"
      width="standard"
      render-mode="inline"
      dismissal="dismissible"
      @close="closeViewer"
    >
      <template #header-actions>
        <UiActionButton
          v-if="canReconnect"
          emphasis="quiet"
          icon-name="reload"
          title="重新连接"
          @click="reconnectActiveLog"
        />
      </template>
      <div ref="logViewport" class="runtime-log-view__viewport" @scroll="handleLogScroll">
        <pre v-if="logText" class="runtime-log-view__content">{{ logText }}</pre>
        <UiEmpty
          v-else
          :description="streamState === 'connecting' ? '正在读取日志末尾内容…' : '暂无日志内容'"
        />
      </div>
    </RecordDetailDrawer>
  </section>
</template>

<style scoped>
.runtime-log-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  min-height: 0;
  gap: 16px;
}

.runtime-log-view__list {
  flex: 1 1 auto;
  min-height: 0;
}

.runtime-log-view__header-actions,
.runtime-log-view__row-actions {
  display: flex;
  align-items: center;
}

.runtime-log-view__header-actions {
  flex-wrap: wrap;
  gap: 8px;
}

.runtime-log-view__row-actions {
  flex-wrap: nowrap;
  gap: 4px;
  white-space: nowrap;
}

.runtime-log-view__viewport {
  height: 100%;
  min-height: 0;
  overflow: auto;
  border: 1px solid var(--muyun-border);
  border-radius: 6px;
  background: var(--muyun-surface);
  color: var(--muyun-text);
}

.runtime-log-view__content {
  margin: 0;
  padding: 16px;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
}

.runtime-log-view__viewport :deep(.ant-empty) {
  color: var(--muyun-text-muted);
}
</style>
