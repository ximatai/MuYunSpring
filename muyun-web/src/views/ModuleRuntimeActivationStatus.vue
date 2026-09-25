<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, watch } from 'vue';
import type { DynamicRuntimeActivationStatus, WebActionResultEnvelope } from '@muyun/web-contracts';
import { actionResultData, useModuleContext } from '@muyun/web-core';
import { presentPlatformError, RecordStatusTag } from '@muyun/platform-components';
import type { ModuleActivationFeedback } from './moduleRuntimeActivation';
import { UiActionButton, UiPopover } from '@muyun/vue-ui-antdv';

const props = defineProps<{ moduleAlias: string; reloadKey?: number }>();
const context = useModuleContext({ moduleAlias: 'platform.module' });
const status = ref<DynamicRuntimeActivationStatus>();
const loading = ref(false);
const retrying = ref(false);
const loadFailed = ref(false);
let requestSequence = 0;
let active = true;
let timer: ReturnType<typeof setTimeout> | undefined;
const waiting = computed(() => {
  const current = status.value;
  return Boolean(
    current &&
    current.status !== 'UNTRACKED' &&
    current.status !== 'FAILED' &&
    (current.status === 'PENDING' || current.installedRevision !== current.desiredRevision),
  );
});
const needsAttention = computed(() => loadFailed.value || status.value?.status === 'FAILED' || waiting.value);
const label = computed(() =>
  loadFailed.value ? '状态未知' : status.value?.status === 'FAILED' ? '生效失败' : '正在应用修改',
);
const tone = computed(() =>
  loadFailed.value ? 'NEUTRAL' : status.value?.status === 'FAILED' ? 'DANGER' : 'WARNING',
);
const guidance = computed(() => {
  if (loadFailed.value) return '暂时无法查询，请稍后重试。';
  if (status.value?.status === 'FAILED') return '修改已保存，但未能生效。';
  return '完成后将自动关闭提示。';
});
const message = computed(() => {
  if (loadFailed.value) return '暂时无法查询模块运行配置状态';
  const current = status.value;
  if (!current || current.status === 'UNTRACKED') return '尚无模块运行配置生效记录';
  if (current.status === 'FAILED') return '模块运行配置已保存，生效失败';
  if (current.status === 'PENDING') return '模块运行配置已保存，正在生效';
  if (current.installedRevision !== current.desiredRevision)
    return '模块运行配置已保存，当前节点尚未确认生效';
  return current.status === 'INACTIVE' ? '模块运行配置已生效，业务运行入口已停用' : '模块运行配置已生效';
});
const canRetry = computed(
  () =>
    status.value?.desiredRevision != null &&
    (status.value.status === 'FAILED' ||
      status.value.status === 'PENDING' ||
      status.value.installedRevision !== status.value.desiredRevision) &&
    context.can('retryRuntimeActivation') === true,
);

watch(
  () => [props.moduleAlias, props.reloadKey],
  () => {
    requestSequence++;
    status.value = undefined;
    retrying.value = false;
    void load();
  },
  { immediate: true },
);

function stopPolling() {
  clearTimeout(timer);
  timer = undefined;
}
function schedulePoll() {
  stopPolling();
  if (active && waiting.value && !loadFailed.value) timer = setTimeout(() => void load(), 2000);
}
onDeactivated(() => {
  active = false;
  requestSequence++;
  stopPolling();
});
onActivated(() => {
  if (active) return;
  active = true;
  retrying.value = false;
  void load();
});
onUnmounted(() => {
  active = false;
  requestSequence++;
  stopPolling();
});

async function load(): Promise<ModuleActivationFeedback | undefined> {
  if (!active || retrying.value) return;
  stopPolling();
  const sequence = ++requestSequence;
  const alias = props.moduleAlias;
  loadFailed.value = false;
  loading.value = true;
  try {
    const result = await context.http.request<DynamicRuntimeActivationStatus>({
      method: 'GET',
      path: `/platform.module/${encodeURIComponent(alias)}/runtime/activation`,
    });
    if (sequence === requestSequence) {
      status.value = result;
      return {
        message: {
          text: message.value,
          type:
            result.status === 'FAILED'
              ? 'WARNING'
              : waiting.value || result.status === 'UNTRACKED'
                ? 'INFO'
                : 'SUCCESS',
        },
      };
    }
  } catch {
    if (sequence !== requestSequence) return;
    loadFailed.value = true;
  } finally {
    if (sequence === requestSequence) {
      loading.value = false;
      schedulePoll();
    }
  }
}

async function retry() {
  if (!canRetry.value || retrying.value) return;
  stopPolling();
  const sequence = ++requestSequence;
  const alias = props.moduleAlias;
  const revision = status.value!.desiredRevision!;
  retrying.value = true;
  try {
    const result = await context.http.request<
      DynamicRuntimeActivationStatus | WebActionResultEnvelope<DynamicRuntimeActivationStatus>
    >({
      method: 'POST',
      path: `/platform.module/${encodeURIComponent(alias)}/runtime/activation/retry?expectedRevision=${revision}`,
    });
    if (sequence === requestSequence) {
      status.value = actionResultData(result);
      loadFailed.value = false;
    }
  } catch (cause) {
    if (sequence !== requestSequence) return;
    presentPlatformError(cause, { source: 'runtime-activation', phase: 'action' });
    retrying.value = false;
    await load();
  } finally {
    if (sequence === requestSequence) {
      retrying.value = false;
      schedulePoll();
    }
  }
}
defineExpose({ refresh: load });
</script>

<template>
  <section
    v-if="needsAttention"
    class="module-runtime-activation"
    aria-label="模块运行配置生效状态"
    aria-live="polite"
  >
    <div class="module-runtime-activation__summary">
      <span class="module-runtime-activation__label">运行配置</span>
      <RecordStatusTag :enabled="true" :enabled-label="label" :enabled-tone="tone" />
      <span class="module-runtime-activation__guidance">{{ guidance }}</span>
    </div>
    <div class="module-runtime-activation__actions">
      <UiActionButton
        v-if="canRetry"
        density="compact"
        :disabled="loading || retrying"
        :loading="retrying"
        @click="retry"
        >重试生效</UiActionButton
      >
      <UiActionButton
        v-if="loadFailed"
        density="compact"
        :disabled="loading || retrying"
        icon-name="reload"
        @click="load"
        >重新查询</UiActionButton
      >
      <UiPopover :key="moduleAlias" placement="bottomRight">
        <UiActionButton density="compact" emphasis="quiet" title="查看诊断信息">
          {{ status?.status === 'FAILED' ? '查看原因' : '诊断信息' }}
        </UiActionButton>
        <template #content>
          <div class="module-runtime-activation__details">
            <strong>诊断信息</strong>
            <p class="module-runtime-activation__scope">模块运行配置的生效情况</p>
            <p v-if="loadFailed">状态查询失败，请重新查询。</p>
            <p v-else-if="status?.status === 'FAILED'" class="module-runtime-activation__failure">
              {{ status.failureMessage || '暂未提供失败原因，请重试或联系管理员。' }}
            </p>
            <p v-else-if="waiting">服务正在加载最新配置，状态将自动更新。</p>
            <dl v-if="status" class="module-runtime-activation__versions">
              <dt>配置版本</dt>
              <dd>{{ status.desiredRevision ?? '—' }}</dd>
              <dt>已加载版本</dt>
              <dd>{{ status.installedRevision ?? '—' }}</dd>
            </dl>
            <UiActionButton
              density="compact"
              emphasis="quiet"
              icon-name="reload"
              :disabled="loading || retrying"
              @click="load"
              >刷新状态</UiActionButton
            >
          </div>
        </template>
      </UiPopover>
    </div>
  </section>
</template>

<style scoped>
.module-runtime-activation,
.module-runtime-activation__summary,
.module-runtime-activation__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.module-runtime-activation__label {
  color: var(--muyun-text-secondary);
}
.module-runtime-activation {
  font-size: 12px;
  flex: 1 0 100%;
  justify-content: space-between;
  box-sizing: border-box;
  padding: 10px 12px;
  border: 1px solid var(--muyun-warning-border);
  border-radius: 8px;
  background: var(--muyun-warning-soft);
}
.module-runtime-activation__summary {
  flex-wrap: wrap;
}
.module-runtime-activation__guidance {
  color: var(--muyun-warning-soft-text);
}
.module-runtime-activation__actions {
  flex: 0 0 auto;
  gap: 6px;
}
.module-runtime-activation__details {
  width: min(280px, calc(100vw - 64px));
  font-size: 13px;
  color: var(--muyun-text-body);
  overflow-wrap: anywhere;
}
.module-runtime-activation__scope {
  margin: 4px 0 12px;
  color: var(--muyun-text-secondary);
  font-size: 12px;
}
.module-runtime-activation__failure {
  color: var(--muyun-danger-soft-text);
}
.module-runtime-activation__versions {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 6px 16px;
  margin: 12px 0;
}
.module-runtime-activation__versions dt {
  color: var(--muyun-text-secondary);
}
.module-runtime-activation__versions dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
}
@media (max-width: 720px) {
  .module-runtime-activation {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
