import { computed, effectScope, ref, watch } from 'vue';
import {
  codeOperationErrorMessage,
  platformLanguageContext,
  type LanguageContext,
  AppError,
  OperationUsageError,
  OperationRejectedError,
  withHttpHeaders,
  type HttpClient,
  type OperationProposal,
} from '@muyun/web-core';
import {
  businessRuleChangeImpact,
  normalizeFormulaCapabilities,
  businessRuleIssueMessage,
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
import type { BusinessRuleEditor, BusinessRuleTrialInput } from './businessRuleEditor';

export class BusinessRulePrecheckError extends OperationUsageError {
  constructor(
    readonly issues: BusinessRuleIssue[],
    language: LanguageContext = platformLanguageContext,
  ) {
    super(
      issues
        .map((issue) => businessRuleIssueMessage(issue, language))
        .join(language.t('platform.rules.issue-separator', '；')),
    );
  }
}

/** One in-memory candidate shared by manual editing and conversation; server baselines remain authoritative. */
export function createBusinessRuleSession(
  http: HttpClient,
  moduleAlias: string,
  ownerValid: () => boolean = () => true,
  confirmationScope: () => boolean = () => true,
  language: LanguageContext = platformLanguageContext,
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
    const committedNeedsReload = ref(false);
    const submissionStatus = ref<'idle' | 'unknown' | 'current-read'>('idle');
    const tenantId = ref('');
    const revision = ref(0);
    const editors = new Set<symbol>();
    const editing = ref(false);
    const dirty = computed(
      () =>
        !!snapshot.value &&
        proposalFingerprintOf(rules.value) !== proposalFingerprintOf(editableProposals(snapshot.value)),
    );
    const ready = computed(
      () =>
        !!snapshot.value &&
        !loading.value &&
        !applying.value &&
        !loadFailed.value &&
        submissionStatus.value !== 'unknown',
    );
    const path = `/platform.module/${encodeURIComponent(moduleAlias)}/business-rules`;
    let loadEpoch = 0;
    watch([rules, snapshot, ui, tenantId, submissionStatus], () => revision.value++, {
      deep: true,
      flush: 'sync',
    });
    function requireValid() {
      if (!valid())
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.screen.businessRuleSession.identity-has-changed-read-the-rules-again',
            '身份已变化，请重新读取规则',
          ),
        );
    }
    function requireReady() {
      requireValid();
      if (submissionStatus.value === 'unknown')
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.screen.businessRuleSession.the-previous-rule-submission-outcome-is-unknown-read-the-current',
            '原规则提交结果未知，请先读取当前配置再重新审阅',
          ),
        );
      if (!ready.value)
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.screen.businessRuleSession.wait-for-rule-loading-or-application-to-finish',
            '请等待规则加载或应用完成',
          ),
        );
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
      committedNeedsReload.value = false;
      submissionStatus.value = 'idle';
    }
    // Publish the loaded baseline through the caller's guarded commit when selecting from the assistant.
    async function load(force = false, commit: (accept: () => void) => void = (accept) => accept()) {
      requireValid();
      if (applying.value)
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.screen.businessRuleSession.rules-are-being-applied',
            '规则正在应用',
          ),
        );
      if (submissionStatus.value === 'unknown') {
        commit(() => {});
        return;
      }
      if (snapshot.value && !loadFailed.value && !force) {
        commit(() => {});
        return;
      }
      if (dirty.value)
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.screen.businessRuleSession.apply-or-discard-the-current-unsaved-changes-first',
            '请先应用或放弃当前未保存更改',
          ),
        );
      const epoch = ++loadEpoch;
      loading.value = true;
      loadFailed.value = false;
      try {
        const loaded = await http.request<BusinessRuleSnapshot>({ path });
        if (epoch !== loadEpoch || !valid()) return;
        if (loaded.moduleAlias !== moduleAlias)
          throw new Error(
            language.t(
              'platform.screen.businessRuleSession.the-rule-module-does-not-match-the-request',
              '规则模块与请求不一致',
            ),
          );
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
    // Reading current configuration establishes a new baseline, never a receipt for the lost request.
    async function readCurrent(
      signal?: AbortSignal,
      commit: (accept: () => void) => void = (accept) => accept(),
    ) {
      requireValid();
      if (submissionStatus.value !== 'unknown' || applying.value || loading.value)
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.screen.businessRuleSession.there-is-no-rule-submission-to-verify',
            '当前没有需要核实的规则提交',
          ),
        );
      const epoch = ++loadEpoch;
      const candidate = rules.value.map(toProposal);
      loading.value = true;
      try {
        const loaded = await http.request<BusinessRuleSnapshot>({ path, signal });
        if (loaded.moduleAlias !== moduleAlias)
          throw new Error(
            language.t(
              'platform.screen.businessRuleSession.the-rule-module-does-not-match-the-request',
              '规则模块与请求不一致',
            ),
          );
        if (signal?.aborted || epoch !== loadEpoch || !valid()) return;
        const result = await merge(loaded);
        if (signal?.aborted || epoch !== loadEpoch || !valid()) return;
        commit(() => {
          accept(result);
          rules.value = candidate;
          loadFailed.value = false;
          submissionStatus.value = 'current-read';
        });
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
      submissionStatus.value = 'idle';
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
    async function apply(
      stillCurrent: () => boolean = () => true,
      reviewed?: { revision: number; checked: BusinessRulePreview },
    ) {
      requireReady();
      if (!dirty.value) return;
      if (editing.value)
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.screen.businessRuleSession.finish-editing-the-current-rule-first',
            '请先完成当前规则编辑',
          ),
        );
      const captured = capture();
      const current = () => captured() && stillCurrent();
      const proposed = rules.value.map(toProposal);
      const baselineFingerprint = snapshot.value!.baselineFingerprint;
      const uiBaselineFingerprint = ui.value?.baselineFingerprint;
      applying.value = true;
      try {
        if (reviewed && reviewed.revision !== revision.value)
          throw new OperationRejectedError(
            language.t(
              'platform.screen.businessRuleSession.the-reviewed-candidate-has-changed-and-was-not-submitted-check',
              '已审阅候选已变化，尚未提交；请重新检查',
            ),
          );
        const checked =
          reviewed?.checked ??
          (await http
            .request<BusinessRulePreview>({
              method: 'POST',
              path: path + '/preview',
              body: { rules: proposed.filter((rule) => rule.kind !== 'UI_CONTROL') },
            })
            .catch(() => {
              throw new OperationRejectedError(
                language.t(
                  'platform.screen.businessRuleSession.rule-prechecks-failed-and-nothing-was-submitted-check-the-rules',
                  '规则预检失败，尚未提交；请重新检查后再应用',
                ),
              );
            }));
        if (!current())
          throw new OperationRejectedError(
            language.t(
              'platform.screen.businessRuleSession.the-rule-candidate-has-changed-and-was-not-submitted-check',
              '规则候选已变化，尚未提交；请重新检查',
            ),
          );
        if (checked.errors.length) throw new BusinessRulePrecheckError(checked.errors, language);
        if (!checked.proposalFingerprint)
          throw new OperationRejectedError(
            language.t(
              'platform.screen.businessRuleSession.the-check-result-is-invalid-and-nothing-was-submitted-reload',
              '检查结果无效，尚未提交；请重新加载后再应用',
            ),
          );
        const result = await http
          .request<BusinessRuleApplyResult>({
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
          })
          .catch((cause: unknown) => {
            if (cause instanceof AppError && [400, 401, 403, 404, 409, 422].includes(cause.status ?? 0))
              throw new OperationRejectedError(cause.message);
            if (valid()) submissionStatus.value = 'unknown';
            throw cause;
          });
        // The apply response proves commitment. A subsequent read or context change cannot
        // turn that fact into an unknown write or leave the old baseline available for retry.
        try {
          if (!current())
            throw new Error(
              language.t(
                'platform.screen.businessRuleSession.the-context-changed-during-submission',
                '提交期间上下文已变化',
              ),
            );
          const merged = await merge(result.snapshot);
          if (!current())
            throw new Error(
              language.t(
                'platform.screen.businessRuleSession.the-context-changed-during-synchronization',
                '同步期间上下文已变化',
              ),
            );
          accept(merged);
          return { synchronized: true };
        } catch {
          if (valid()) {
            committedNeedsReload.value = true;
            loadFailed.value = true;
            snapshot.value = undefined;
            ui.value = undefined;
            rules.value = [];
          }
          return { synchronized: false };
        }
      } finally {
        applying.value = false;
      }
    }
    const adapter: BusinessRuleEditor = {
      language,
      summary: () => ({
        moduleAlias,
        editable: valid() && ready.value && !editing.value,
        factsAvailable: valid() && ready.value,
        submissionStatus: submissionStatus.value,
        ...(valid() && ready.value ? { hasUnappliedChanges: dirty.value } : {}),
      }),
      catalog: (section) =>
        section === 'fields'
          ? (snapshot.value?.editableFields ?? [])
          : section === 'childFields'
            ? (snapshot.value?.childFields ?? [])
            : section === 'aggregateFields'
              ? (snapshot.value?.aggregateFields ?? [])
              : section === 'functions'
                ? normalizeFormulaCapabilities(snapshot.value?.functions, language)
                : section === 'forms'
                  ? (ui.value?.forms ?? [])
                  : [
                      ...rules.value.map((rule) => ({
                        ...rule,
                        persistence: snapshot.value?.rules.some(
                          (saved) =>
                            saved.code === rule.code &&
                            JSON.stringify(toProposal(saved)) === JSON.stringify(toProposal(rule)),
                        )
                          ? 'saved'
                          : 'draft',
                      })),
                      ...(snapshot.value
                        ? readonlyRules(snapshot.value).map((rule) => ({ ...rule, persistence: 'saved' }))
                        : []),
                    ],
      revise(rule) {
        requireReady();
        if (editing.value)
          throw new OperationUsageError(
            codeOperationErrorMessage(
              language,
              'platform.screen.businessRuleSession.finish-editing-the-rule-on-the-page-first',
              '请先完成页面中的规则编辑',
            ),
          );
        if (snapshot.value!.rules.some((item) => item.code === rule.code && !item.editable))
          throw new OperationUsageError(
            codeOperationErrorMessage(
              language,
              'platform.screen.businessRuleSession.read-only-rules-cannot-be-overwritten',
              '不能覆盖只读规则',
            ),
          );
        if (
          rule.kind === 'CALCULATION' &&
          ![...snapshot.value!.editableFields, ...(snapshot.value!.childFields ?? [])].some(
            (field) => field.fieldName === rule.targetField,
          )
        )
          throw new OperationUsageError(
            codeOperationErrorMessage(
              language,
              'platform.screen.businessRuleSession.calculation-targets-must-come-from-the-writable-main-or-direct',
              '计算目标必须来自主表或直接子表的可写字段目录',
            ),
          );
        if (rule.kind === 'UI_CONTROL') {
          const form = ui.value?.forms.find((form) => form.key === rule.formKey);
          if (
            !form ||
            !rule.targets?.length ||
            rule.targets.some((target) => !form.elements.some((element) => element.key === target.elementKey))
          )
            throw new OperationUsageError(
              codeOperationErrorMessage(
                language,
                'platform.screen.businessRuleSession.ui-targets-must-come-from-the-form-directory',
                '界面目标必须来自表单目录',
              ),
            );
        }
        const index = rules.value.findIndex((item) => item.code === rule.code);
        const next = rules.value.slice();
        if (index < 0) next.push(rule);
        else next[index] = rule;
        replace(next);
      },
      preview,
      trial,
      readCurrent,
      async prepareConfirmation(signal): Promise<OperationProposal> {
        requireReady();
        if (!dirty.value)
          throw new OperationUsageError(
            codeOperationErrorMessage(
              language,
              'platform.screen.businessRuleSession.there-are-no-rule-changes-to-apply',
              '当前没有待应用的规则更改',
            ),
          );
        if (editing.value)
          throw new OperationUsageError(
            codeOperationErrorMessage(
              language,
              'platform.screen.businessRuleSession.finish-editing-the-rule-on-the-page-first',
              '请先完成页面中的规则编辑',
            ),
          );
        const captured = capture();
        const current = () => captured() && confirmationScope();
        const checked = await preview(signal);
        if (!current())
          throw new OperationUsageError(
            codeOperationErrorMessage(
              language,
              'platform.screen.businessRuleSession.the-rule-candidate-has-changed-check-it-again',
              '规则候选已变化，请重新检查',
            ),
          );
        if (checked.errors.length) throw new BusinessRulePrecheckError(checked.errors, language);
        if (!checked.proposalFingerprint)
          throw new OperationUsageError(
            codeOperationErrorMessage(
              language,
              'platform.screen.businessRuleSession.the-check-result-is-invalid-run-the-prechecks-again',
              '检查结果无效，请重新预检',
            ),
          );
        const reviewed = { revision: revision.value, checked: structuredClone(checked) };
        const impact = businessRuleChangeImpact(snapshot.value!, rules.value);
        const describe = (rule: BusinessRuleProposal) => {
          const fields = [
            ...snapshot.value!.editableFields,
            ...(snapshot.value!.aggregateFields ?? []),
            ...(snapshot.value!.childFields ?? []),
          ];
          const target = fields.find((field) => field.fieldName === rule.targetField);
          const details = [
            language.t(
              'platform.screen.businessRuleSession.value-valuevalue-value-after-applying',
              '{value1} {value2}{value3}（应用后{value4}）',
              {
                value1: {
                  CALCULATION: language.t(
                    'platform.screen.BusinessRuleGovernanceSurface.field-calculation',
                    '字段计算',
                  ),
                  VALIDATION: language.t(
                    'platform.screen.BusinessRuleGovernanceSurface.save-validation',
                    '保存校验',
                  ),
                  UI_CONTROL: language.t(
                    'platform.screen.BusinessRuleGovernanceSurface.ui-control',
                    '界面控制',
                  ),
                }[rule.kind],
                value2: rule.kind === 'CALCULATION' ? (target?.title || rule.targetField) + ' = ' : '',
                value3: presentableFormulaExpression(rule.expression, fields),
                value4: rule.enabled
                  ? language.t('platform.screen.WorkflowWorkbenchView.enabled', '启用')
                  : language.t('platform.screen.WorkflowWorkbenchView.disabled', '停用'),
              },
            ),
          ];
          if (rule.messageTemplate)
            details.push(
              language.t('platform.screen.businessRuleSession.failure-message', '失败提示：') +
                rule.messageTemplate,
            );
          if (rule.kind === 'UI_CONTROL') {
            const form = ui.value?.forms.find((form) => form.key === rule.formKey);
            details.push(
              language.t('platform.screen.businessRuleSession.form', '表单：') +
                (form?.title || rule.formKey),
            );
            for (const target of rule.targets ?? [])
              details.push(
                `${form?.elements.find((item) => item.key === target.elementKey)?.label || target.elementKey}：${target.hide ? language.t('platform.screen.PageCompositionTree.hide', '隐藏') : language.t('platform.screen.PageCompositionTree.show', '显示')}、${target.readOnly ? language.t('platform.screen.metadataSourceTree.read-only', '只读') : language.t('platform.screen.businessRuleSession.editable', '可编辑')}`,
              );
          }
          return details.join('；');
        };
        return {
          presentation: {
            title: language.t(
              'platform.screen.businessRuleSession.confirm-business-rule-changes',
              '确认应用业务规则',
            ),
            lines: [
              moduleAlias,
              ...impact.added.map(
                (rule) => language.t('platform.screen.businessRuleSession.added', '新增：') + describe(rule),
              ),
              ...impact.modified.map(
                (rule) =>
                  language.t('platform.screen.businessRuleSession.modified', '修改：') + describe(rule),
              ),
              ...impact.deleted.map(
                (rule) =>
                  language.t('platform.screen.businessRuleSession.deleted', '删除：') + describe(rule),
              ),
              language.t(
                'platform.screen.businessRuleSession.apply-all-current-changes-including-manual-edits-on-the-page',
                '应用当前整组更改，包含页面已有人工编辑；影响后续业务操作，不回算历史记录。草稿仅保留在当前工作区，刷新后不会恢复。',
              ),
            ],
          },
          confirmLabel: language.t(
            'platform.screen.businessRuleSession.confirm-rule-changes',
            '确认应用规则',
          ),
          expiresAt: Date.now() + 10 * 60_000,
          isCurrent: () => current() && !editing.value && !applying.value,
          async execute() {
            if (!current() || editing.value)
              throw new OperationRejectedError(
                language.t(
                  'platform.screen.businessRuleSession.the-rule-candidate-has-changed-and-was-not-submitted',
                  '规则候选已变化，尚未提交',
                ),
              );
            try {
              const outcome = await apply(current, reviewed);
              return {
                title: language.t(
                  'platform.screen.businessRuleSession.business-rules-applied',
                  '业务规则已应用',
                ),
                lines: [
                  moduleAlias,
                  ...(outcome?.synchronized === false
                    ? [
                        language.t(
                          'platform.screen.businessRuleSession.rules-were-submitted-but-the-view-has-not-synchronized-reload',
                          '规则已提交，但界面尚未同步；请重新加载后继续编辑，不要重复提交。',
                        ),
                      ]
                    : []),
                ],
              };
            } catch (error) {
              if (error instanceof BusinessRulePrecheckError) throw new OperationRejectedError(error.message);
              throw error;
            }
          },
          async lookup() {
            if (submissionStatus.value === 'unknown') await readCurrent();
            return undefined;
          },
        };
      },
    };
    return {
      language,
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
      committedNeedsReload,
      submissionStatus,
      applying,
      tenantId,
      revision,
      dirty,
      editing,
      adapter,
      load,
      replace,
      discard,
      apply: (stillCurrent?: () => boolean) => apply(stillCurrent),
      readCurrent,
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
