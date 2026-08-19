<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { ModulePageCardAssistantContext } from '@muyun/dynamic-page-runtime';
import { UiInput, UiRadioGroup, type UiRadioOption } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'PasswordPolicyPreview' });

type PreviewScope = 'CURRENT' | 'ALL';

interface PasswordPolicyRuleSnapshot {
  id?: string;
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
  diagnostic?: string;
}

const props = defineProps<{ context: ModulePageCardAssistantContext }>();
const scope = ref<PreviewScope>('CURRENT');
const password = ref('');
const authoritativeAllRules = ref<readonly PasswordPolicyRuleSnapshot[]>();
const allRulesLoadFailed = ref(false);
const allRulesLoading = ref(false);
let allRulesRequestVersion = 0;

const currentRule = computed(() => asRule(props.context.record));
const hasCurrentRule = computed(
  () => props.context.mode !== 'view' || normalizedId(currentRule.value?.id) !== undefined,
);
const scopeOptions = computed<readonly UiRadioOption[]>(() => [
  { value: 'CURRENT', label: '本规则', disabled: !hasCurrentRule.value },
  { value: 'ALL', label: '全规则' },
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
  [scope, () => props.context.formSessionKey],
  ([nextScope]) => {
    if (nextScope === 'ALL') void loadAuthoritativeAllRules();
  },
  { immediate: true },
);

async function loadAuthoritativeAllRules() {
  const requestVersion = ++allRulesRequestVersion;
  allRulesLoading.value = true;
  allRulesLoadFailed.value = false;
  authoritativeAllRules.value = undefined;
  try {
    const rules = await props.context.module.http.request<PasswordPolicyRuleSnapshot[]>({
      path: '/iam.password_policy_rule/active-global-rules',
    });
    if (requestVersion !== allRulesRequestVersion) return;
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
  const rules = authoritativeAllRules.value.filter(participatesInAllRules);
  const currentId = normalizedId(current?.id);
  if (currentId) {
    const index = rules.findIndex((rule) => normalizedId(rule.id) === currentId);
    if (index >= 0) rules.splice(index, 1);
  }
  if (current && participatesInAllRules(current)) {
    rules.push(current);
  }
  return rules.length > 0 ? rules.sort(compareRules) : [];
});

const results = computed<PasswordPolicyPreviewRuleResult[]>(() => {
  if (!password.value) return [];
  return previewRules.value.map((rule, index) => evaluateRule(rule, password.value, index));
});

const summary = computed(() => {
  if (!hasCurrentRule.value && scope.value === 'CURRENT') return '请选择或新建一条规则';
  if (scope.value === 'ALL' && allRulesLoading.value) return '正在加载权威规则集';
  if (scope.value === 'ALL' && allRulesLoadFailed.value) return '无法加载权威规则集';
  if (!password.value) return '输入测试密码后即时反馈';
  if (results.value.length === 0) return '当前没有参与试算的启用规则';
  const failed = results.value.filter((item) => !item.passed).length;
  return failed === 0
    ? `全部通过（${results.value.length}/${results.value.length}）`
    : `${failed} 条规则未通过`;
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
  const title = normalizedText(rule.title, '未命名规则');
  const message = normalizedText(rule.message, '未配置失败提示');
  const pattern = rule.pattern;
  if (!pattern?.trim()) {
    return { key: ruleKey(rule, index), title, message, passed: false, diagnostic: '正则表达式不能为空' };
  }
  try {
    // Java validates with Matcher.matches(); wrapping keeps browser feedback full-string based too.
    const passed = new RegExp(`^(?:${pattern})$`).test(value);
    return { key: ruleKey(rule, index), title, message, passed };
  } catch {
    return {
      key: ruleKey(rule, index),
      title,
      message,
      passed: false,
      diagnostic: '当前浏览器不支持该正则语法；保存时仍由服务端 Java 正则校验',
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
  return rule.enabled !== false && (rule.scopeType == null || rule.scopeType.toLowerCase() === 'global');
}
</script>

<template>
  <section class="password-policy-preview">
    <header>
      <h3>密码试算</h3>
      <span :class="{ failed: results.some((item) => !item.passed) }">{{ summary }}</span>
    </header>

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
      placeholder="输入测试密码"
      aria-label="测试密码"
    />

    <ul v-if="results.length" class="preview-results">
      <li v-for="item in results" :key="item.key" :class="{ passed: item.passed, failed: !item.passed }">
        <strong>{{ item.title }}</strong>
        <span>{{ item.diagnostic ?? (item.passed ? '通过' : item.message) }}</span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.password-policy-preview {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  background: var(--muyun-hover-subtle);
}

.password-policy-preview header {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 12px;
}

.password-policy-preview h3 {
  margin: 0;
}

.password-policy-preview h3 {
  color: var(--muyun-text);
  font-size: 15px;
}

.password-policy-preview header span,
.preview-results span {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.password-policy-preview header span {
  color: var(--muyun-success-text);
  font-weight: 700;
  text-align: right;
}

.password-policy-preview header span.failed {
  color: var(--muyun-danger-text);
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
  padding: 9px 10px;
  border: 1px solid var(--muyun-border);
  border-radius: 6px;
  background: var(--muyun-surface);
}

.preview-results li.passed {
  border-color: rgb(16 185 129 / 35%);
}

.preview-results li.failed {
  border-color: rgb(239 68 68 / 35%);
}

.preview-results strong {
  color: var(--muyun-text-body);
  font-size: 13px;
}
</style>
