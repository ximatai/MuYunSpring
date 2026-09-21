<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { presentPlatformError } from '@muyun/platform-components';
import { useModuleContext } from '@muyun/web-core';
import {
  UiActionButton,
  UiEmpty,
  UiError,
  UiInput,
  UiSpin,
  UiSwitch,
  confirmAction,
  showSuccessMessage,
} from '@muyun/vue-ui-antdv';
import {
  createBusinessLogRetentionClient,
  type BusinessLogEventType,
  type BusinessLogRetentionPolicy,
} from './businessLogRetentionClient';

defineOptions({ name: 'BusinessLogRetentionView' });

const moduleContext = useModuleContext<Record<string, unknown>>({
  moduleAlias: 'platform.business_log_retention',
});
const client = createBusinessLogRetentionClient(moduleContext.http);
const policies = ref<BusinessLogRetentionPolicy[]>([]);
const loading = ref(false);
const loadError = ref<string>();
const savingType = ref<BusinessLogEventType>();
const purgingType = ref<BusinessLogEventType>();

const canConfigure = computed(() => moduleContext.can('configureRetentionPolicy') === true);
const canPurge = computed(() => moduleContext.can('purgeExpiredLogs') === true);

onMounted(loadPolicies);

async function loadPolicies() {
  loading.value = true;
  loadError.value = undefined;
  try {
    policies.value = await client.policies();
  } catch (error) {
    loadError.value = errorMessage(error);
    presentPlatformError(error, { source: 'business-log-retention', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

async function save(policy: BusinessLogRetentionPolicy) {
  if (!validDays(policy.retentionDays) || savingType.value) return;
  savingType.value = policy.eventType;
  try {
    const updated = await client.update(policy);
    replacePolicy(updated);
    showSuccessMessage(`${typeLabel(policy.eventType)}留存策略已保存`);
  } catch (error) {
    presentPlatformError(error, { source: 'business-log-retention', phase: 'action' });
  } finally {
    savingType.value = undefined;
  }
}

async function purge(policy: BusinessLogRetentionPolicy) {
  if (purgingType.value) return;
  const confirmed = await confirmAction({
    title: `立即清理${typeLabel(policy.eventType)}`,
    content: `将删除严格早于 ${policy.retentionDays} 天的日志。该操作不可恢复。`,
    requiredText: `清理${typeLabel(policy.eventType)}`,
    danger: true,
  });
  if (!confirmed) return;
  purgingType.value = policy.eventType;
  try {
    const run = await client.purge(policy.eventType);
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
  policies.value = policies.value.map((policy) =>
    policy.eventType === updated.eventType ? updated : policy,
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
  return error instanceof Error ? error.message : '日志留存策略加载失败。';
}
</script>

<template>
  <main class="retention-governance">
    <header>
      <div>
        <h1>日志留存</h1>
        <p>按日志类型管理自动清理和保留天数。调度器会读取最新策略并分批执行。</p>
      </div>
      <UiActionButton :loading="loading" @click="loadPolicies">刷新</UiActionButton>
    </header>

    <UiSpin v-if="loading" tip="加载留存策略" />
    <UiError v-else-if="loadError" title="留存策略不可用" :message="loadError" />
    <UiEmpty v-else-if="policies.length === 0" description="暂无日志留存策略" />
    <section v-else class="retention-governance__policies">
      <article v-for="policy in policies" :key="policy.eventType">
        <div class="retention-governance__identity">
          <h2>{{ typeLabel(policy.eventType) }}</h2>
          <small v-if="policy.updatedAt">
            最近更新：{{ policy.updatedAt
            }}<template v-if="policy.updatedBy"> · {{ policy.updatedBy }}</template>
          </small>
          <small v-else>尚未人工调整，使用平台安全默认值。</small>
        </div>
        <label>
          <span>自动清理</span>
          <UiSwitch
            v-model:checked="policy.automaticCleanupEnabled"
            :disabled="!canConfigure"
            checked-text="启用"
            unchecked-text="停用"
          />
        </label>
        <label>
          <span>保留天数</span>
          <UiInput
            :value="policy.retentionDays"
            type="number"
            :disabled="!canConfigure"
            :aria-label="`${typeLabel(policy.eventType)}保留天数`"
            @update:value="updateDays(policy, $event)"
          />
          <small v-if="!validDays(policy.retentionDays)" class="retention-governance__validation">
            请输入 1 至 36500 之间的整数。
          </small>
        </label>
        <div class="retention-governance__actions">
          <UiActionButton
            v-if="canConfigure"
            emphasis="primary"
            :disabled="!validDays(policy.retentionDays)"
            :loading="savingType === policy.eventType"
            @click="save(policy)"
          >
            保存策略
          </UiActionButton>
          <UiActionButton
            v-if="canPurge"
            intent="danger"
            :disabled="!validDays(policy.retentionDays)"
            :loading="purgingType === policy.eventType"
            @click="purge(policy)"
          >
            立即清理
          </UiActionButton>
        </div>
      </article>
    </section>
  </main>
</template>

<style scoped>
.retention-governance {
  display: grid;
  gap: 20px;
  min-height: 100%;
  padding: 24px;
  background: var(--muyun-surface-page);
}
.retention-governance > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.retention-governance h1,
.retention-governance h2,
.retention-governance p {
  margin: 0;
}
.retention-governance header p,
.retention-governance small {
  color: var(--muyun-text-muted);
}
.retention-governance__policies {
  display: grid;
  gap: 12px;
}
.retention-governance__policies article {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) minmax(160px, 0.45fr) minmax(220px, 0.55fr) auto;
  gap: 20px;
  align-items: center;
  padding: 18px 20px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 10px;
  background: var(--muyun-surface-container);
}
.retention-governance__identity,
.retention-governance label {
  display: grid;
  gap: 7px;
}
.retention-governance__actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
.retention-governance__validation {
  color: var(--muyun-danger-text) !important;
}
@media (max-width: 960px) {
  .retention-governance__policies article {
    grid-template-columns: 1fr;
  }
  .retention-governance__actions {
    justify-content: flex-start;
  }
}
</style>
