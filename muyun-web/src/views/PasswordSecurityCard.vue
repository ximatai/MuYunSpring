<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import {
  AppError,
  actionResultData,
  createModuleContext,
  withHttpHeaders,
  useLanguageContext,
} from '@muyun/web-core';
import type { WebActionResultEnvelope } from '@muyun/web-contracts';
import type { ModulePageCardAssistantContext, ModulePageWorkspaceDraft } from '@muyun/dynamic-page-runtime';
import { UiActionButton, UiInput } from '@muyun/vue-ui-antdv';
import {
  RecordDetailExtensionSection,
  RecordDetailFields,
  RecordQueryListSurface,
  type QueryListRecord,
} from '@muyun/platform-components';
import PasswordSecurityRules from './PasswordSecurityRules.vue';
import { evaluatePasswordRules } from './passwordSecurityTrial';

defineOptions({ name: 'PasswordSecurityCard' });
interface EffectiveRuleView {
  pattern: string;
  message: string;
  enabled?: boolean;
}
interface EffectivePolicyView {
  tenantId: string | null;
  policyId: string | null;
  complexityMode: string;
  lockoutMode: string;
  complexitySource: 'TENANT' | 'PLATFORM' | 'DEFAULT';
  complexityRules: EffectiveRuleView[];
  platformComplexityRules?: EffectiveRuleView[];
  lockoutSource: 'TENANT' | 'PLATFORM_DB';
  maxFailedAttempts: number;
  lockDurationSeconds: number | null;
}
const props = defineProps<{ context: ModulePageCardAssistantContext }>();
const language = useLanguageContext();
const effective = ref<EffectivePolicyView>();
const rules = ref<QueryListRecord[]>([]);
const originalRules = ref<QueryListRecord[]>([]);
const loading = ref(false);
const failed = ref(false);
const rulesAccessible = ref(false);
const ruleEditorOpen = ref(false);
const testPassword = ref('');
let requestVersion = 0;
const targetTenant = computed<string | undefined>(() => {
  const value =
    props.context.record && 'tenantId' in props.context.record
      ? props.context.record.tenantId
      : props.context.navigatorRecords?.tenant?.id;
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
});
const targetContext = computed(() =>
  createModuleContext<QueryListRecord>({
    moduleAlias: 'iam.password_policy_rule',
    http: targetTenant.value
      ? withHttpHeaders(props.context.module.http, { 'X-MuYun-Tenant-Id': targetTenant.value })
      : props.context.module.http,
    language,
  }),
);
const editing = computed(() => props.context.mode !== 'view');
const custom = computed(
  () =>
    !targetTenant.value ||
    (editing.value
      ? props.context.record?.complexityMode === 'custom'
      : effective.value?.complexityMode === 'custom'),
);
const fallback: EffectiveRuleView[] = [{ pattern: '^.{6,}$', message: '密码长度不能少于 6 位' }];
const activeDraft = computed<EffectiveRuleView[]>(() =>
  rules.value
    .filter(
      (rule) =>
        rule.enabled !== false && typeof rule.pattern === 'string' && typeof rule.message === 'string',
    )
    .map((rule) => ({ pattern: String(rule.pattern), message: String(rule.message), enabled: true })),
);
const trialUnavailable = computed(
  () =>
    editing.value && custom.value && !rulesAccessible.value && effective.value?.complexityMode !== 'custom',
);
const trialRules = computed(() => {
  if (!editing.value) return effective.value?.complexityRules ?? [];
  if (trialUnavailable.value) return [];
  if (custom.value)
    return rulesAccessible.value
      ? activeDraft.value.length
        ? activeDraft.value
        : fallback
      : (effective.value?.complexityRules ?? []);
  return (
    effective.value?.platformComplexityRules ??
    (effective.value?.complexityMode === 'inherit' ? effective.value.complexityRules : [])
  );
});
const trialResults = computed(() => evaluatePasswordRules(trialRules.value, testPassword.value));
const trialSummary = computed(() => {
  if (!testPassword.value || trialResults.value.length === 0)
    return language.t('platform.screen.PasswordSecurityCard.no-local-rules', '暂无参与试算的规则');
  const failed = trialResults.value.filter((item) => item.status === 'failed').length;
  const unsupported = trialResults.value.filter((item) => item.status === 'unsupported').length;
  if (!failed && !unsupported)
    return language.t('platform.screen.PasswordSecurityCard.trial-passed', '全部通过');
  if (!failed)
    return language.t(
      'platform.screen.PasswordSecurityCard.trial-unsupported',
      '{value1} 条规则浏览器不支持该正则，未参与判定',
      { value1: unsupported },
    );
  return language.t('platform.screen.PasswordSecurityCard.trial-failed', '{value1} 条规则未通过', {
    value1: failed,
  });
});
const summary = computed(() =>
  !effective.value
    ? ''
    : custom.value
      ? targetTenant.value
        ? language.t('platform.screen.PasswordSecurityCard.complexity-tenant', '租户自定义')
        : language.t('platform.screen.PasswordSecurityCard.platform-default', '平台默认')
      : language.t('platform.screen.PasswordSecurityCard.complexity-platform', '继承平台'),
);
const ruleColumns = computed(() => [
  {
    key: 'message',
    dataIndex: 'message',
    title: language.t('platform.screen.PasswordSecurityCard.rule', '复杂度规则'),
  },
]);
const inheritedRows = computed(() =>
  trialRules.value.map((rule, index) => ({ id: String(index), message: rule.message || rule.pattern })),
);
const copy = (rows: QueryListRecord[]) => rows.map((row) => ({ ...row }));
async function load() {
  const token = ++requestVersion;
  const tenantId = targetTenant.value;
  const context = targetContext.value;
  loading.value = true;
  failed.value = false;
  try {
    const snapshot = await props.context.module.http.request<EffectivePolicyView>({
      path: '/iam.password_security_policy/effective',
      query: tenantId ? { tenantId } : undefined,
    });
    let page: { records: QueryListRecord[] } | undefined;
    try {
      await context.runtime.ready;
      page = await context.crud.query({
        unpaged: true,
        conditions: [{ fieldName: 'scopeType', operator: 'EQ', values: [tenantId ? 'tenant' : 'global'] }],
        ...(tenantId ? { externalQueryValues: { tenantId } } : {}),
      });
      // Explorer rows are compact projections, not editable records. Load canonical details
      // before creating the draft so an omitted pattern, description or sort order is never cleared.
      page = {
        records: await Promise.all(page.records.map((record) => context.crud.view(String(record.id)))),
      };
    } catch (error) {
      if (!(error instanceof AppError) || error.status !== 403) throw error;
      page = undefined;
    }
    if (token !== requestVersion) return;
    if (page?.records.some((record) => !record.id || typeof record.version !== 'number'))
      throw new Error('规则记录缺少身份或版本，无法安全编辑');
    await Promise.allSettled((page?.records ?? []).map((record) => context.recordActions(String(record.id))));
    if (token !== requestVersion) return;
    effective.value = snapshot;
    rulesAccessible.value = Boolean(page);
    originalRules.value = copy(page?.records ?? []);
    rules.value = copy(page?.records ?? []);
  } catch {
    if (token !== requestVersion) return;
    failed.value = true;
    effective.value = undefined;
    rulesAccessible.value = false;
    originalRules.value = [];
    rules.value = [];
  } finally {
    if (token === requestVersion) loading.value = false;
  }
}
const handler: ModulePageWorkspaceDraft = {
  ready: () => !loading.value && !failed.value && !ruleEditorOpen.value && Boolean(effective.value),
  dirty: () => JSON.stringify(rules.value) !== JSON.stringify(originalRules.value),
  async save({ record, mode, recordDirty, requestId }) {
    if (!handler.ready())
      throw new AppError('密码规则尚未完整加载，请重新打开编辑', { status: 400, code: 'VALIDATION_FAILED' });
    if (
      targetTenant.value &&
      record.complexityMode === 'custom' &&
      effective.value?.complexityMode !== 'custom' &&
      rulesAccessible.value &&
      !activeDraft.value.length
    )
      throw new AppError('切换为自定义时，请至少添加一条启用的密码规则', {
        status: 400,
        code: 'VALIDATION_FAILED',
      });
    const result = await props.context.module.http.request<
      { record: QueryListRecord } | WebActionResultEnvelope<{ record: QueryListRecord }>
    >({
      method: 'POST',
      path: '/iam.password_security_policy/workspace-save',
      headers: requestId ? { 'X-Muyun-Save-Request': requestId } : undefined,
      body: {
        tenantId: targetTenant.value ?? null,
        policy: recordDirty || mode === 'create' ? { ...record, tenantId: targetTenant.value ?? null } : null,
        originalRules: rulesAccessible.value
          ? originalRules.value.map(({ id, version }) => ({ id, version }))
          : null,
        rules: rulesAccessible.value
          ? rules.value.map(({ id, version, title, pattern, message, description, enabled, sortOrder }) => ({
              id,
              version,
              title,
              pattern,
              message,
              description,
              enabled,
              sortOrder,
            }))
          : null,
      },
    });
    return { ...result, ...actionResultData(result) };
  },
};
watch(
  () => props.context.registerWorkspaceDraft,
  (register, previous) => {
    previous?.(undefined);
    register?.(handler);
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  requestVersion++;
  props.context.registerWorkspaceDraft?.(undefined);
});
watch(
  targetTenant,
  () => {
    testPassword.value = '';
    effective.value = undefined;
    void load();
  },
  { immediate: true },
);
watch(
  () => props.context.mode,
  (mode, previous) => {
    if (mode !== previous) void load();
  },
);
</script>

<template>
  <RecordDetailExtensionSection
    v-if="!context.record && context.mode === 'view' && effective && !failed"
    :title="language.t('platform.password-security.lockout-title', '登录失败锁定')"
    :subtitle="language.t('platform.password-security.inherited-lockout', '继承平台配置')"
  >
    <RecordDetailFields
      :record="{
        maxFailedAttempts: effective.maxFailedAttempts,
        lockDurationSeconds: effective.lockDurationSeconds,
      }"
      :field-names="
        effective.maxFailedAttempts === 0
          ? ['maxFailedAttempts']
          : ['maxFailedAttempts', 'lockDurationSeconds']
      "
      :fallback="{
        maxFailedAttempts: {
          label: language.t('platform.password-security.failed-attempts', '失败次数阈值'),
        },
        lockDurationSeconds: {
          label: language.t('platform.password-security.lock-seconds', '锁定时长（秒）'),
        },
      }"
    />
  </RecordDetailExtensionSection>
  <RecordDetailExtensionSection
    :title="language.t('platform.password-security.complexity-rules-title', '复杂度规则')"
    :subtitle="
      loading
        ? language.t('platform.screen.PasswordSecurityCard.loading', '加载中')
        : failed
          ? language.t('platform.screen.PasswordSecurityCard.load-failed', '无法加载有效策略')
          : summary
    "
    class="password-security-card"
  >
    <template #actions>
      <UiActionButton v-if="!editing" emphasis="quiet" density="compact" :disabled="loading" @click="load">
        {{ language.t('platform.screen.PasswordSecurityCard.refresh', '刷新') }}
      </UiActionButton>
    </template>
    <p v-if="editing" class="note">
      {{
        language.t(
          'platform.password-security.draft-note',
          '正在编辑尚未保存的密码安全配置；试算基于当前草稿。',
        )
      }}
    </p>
    <p
      v-if="
        custom &&
        (editing && rulesAccessible ? activeDraft.length === 0 : effective?.complexitySource === 'DEFAULT')
      "
      class="note"
    >
      {{
        editing && targetTenant && effective?.complexityMode !== 'custom'
          ? language.t(
              'platform.password-security.custom-rule-required',
              '切换为自定义时，请至少添加一条启用的密码规则。',
            )
          : language.t(
              'platform.screen.PasswordSecurityCard.empty-custom-note',
              '本地复杂度规则为空时回落最低 6 位兜底，而非无约束。',
            )
      }}
    </p>
    <RecordQueryListSurface
      v-if="effective && !failed && (!custom || !rulesAccessible)"
      :columns="ruleColumns"
      :rows="inheritedRows"
      :loading="loading"
      :header-visible="false"
      :fill-height="false"
      :conditions-visible="false"
      embedded
      chrome-free
    />
    <PasswordSecurityRules
      v-if="effective && !failed && custom && rulesAccessible"
      :rows="rules"
      :original-rows="originalRules"
      :target-context="targetContext"
      :editing="editing"
      :disabled="loading || context.saving"
      @update:editing="ruleEditorOpen = $event"
      @update:rows="rules = $event"
    />
    <div v-if="effective && !loading && !failed" class="trial">
      <label
        >{{
          editing
            ? language.t('platform.password-security.trial-draft', '密码试算（未保存的配置）')
            : language.t('platform.screen.PasswordSecurityCard.trial', '密码试算')
        }}
        <UiInput
          v-model:value="testPassword"
          :disabled="trialUnavailable"
          autocomplete="off"
          :aria-label="language.t('platform.screen.PasswordSecurityCard.trial', '密码试算')"
          :placeholder="language.t('platform.screen.PasswordSecurityCard.trial-placeholder', '输入测试密码')"
        />
      </label>
      <p v-if="trialUnavailable" class="note">
        {{
          language.t('platform.password-security.trial-unavailable', '无法读取租户规则，当前草稿无法试算。')
        }}
      </p>
      <span v-if="testPassword">{{ trialSummary }}</span>
      <p class="note">
        {{
          language.t(
            'platform.password-security.trial-browser-note',
            '浏览器试算仅供参考，最终以服务端校验为准。',
          )
        }}
      </p>
      <ul v-if="testPassword" class="trial-results">
        <li v-for="item in trialResults" :key="item.key" :class="item.status">
          <span>{{ item.label }}</span
          ><span v-if="item.status === 'unsupported'">{{
            language.t(
              'platform.screen.PasswordSecurityCard.regex-unsupported',
              '浏览器不支持该正则，未参与判定',
            )
          }}</span>
        </li>
      </ul>
    </div>
  </RecordDetailExtensionSection>
</template>
<style scoped>
.note {
  margin: 0 0 8px;
  color: var(--muyun-text-muted);
  font-size: var(--muyun-font-size-helper, 12px);
}
.trial-results {
  display: grid;
  gap: 4px;
  margin: 0;
  padding-left: 20px;
}
.trial {
  display: grid;
  gap: 8px;
  margin-top: var(--muyun-detail-section-block-gap, 16px);
}
.failed {
  color: var(--muyun-danger-text);
}
.passed {
  color: var(--muyun-success-text);
}
.unsupported {
  color: var(--muyun-text-muted);
}
</style>
