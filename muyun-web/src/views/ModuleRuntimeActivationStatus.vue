<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { DynamicRuntimeActivationStatus, WebActionResultEnvelope } from '@muyun/web-contracts';
import { actionResultData, useModuleContext } from '@muyun/web-core';
import { presentPlatformError } from '@muyun/platform-components';
import { UiButton } from '@muyun/vue-ui-antdv';

const props = defineProps<{ moduleAlias: string; reloadKey?: number }>();
const context = useModuleContext({ moduleAlias: 'platform.module' });
const status = ref<DynamicRuntimeActivationStatus>();
const loading = ref(false);
const retrying = ref(false);
const loadFailed = ref(false);
let requestSequence = 0;
const message = computed(() => {
  if (loadFailed.value) return '暂时无法查询配置生效状态';
  const current = status.value;
  if (!current || current.status === 'UNTRACKED') return '尚无配置生效记录';
  if (current.status === 'FAILED') return '配置已提交，生效失败';
  if (current.status === 'PENDING') return '配置已提交，等待生效';
  if (current.installedRevision !== current.desiredRevision) return '配置已提交，当前节点尚未确认生效';
  return current.status === 'INACTIVE' ? '配置已生效，业务运行入口已停用' : '配置已生效';
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
  () => void load(),
  { immediate: true },
);

async function load() {
  const sequence = ++requestSequence;
  const alias = props.moduleAlias;
  status.value = undefined;
  loadFailed.value = false;
  retrying.value = false;
  loading.value = true;
  try {
    const result = await context.http.request<DynamicRuntimeActivationStatus>({
      method: 'GET',
      path: `/platform.module/${encodeURIComponent(alias)}/runtime/activation`,
    });
    if (sequence === requestSequence) status.value = result;
  } catch (cause) {
    if (sequence !== requestSequence) return;
    loadFailed.value = true;
    presentPlatformError(cause, { source: 'runtime-activation', phase: 'load' });
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
}

async function retry() {
  if (!canRetry.value || retrying.value) return;
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
    if (sequence === requestSequence) status.value = actionResultData(result);
  } catch (cause) {
    if (sequence !== requestSequence) return;
    presentPlatformError(cause, { source: 'runtime-activation', phase: 'action' });
    await load();
  } finally {
    if (sequence === requestSequence) retrying.value = false;
  }
}
</script>

<template>
  <section class="module-runtime-activation" aria-label="配置生效状态" aria-live="polite">
    <span>{{ loading ? '查询配置生效状态…' : message }}</span>
    <span v-if="status?.failureMessage">{{ status.failureMessage }}</span>
    <div>
      <UiButton :disabled="loading || retrying" @click="load">刷新状态</UiButton>
      <UiButton v-if="canRetry" :disabled="loading || retrying" @click="retry">重试生效</UiButton>
    </div>
  </section>
</template>

<style scoped>
.module-runtime-activation {
  display: grid;
  gap: 8px;
  padding: 12px;
  margin-bottom: 16px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  color: var(--muyun-text-body);
}
.module-runtime-activation > div {
  display: flex;
  gap: 8px;
}
</style>
