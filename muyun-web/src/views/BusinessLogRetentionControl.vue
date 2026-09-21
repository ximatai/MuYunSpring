<script setup lang="ts">
import { computed, ref } from 'vue';
import { presentPlatformError, RecordDetailDrawer } from '@muyun/platform-components';
import { useModuleContext } from '@muyun/web-core';
import {
  UiActionButton,
  UiEmpty,
  UiError,
  UiInput,
  UiSpin,
  UiSwitch,
  confirmAction,
  showInfoMessage,
  showSuccessMessage,
} from '@muyun/vue-ui-antdv';
import {
  createBusinessLogRetentionClient,
  type BusinessLogEventType,
  type BusinessLogRetentionPolicy,
} from './businessLogRetentionClient';

defineOptions({ name: 'BusinessLogRetentionControl' });

const props = defineProps<{
  eventTypes: BusinessLogEventType[];
}>();

const retentionContext = useModuleContext<Record<string, unknown>>({
  moduleAlias: 'platform.business_log_retention',
  runtimeAccess: 'VIEW',
});
const client = createBusinessLogRetentionClient(retentionContext.http);
const open = ref(false);
const policies = ref<BusinessLogRetentionPolicy[]>([]);
const persistedPolicies = ref<Partial<Record<BusinessLogEventType, BusinessLogRetentionPolicy>>>({});
const loading = ref(false);
const loadError = ref<string>();
const savingType = ref<BusinessLogEventType>();
const purgingType = ref<BusinessLogEventType>();

const canView = computed(() => retentionContext.can('viewRetentionPolicies') === true);
const canConfigure = computed(() => retentionContext.can('configureRetentionPolicy') === true);
const canPurge = computed(() => retentionContext.can('purgeExpiredLogs') === true);
const operationPending = computed(() => savingType.value !== undefined || purgingType.value !== undefined);
const visiblePolicies = computed(() => {
  const visibleTypes = new Set(props.eventTypes);
  return policies.value.filter((policy) => visibleTypes.has(policy.eventType));
});
const hasDirtyPolicy = computed(() => visiblePolicies.value.some(isDirty));

async function openPanel() {
  if (!canView.value) return;
  open.value = true;
  await loadPolicies();
}

async function loadPolicies() {
  loading.value = true;
  loadError.value = undefined;
  try {
    const loaded = await client.policies();
    const visibleTypes = new Set(props.eventTypes);
    const visible = loaded.filter((policy) => visibleTypes.has(policy.eventType));
    policies.value = visible.map((policy) => ({ ...policy }));
    persistedPolicies.value = Object.fromEntries(visible.map((policy) => [policy.eventType, { ...policy }]));
  } catch (error) {
    loadError.value = errorMessage(error);
    presentPlatformError(error, { source: 'business-log-retention', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

async function confirmClose() {
  if (operationPending.value) {
    showInfoMessage('自动清理操作正在执行，请稍候。');
    return false;
  }
  if (!hasDirtyPolicy.value) return true;
  return confirmAction({
    title: '放弃未保存的自动清理设置？',
    content: '关闭后，本次未保存的自动清理和保留天数修改将丢失。',
  });
}

async function save(policy: BusinessLogRetentionPolicy) {
  if (!validDays(policy.retentionDays) || !isDirty(policy) || operationPending.value) return;
  savingType.value = policy.eventType;
  try {
    const updated = await client.update(policy);
    replacePolicy(updated);
    showSuccessMessage(`${typeLabel(policy.eventType)}自动清理设置已保存`);
  } catch (error) {
    presentPlatformError(error, { source: 'business-log-retention', phase: 'action' });
  } finally {
    savingType.value = undefined;
  }
}

async function purge(policy: BusinessLogRetentionPolicy) {
  if (!validDays(policy.retentionDays) || operationPending.value) return;
  const confirmed = await confirmAction({
    title: `立即清理${typeLabel(policy.eventType)}`,
    content: `将删除严格早于 ${policy.retentionDays} 天的日志。本次清理不会保存对保留天数的修改，且操作不可恢复。`,
    requiredText: `清理${typeLabel(policy.eventType)}`,
    danger: true,
  });
  if (!confirmed) return;
  purgingType.value = policy.eventType;
  try {
    const run = await client.purge(policy.eventType, policy.retentionDays);
    if (run.result.status === 'ALREADY_RUNNING') {
      showInfoMessage('已有日志清理任务正在运行，本次未执行。');
      return;
    }
    showSuccessMessage(
      run.result.status === 'BATCH_LIMIT_REACHED'
        ? `本轮已清理 ${run.result.deletedCount} 条，剩余数据将在后续批次继续处理`
        : `已清理 ${run.result.deletedCount} 条${typeLabel(policy.eventType)}`,
    );
  } catch (error) {
    presentPlatformError(error, { source: 'business-log-retention', phase: 'action' });
  } finally {
    purgingType.value = undefined;
  }
}

function replacePolicy(updated: BusinessLogRetentionPolicy) {
  persistedPolicies.value = { ...persistedPolicies.value, [updated.eventType]: { ...updated } };
  policies.value = policies.value.map((policy) =>
    policy.eventType === updated.eventType ? { ...updated } : policy,
  );
}

function isDirty(policy: BusinessLogRetentionPolicy) {
  const persisted = persistedPolicies.value[policy.eventType];
  return (
    !persisted ||
    persisted.retentionDays !== policy.retentionDays ||
    persisted.automaticCleanupEnabled !== policy.automaticCleanupEnabled
  );
}

function updateDays(policy: BusinessLogRetentionPolicy, value: string) {
  policy.retentionDays = Number(value);
}

function validDays(value: number) {
  return Number.isInteger(value) && value >= 1 && value <= 36_500;
}

function typeLabel(type: BusinessLogEventType) {
  return {
    LOGIN: '登录日志',
    ACTION: '动作日志',
    REQUEST_ERROR: '异常日志',
    PAGE_ACCESS: '页面访问日志',
  }[type];
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '自动清理设置加载失败。';
}
</script>

<template>
  <UiActionButton v-if="canView" @click="openPanel">自动清理</UiActionButton>

  <RecordDetailDrawer
    :open="open"
    title="自动清理"
    subtitle="配置当前日志类型的自动清理和保留期限。"
    width="standard"
    scope="viewport"
    :before-close="confirmClose"
    @close="open = false"
  >
    <UiSpin v-if="loading" tip="加载自动清理设置" />
    <UiError v-else-if="loadError" title="自动清理设置不可用" :message="loadError" />
    <UiEmpty v-else-if="visiblePolicies.length === 0" description="暂无可管理的自动清理设置" />
    <section v-else class="business-log-retention-control__policies">
      <article v-for="policy in visiblePolicies" :key="policy.eventType">
        <header>
          <div>
            <h3>{{ typeLabel(policy.eventType) }}</h3>
            <small v-if="policy.updatedAt">
              最近更新：{{ policy.updatedAt
              }}<template v-if="policy.updatedBy"> · {{ policy.updatedBy }}</template>
            </small>
          </div>
          <label class="business-log-retention-control__automatic-cleanup">
            <span>自动清理</span>
            <UiSwitch
              v-model:checked="policy.automaticCleanupEnabled"
              :disabled="!canConfigure || operationPending"
              checked-text="启用"
              unchecked-text="停用"
            />
          </label>
        </header>

        <div class="business-log-retention-control__content">
          <label class="business-log-retention-control__retention-days">
            <span>保留天数</span>
            <span class="business-log-retention-control__retention-row">
              <UiInput
                :value="policy.retentionDays"
                type="number"
                :disabled="!canConfigure || operationPending"
                :aria-label="`${typeLabel(policy.eventType)}保留天数`"
                @update:value="updateDays(policy, $event)"
              />
              <UiActionButton
                v-if="canPurge"
                intent="danger"
                :disabled="operationPending || !validDays(policy.retentionDays)"
                :loading="purgingType === policy.eventType"
                @click="purge(policy)"
              >
                立即清理
              </UiActionButton>
            </span>
            <small v-if="!validDays(policy.retentionDays)" class="business-log-retention-control__validation">
              请输入 1 至 36500 之间的整数。
            </small>
          </label>
        </div>

        <footer>
          <UiActionButton
            v-if="canConfigure"
            emphasis="primary"
            :disabled="operationPending || !validDays(policy.retentionDays) || !isDirty(policy)"
            :loading="savingType === policy.eventType"
            @click="save(policy)"
          >
            保存
          </UiActionButton>
        </footer>
      </article>
    </section>
  </RecordDetailDrawer>
</template>

<style scoped>
.business-log-retention-control__policies {
  display: grid;
  gap: 16px;
}
.business-log-retention-control__policies article {
  display: grid;
  gap: 18px;
  padding: 20px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 10px;
  background: var(--muyun-surface-container);
}
.business-log-retention-control__policies header,
.business-log-retention-control__policies footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.business-log-retention-control__policies h3 {
  margin: 0;
}
.business-log-retention-control__policies small {
  color: var(--muyun-text-muted);
}
.business-log-retention-control__automatic-cleanup {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
}
.business-log-retention-control__content,
.business-log-retention-control__retention-days {
  display: grid;
  gap: 8px;
}
.business-log-retention-control__retention-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.business-log-retention-control__retention-row :deep(.ui-input) {
  flex: 1 1 auto;
  min-width: 0;
}
.business-log-retention-control__retention-row :deep(.ui-action-button) {
  flex: 0 0 auto;
}
.business-log-retention-control__policies footer {
  justify-content: flex-end;
}
.business-log-retention-control__validation {
  color: var(--muyun-danger-text) !important;
}
@media (max-width: 720px) {
  .business-log-retention-control__policies header {
    display: grid;
    grid-template-columns: 1fr;
  }
  .business-log-retention-control__automatic-cleanup {
    justify-self: start;
  }
}
</style>
