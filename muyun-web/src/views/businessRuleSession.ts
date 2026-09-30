import { computed, effectScope, ref, watch } from 'vue';
import {
  AssistantCapabilityUsageError,
  withHttpHeaders,
  type HttpClient,
  type AssistantOperationProposal,
} from '@muyun/web-core';
import {
  businessRuleChangeImpact,
  editableProposals,
  readonlyRules,
  proposalFingerprintOf,
  presentableFormulaExpression,
  toProposal,
  type BusinessRuleSnapshot,
  type UiControlSnapshot,
  type BusinessRuleProposal,
  type BusinessRulePreview,
  type BusinessRuleTrialResult,
  type BusinessRuleApplyResult,
  type BusinessRuleIssue,
} from './businessRuleGovernance';
import type { BusinessRuleAssistantAdapter, BusinessRuleTrialInput } from './businessRuleAssistantSurface';

export class BusinessRulePrecheckError extends AssistantCapabilityUsageError {
  constructor(readonly issues: BusinessRuleIssue[]) {
    super(issues.map((issue) => issue.message).join('；'));
  }
}

/** One in-memory candidate shared by manual editing and conversation; server baselines remain authoritative. */
export function createBusinessRuleSession(
  http: HttpClient,
  moduleAlias: string,
  ownerValid: () => boolean = () => true,
  confirmationScope: () => boolean = () => true,
) {
  const scope = effectScope(true);
  let disposed = false;
  const valid = () => !disposed && ownerValid();
  return scope.run(() => {
    const snapshot = ref<BusinessRuleSnapshot>();
    const ui = ref<UiControlSnapshot>();
    const rules = ref<BusinessRuleProposal[]>([]);
    const loading = ref(false),
      loadFailed = ref(false),
      applying = ref(false);
    const tenantId = ref('');
    const revision = ref(0);
    const editors = new Set<symbol>();
    const editing = ref(false);
    const dirty = computed(
      () =>
        !!snapshot.value &&
        proposalFingerprintOf(rules.value) !== proposalFingerprintOf(editableProposals(snapshot.value)),
    );
    const ready = computed(() => !!snapshot.value && !loading.value && !applying.value && !loadFailed.value);
    const path = `/platform.module/${encodeURIComponent(moduleAlias)}/business-rules`;
    let loadEpoch = 0;
    watch([rules, snapshot, ui, tenantId], () => revision.value++, { deep: true, flush: 'sync' });
    function requireValid() {
      if (!valid()) throw new AssistantCapabilityUsageError('身份已变化，请重新读取规则');
    }
    function requireReady() {
      requireValid();
      if (!ready.value) throw new AssistantCapabilityUsageError('请等待规则加载或应用完成');
    }
    async function merge(loaded: BusinessRuleSnapshot) {
      const controls = await http.request<UiControlSnapshot>({ path: path + '/ui-controls' });
      return {
        controls,
        loaded: {
          ...loaded,
          rules: [
            ...loaded.rules,
            ...controls.rules.map((rule) => ({ ...rule, kind: 'UI_CONTROL', phase: 'UI', editable: true })),
          ],
        },
      };
    }
    function accept(result: Awaited<ReturnType<typeof merge>>) {
      ui.value = result.controls;
      snapshot.value = result.loaded;
      rules.value = editableProposals(result.loaded);
    }
    // Publish the loaded baseline through the caller's guarded commit when selecting from the assistant.
    async function load(force = false, commit: (accept: () => void) => void = (accept) => accept()) {
      requireValid();
      if (applying.value) throw new AssistantCapabilityUsageError('规则正在应用');
      if (snapshot.value && !loadFailed.value && !force) {
        commit(() => {});
        return;
      }
      if (dirty.value) throw new AssistantCapabilityUsageError('请先应用或放弃当前未保存更改');
      const epoch = ++loadEpoch;
      loading.value = true;
      loadFailed.value = false;
      try {
        const loaded = await http.request<BusinessRuleSnapshot>({ path });
        if (epoch !== loadEpoch || !valid()) return;
        if (loaded.moduleAlias !== moduleAlias) throw new Error('规则模块与请求不一致');
        const result = await merge(loaded);
        if (epoch !== loadEpoch || !valid()) return;
        commit(() => accept(result));
      } catch (error) {
        if (epoch === loadEpoch && valid()) loadFailed.value = true;
        throw error;
      } finally {
        if (epoch === loadEpoch) loading.value = false;
      }
    }
    function replace(next: BusinessRuleProposal[]) {
      requireReady();
      rules.value = next;
    }
    function discard() {
      requireReady();
      rules.value = editableProposals(snapshot.value!);
    }
    function capture() {
      requireReady();
      const captured = revision.value;
      return () => valid() && captured === revision.value && !loading.value && !loadFailed.value;
    }
    async function preview(signal?: AbortSignal) {
      requireReady();
      return http.request<BusinessRulePreview>({
        method: 'POST',
        path: path + '/preview',
        body: { rules: rules.value.filter((rule) => rule.kind !== 'UI_CONTROL') },
        signal,
      });
    }
    async function trial(input: BusinessRuleTrialInput, signal?: AbortSignal) {
      requireReady();
      const scoped = tenantId.value ? withHttpHeaders(http, { 'X-MuYun-Tenant-Id': tenantId.value }) : http;
      return scoped.request<BusinessRuleTrialResult>({
        method: 'POST',
        path: path + '/trial',
        body: { rules: rules.value.filter((rule) => rule.kind !== 'UI_CONTROL'), ...input },
        signal,
      });
    }
    async function apply(stillCurrent: () => boolean = () => true) {
      requireReady();
      if (!dirty.value) return;
      if (editing.value) throw new AssistantCapabilityUsageError('请先完成当前规则编辑');
      const captured = capture();
      const current = () => captured() && stillCurrent();
      const proposed = rules.value.map(toProposal);
      const baselineFingerprint = snapshot.value!.baselineFingerprint;
      const uiBaselineFingerprint = ui.value?.baselineFingerprint;
      applying.value = true;
      try {
        const checked = await http.request<BusinessRulePreview>({
          method: 'POST',
          path: path + '/preview',
          body: { rules: proposed.filter((rule) => rule.kind !== 'UI_CONTROL') },
        });
        if (!current()) throw new Error('规则候选已变化，请重新检查');
        if (checked.errors.length) throw new BusinessRulePrecheckError(checked.errors);
        if (!checked.proposalFingerprint) throw new Error('检查结果无效，请重新加载后再应用');
        const result = await http.request<BusinessRuleApplyResult>({
          method: 'POST',
          path: path + '/apply',
          body: {
            rules: proposed.filter((rule) => rule.kind !== 'UI_CONTROL'),
            uiRules: proposed
              .filter((rule) => rule.kind === 'UI_CONTROL')
              .map(({ code, formKey, expression, enabled, targets }) => ({
                code,
                formKey,
                expression,
                enabled,
                targets,
              })),
            baselineFingerprint,
            uiBaselineFingerprint,
            proposalFingerprint: checked.proposalFingerprint,
          },
        });
        if (!current()) throw new Error('提交期间上下文已变化，请重新核实规则状态');
        const merged = await merge(result.snapshot);
        if (!current()) throw new Error('提交期间上下文已变化，请重新核实规则状态');
        accept(merged);
      } finally {
        applying.value = false;
      }
    }
    const adapter: BusinessRuleAssistantAdapter = {
      summary: () => ({
        moduleAlias,
        editable: valid() && ready.value && !editing.value,
      }),
      catalog: (section) =>
        section === 'fields'
          ? (snapshot.value?.editableFields ?? [])
          : section === 'childFields'
            ? (snapshot.value?.childFields ?? [])
            : section === 'aggregateFields'
              ? (snapshot.value?.aggregateFields ?? [])
              : section === 'functions'
                ? (snapshot.value?.functions ?? [])
                : section === 'forms'
                  ? (ui.value?.forms ?? [])
                  : [...rules.value, ...(snapshot.value ? readonlyRules(snapshot.value) : [])],
      revise(rule) {
        requireReady();
        if (editing.value) throw new AssistantCapabilityUsageError('请先完成页面中的规则编辑');
        if (snapshot.value!.rules.some((item) => item.code === rule.code && !item.editable))
          throw new AssistantCapabilityUsageError('不能覆盖只读规则');
        if (
          rule.kind === 'CALCULATION' &&
          ![...snapshot.value!.editableFields, ...(snapshot.value!.childFields ?? [])].some(
            (field) => field.fieldName === rule.targetField,
          )
        )
          throw new AssistantCapabilityUsageError('计算目标必须来自主表或直接子表的可写字段目录');
        if (rule.kind === 'UI_CONTROL') {
          const form = ui.value?.forms.find((form) => form.key === rule.formKey);
          if (
            !form ||
            !rule.targets?.length ||
            rule.targets.some((target) => !form.elements.some((element) => element.key === target.elementKey))
          )
            throw new AssistantCapabilityUsageError('界面目标必须来自表单目录');
        }
        const index = rules.value.findIndex((item) => item.code === rule.code);
        const next = rules.value.slice();
        if (index < 0) next.push(rule);
        else next[index] = rule;
        replace(next);
      },
      preview,
      trial,
      async prepareConfirmation(signal): Promise<AssistantOperationProposal> {
        requireReady();
        if (!dirty.value) throw new AssistantCapabilityUsageError('当前没有待应用的规则更改');
        if (editing.value) throw new AssistantCapabilityUsageError('请先完成页面中的规则编辑');
        const captured = capture();
        const current = () => captured() && confirmationScope();
        const checked = await preview(signal);
        if (!current()) throw new AssistantCapabilityUsageError('规则候选已变化，请重新检查');
        if (checked.errors.length) throw new BusinessRulePrecheckError(checked.errors);
        const impact = businessRuleChangeImpact(snapshot.value!, rules.value);
        const describe = (rule: BusinessRuleProposal) => {
          const fields = [
            ...snapshot.value!.editableFields,
            ...(snapshot.value!.aggregateFields ?? []),
            ...(snapshot.value!.childFields ?? []),
          ];
          const target = fields.find((field) => field.fieldName === rule.targetField);
          const details = [
            `${{ CALCULATION: '字段计算', VALIDATION: '保存校验', UI_CONTROL: '界面控制' }[rule.kind]} ${rule.kind === 'CALCULATION' ? (target?.title || rule.targetField) + ' = ' : ''}${presentableFormulaExpression(rule.expression, fields)}（应用后${rule.enabled ? '启用' : '停用'}）`,
          ];
          if (rule.messageTemplate) details.push('失败提示：' + rule.messageTemplate);
          if (rule.kind === 'UI_CONTROL') {
            const form = ui.value?.forms.find((form) => form.key === rule.formKey);
            details.push('表单：' + (form?.title || rule.formKey));
            for (const target of rule.targets ?? [])
              details.push(
                `${form?.elements.find((item) => item.key === target.elementKey)?.label || target.elementKey}：${target.hide ? '隐藏' : '显示'}、${target.readOnly ? '只读' : '可编辑'}`,
              );
          }
          return details.join('；');
        };
        return {
          presentation: {
            title: '确认应用业务规则',
            lines: [
              moduleAlias,
              ...impact.added.map((rule) => '新增：' + describe(rule)),
              ...impact.modified.map((rule) => '修改：' + describe(rule)),
              ...impact.deleted.map((rule) => '删除：' + describe(rule)),
              '应用当前整组更改，包含页面已有人工编辑；影响后续业务操作，不回算历史记录。草稿仅保留在当前工作区，刷新后不会恢复。',
            ],
          },
          modelSummary: '规则更改等待用户确认，尚未应用。',
          confirmLabel: '确认应用规则',
          expiresAt: Date.now() + 10 * 60_000,
          isCurrent: () => current() && !editing.value && !applying.value,
          async execute() {
            if (!current() || editing.value) throw new Error('规则候选已变化');
            await apply(current);
            return { title: '业务规则已应用', lines: [moduleAlias] };
          },
          lookup: async () => undefined,
        };
      },
    };
    return {
      moduleAlias,
      dispose() {
        disposed = true;
        scope.stop();
      },
      invalidateConfirmations: () => revision.value++,
      snapshot,
      ready,
      ui,
      rules,
      loading,
      loadFailed,
      applying,
      tenantId,
      revision,
      dirty,
      editing,
      adapter,
      load,
      replace,
      discard,
      apply,
      trial,
      setEditing(owner: symbol, value: boolean) {
        if (value) editors.add(owner);
        else editors.delete(owner);
        editing.value = !!editors.size;
      },
    };
  })!;
}
export type BusinessRuleSession = ReturnType<typeof createBusinessRuleSession>;
