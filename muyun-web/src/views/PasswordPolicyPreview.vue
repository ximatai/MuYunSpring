<script setup lang="ts">
import {
  useLanguageContext,
  platformCodeTextReference,
  recordPresentationText,
  presentationLabel,
} from '@muyun/web-core';
import type { TextReference, TextPresentation, RecordTextPresentation } from '@muyun/web-contracts';
import { computed, ref, watch } from 'vue';
import type { ModulePageCardAssistantContext } from '@muyun/dynamic-page-runtime';
import { UiInput, UiRadioGroup, type UiRadioOption } from '@muyun/vue-ui-antdv';
import { RecordDetailExtensionSection } from '@muyun/platform-components';

const language = useLanguageContext();

defineOptions({ name: 'PasswordPolicyPreview' });

type PreviewScope = 'CURRENT' | 'ALL';

interface PasswordPolicyRuleSnapshot extends RecordTextPresentation {
  id?: string;
  tenantId?: string;
  title?: string;
  pattern?: string;
  message?: string;
  enabled?: boolean;
  scopeType?: string;
  sortOrder?: number;
}

interface PasswordPolicyPreviewRuleResult {
  key: string;
  title: string;
  message: string;
  passed: boolean;
  titleSource?: TextReference;
  messageSource?: TextReference;
  diagnostic?: TextReference;
  titleText?: TextPresentation;
  messageText?: TextPresentation;
}

const props = defineProps<{ context: ModulePageCardAssistantContext }>();
const scope = ref<PreviewScope>('CURRENT');
const password = ref('');
const authoritativeAllRules = ref<readonly PasswordPolicyRuleSnapshot[]>();
const effectiveComplexityMode = ref<string>();
const usesDefaultRules = ref(false);
const allRulesLoadFailed = ref(false);
const allRulesLoading = ref(false);
let allRulesRequestVersion = 0;

const currentRule = computed(() => asRule(props.context.record));
const targetTenant = computed(() => {
  const tenant =
    props.context.record && 'tenantId' in props.context.record
      ? props.context.record.tenantId
      : props.context.navigatorRecords?.tenant?.id;
  return typeof tenant === 'string' && tenant.trim() ? tenant.trim() : undefined;
});
const hasCurrentRule = computed(
  () => props.context.mode !== 'view' || normalizedId(currentRule.value?.id) !== undefined,
);
const scopeOptions = computed<readonly UiRadioOption[]>(() => [
  {
    value: 'CURRENT',
    label: language.t('platform.screen.PasswordPolicyPreview.current-rule', '本规则'),
    disabled: !hasCurrentRule.value,
  },
  { value: 'ALL', label: language.t('platform.screen.PasswordPolicyPreview.all-rules', '全规则') },
]);

// A current-rule preview has no meaning while the detail card has no selected
// record (for example after the user intentionally clears a list selection).
// Keep the choice valid even if a stale UI event arrives during that transition.
watch(
  hasCurrentRule,
  (available) => {
    if (!available && scope.value === 'CURRENT') scope.value = 'ALL';
  },
  { immediate: true },
);

function updateScope(next: string) {
  if (next === 'CURRENT' && !hasCurrentRule.value) return;
  if (next === 'CURRENT' || next === 'ALL') scope.value = next;
}

// The all-rules snapshot deliberately belongs to the IAM preview, rather than
// the general card-assistant context: a generic explorer may be paged, scoped
// or showing recycle-bin data and therefore cannot authoritatively define this
// security policy. No request is made while a user types a trial password.
watch(
  [scope, () => props.context.formSessionKey, targetTenant],
  ([nextScope]) => {
    if (nextScope === 'ALL') void loadAuthoritativeAllRules();
  },
  { immediate: true },
);

watch(targetTenant, () => {
  password.value = '';
  authoritativeAllRules.value = undefined;
});

async function loadAuthoritativeAllRules() {
  const requestVersion = ++allRulesRequestVersion;
  allRulesLoading.value = true;
  allRulesLoadFailed.value = false;
  authoritativeAllRules.value = undefined;
  try {
    // The preview follows the selected target scope's effective rules, never a fixed global list.
    const effective = await props.context.module.http.request<{
      complexityMode: string;
      complexitySource: string;
      complexityRules: { id?: string; tenantId?: string; pattern: string; message: string }[];
    }>({
      path: '/iam.password_security_policy/effective',
      query: targetTenant.value ? { tenantId: targetTenant.value } : undefined,
    });
    if (requestVersion !== allRulesRequestVersion) return;
    const rules: PasswordPolicyRuleSnapshot[] = (effective.complexityRules ?? []).map((rule) => ({
      id: rule.id,
      tenantId: rule.tenantId,
      title: rule.message,
      pattern: rule.pattern,
      message: rule.message,
      enabled: true,
    }));
    effectiveComplexityMode.value = effective.complexityMode;
    usesDefaultRules.value = effective.complexitySource === 'DEFAULT';
    authoritativeAllRules.value = Object.freeze(rules.map((rule) => Object.freeze({ ...rule })));
  } catch {
    if (requestVersion !== allRulesRequestVersion) return;
    allRulesLoadFailed.value = true;
  } finally {
    if (requestVersion === allRulesRequestVersion) allRulesLoading.value = false;
  }
}

const previewRules = computed(() => {
  const current = hasCurrentRule.value ? currentRule.value : undefined;
  if (scope.value === 'CURRENT') return current ? [current] : [];

  if (!authoritativeAllRules.value) return [];
  let rules = [...authoritativeAllRules.value];
  const applies = !targetTenant.value || effectiveComplexityMode.value === 'custom';
  if (!applies) return rules;
  const currentId = normalizedId(current?.id);
  if (currentId) {
    const index = rules.findIndex((rule) => normalizedId(rule.id) === currentId);
    if (index >= 0) rules.splice(index, 1);
  }
  if (current && participatesInAllRules(current)) {
    if (usesDefaultRules.value) rules = [];
    rules.push(current);
  }
  return rules.length > 0
    ? rules.sort(compareRules)
    : [
        {
          pattern: '^.{6,}$',
          message: '密码长度不能少于 6 位',
          enabled: true,
        },
      ];
});

const results = computed<PasswordPolicyPreviewRuleResult[]>(() => {
  if (!password.value) return [];
  return previewRules.value.map((rule, index) => evaluateRule(rule, password.value, index));
});

// Validation depends only on raw rules and the password; language projects retained results.
const displayedResults = computed(() =>
  results.value.map((item) => ({
    ...item,
    title: item.titleSource
      ? language.t(item.titleSource.sourceId, item.title)
      : presentationLabel(item.title, item.titleText, language),
    message: item.messageSource
      ? language.t(item.messageSource.sourceId, item.message)
      : presentationLabel(item.message, item.messageText, language),
    diagnostic: item.diagnostic
      ? language.t(item.diagnostic.sourceId, item.diagnostic.originalText)
      : undefined,
  })),
);

const summary = computed(() => {
  if (!hasCurrentRule.value && scope.value === 'CURRENT')
    return language.t(
      'platform.screen.PasswordPolicyPreview.select-or-create-a-rule',
      '请选择或新建一条规则',
    );
  if (scope.value === 'ALL' && allRulesLoading.value)
    return language.t(
      'platform.screen.PasswordPolicyPreview.loading-the-authoritative-rules',
      '正在加载权威规则集',
    );
  if (scope.value === 'ALL' && allRulesLoadFailed.value)
    return language.t(
      'platform.screen.PasswordPolicyPreview.unable-to-load-the-authoritative-rules',
      '无法加载权威规则集',
    );
  if (!password.value)
    return language.t(
      'platform.screen.PasswordPolicyPreview.enter-a-test-password-for-immediate-feedback',
      '输入测试密码后即时反馈',
    );
  if (results.value.length === 0)
    return language.t(
      'platform.screen.PasswordPolicyPreview.no-enabled-rules-participate-in-this-preview',
      '当前没有参与试算的启用规则',
    );
  const failed = results.value.filter((item) => !item.passed).length;
  return failed === 0
    ? language.t(
        'platform.screen.PasswordPolicyPreview.all-passed-value-value',
        '全部通过（{value1}/{value2}）',
        { value1: results.value.length, value2: results.value.length },
      )
    : language.t('platform.screen.PasswordPolicyPreview.value-rules-failed', '{value1} 条规则未通过', {
        value1: failed,
      });
});

function asRule(
  value: Readonly<Record<string, unknown>> | undefined,
): PasswordPolicyRuleSnapshot | undefined {
  if (!value) return undefined;
  return value as PasswordPolicyRuleSnapshot;
}

function evaluateRule(
  rule: PasswordPolicyRuleSnapshot,
  value: string,
  index: number,
): PasswordPolicyPreviewRuleResult {
  const titleSource = rule.title?.trim()
    ? undefined
    : platformCodeTextReference('platform.screen.PasswordPolicyPreview.unnamed-rule');
  const messageSource = rule.message?.trim()
    ? undefined
    : platformCodeTextReference('platform.screen.PasswordPolicyPreview.no-failure-message-configured');
  const title = normalizedText(rule.title, titleSource?.originalText ?? '');
  const message = normalizedText(rule.message, messageSource?.originalText ?? '');
  const texts = {
    titleText: recordPresentationText(rule, 'title'),
    messageText: recordPresentationText(rule, 'message'),
  };
  const pattern = rule.pattern;
  if (!pattern?.trim()) {
    return {
      ...texts,
      key: ruleKey(rule, index),
      title,
      message,
      titleSource,
      messageSource,
      passed: false,
      diagnostic: platformCodeTextReference(
        'platform.screen.PasswordPolicyPreview.regular-expression-required',
      ),
    };
  }
  try {
    // Java validates with Matcher.matches(); wrapping keeps browser feedback full-string based too.
    const passed = new RegExp(`^(?:${pattern})$`).test(value);
    return { ...texts, key: ruleKey(rule, index), title, message, titleSource, messageSource, passed };
  } catch {
    return {
      ...texts,
      key: ruleKey(rule, index),
      title,
      message,
      titleSource,
      messageSource,
      passed: false,
      diagnostic: platformCodeTextReference(
        'platform.screen.PasswordPolicyPreview.browser-regex-unsupported',
      ),
    };
  }
}

function compareRules(left: PasswordPolicyRuleSnapshot, right: PasswordPolicyRuleSnapshot) {
  return (
    (left.sortOrder ?? Number.MAX_SAFE_INTEGER) - (right.sortOrder ?? Number.MAX_SAFE_INTEGER) ||
    normalizedText(left.title, '').localeCompare(normalizedText(right.title, ''))
  );
}

function ruleKey(rule: PasswordPolicyRuleSnapshot, index: number) {
  return normalizedId(rule.id) ?? `draft:${index}`;
}

function normalizedId(value: string | undefined) {
  return value?.trim() || undefined;
}

function normalizedText(value: string | undefined, fallback: string) {
  return value?.trim() || fallback;
}

function participatesInAllRules(rule: PasswordPolicyRuleSnapshot) {
  return rule.enabled !== false && normalizedId(rule.tenantId) === targetTenant.value;
}
</script>

<template>
  <RecordDetailExtensionSection
    class="password-policy-preview"
    :title="language.t('platform.screen.PasswordPolicyPreview.password-preview', '密码试算')"
    :subtitle="summary"
  >
    <div class="preview-content">
      <UiRadioGroup
        class="preview-scope"
        :value="scope"
        :options="scopeOptions"
        size="small"
        @update:value="updateScope"
      />

      <UiInput
        v-model:value="password"
        type="text"
        autocomplete="off"
        :placeholder="
          language.t('platform.screen.PasswordPolicyPreview.enter-a-test-password', '输入测试密码')
        "
        :aria-label="language.t('platform.screen.PasswordPolicyPreview.test-password', '测试密码')"
      />

      <ul v-if="results.length" class="preview-results">
        <li
          v-for="item in displayedResults"
          :key="item.key"
          :class="{ passed: item.passed, failed: !item.passed }"
        >
          <strong>{{ item.title }}</strong>
          <span>{{
            item.diagnostic ??
            (item.passed ? language.t('platform.workflow-record.check-passed', '通过') : item.message)
          }}</span>
        </li>
      </ul>
    </div>
  </RecordDetailExtensionSection>
</template>

<style scoped>
.preview-content {
  display: grid;
  gap: 12px;
}

.preview-results span {
  color: var(--muyun-text-muted);
  font-size: var(--muyun-font-size-helper, 12px);
}

.preview-scope {
  justify-self: start;
}

.preview-results {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.preview-results li {
  display: grid;
  gap: 3px;
}

.preview-results li.passed span {
  color: var(--muyun-success-text);
}

.preview-results li.failed span {
  color: var(--muyun-danger-text);
}

.preview-results strong {
  color: var(--muyun-text-body);
  font-size: var(--muyun-font-size-body, 13px);
}
</style>
