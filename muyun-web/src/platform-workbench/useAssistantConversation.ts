import {
  codeControlText,
  joinControlText,
  resolveControlText,
  archiveControlText,
  restoreControlText,
  modelControlOriginal,
  type ControlText,
} from './assistantControlText';
import { useLanguageContext, type LanguageContext } from '@muyun/web-core';
import { useAssistantConversationArchive } from './useAssistantConversationArchive';
import type { AssistantConversationClient } from '@muyun/web-core';
import type { ConfigurationCollaboration } from './configurationCollaboration';
import type { ConstructionPlanSession } from './constructionPlanSession';
import { markRaw, computed, onBeforeUnmount, ref, watch } from 'vue';
import type {
  AssistantConversationMessage,
  AssistantSelectionInteraction,
  AssistantSelectionOption,
  AssistantSelectionResponse,
  AssistantResultPresentation,
} from '@muyun/web-contracts';
import {
  AppError,
  restoreAssistantOperationReceipt,
  parseOperationReceiptReference,
  AssistantConversationInterruptedError,
  runAssistantConversation,
  createAssistantCapabilitySelection,
  createAssistantDisplayObservations,
  boundedAssistantConversationHistory,
  sameAssistantInvocationToken,
  StaleAssistantInvocationError,
  type AssistantSurfaceRegistry,
  type AssistantOperationConfirmation,
  type AssistantConfirmationState,
  type AssistantInvocationToken,
} from '@muyun/web-core';

interface ConversationItem {
  id: number;
  role: 'user' | 'assistant' | 'status';
  text: string;
  controlText?: ControlText;
  archivedControlText?: import('@muyun/web-core').AssistantControlText;
  selection?: ConversationSelection;
  confirmation?: AssistantOperationConfirmation;
  confirmationState?: AssistantConfirmationState;
  confirmationScope?: string;
  confirmationUserGoal?: string;
  diagnostic?: string;
  details?: AssistantResultPresentation['details'];
}

interface ConversationSelection {
  value: AssistantSelectionInteraction;
  token?: AssistantInvocationToken;
  state: 'open' | 'submitting' | 'answered' | 'superseded';
  selectedOptionId?: string;
  retryable?: boolean;
}

export function useAssistantConversation(props: {
  open: boolean;
  language?: LanguageContext;
  registry: AssistantSurfaceRegistry;
  conversationClient?: AssistantConversationClient;
  constructionPlan?: ConstructionPlanSession;
  configurationCollaboration?: ConfigurationCollaboration;
}) {
  const language = props.language ?? useLanguageContext();
  const capabilitySelection = createAssistantCapabilitySelection();
  const displayObservations = createAssistantDisplayObservations();
  const emptyCost = () => ({
    requests: 0,
    reported: 0,
    tokens: 0,
    modelMs: 0,
    capabilityMs: 0,
    executions: 0,
    toolRequests: 0,
    knownToolRequests: 0,
    userMessages: 0,
    continuations: 0,
    confirmations: 0,
    checks: 0,
    confirmationMs: 0,
  });
  let observedCost = emptyCost();
  const costText = (cost: ReturnType<typeof emptyCost>) =>
    language.t(
      'platform.screen.useAssistantConversation.value-model-requests-value-reported-tokens-coverage-value-value-model',
      '{value1} 次模型请求 · 已报告 {value2} tokens（总量覆盖 {value3}/{value4}） · 模型等待 {value5} 秒 · 平台执行 {value6} 秒 · {value7} 次工具执行 · 工具请求 {value8} 次（次数覆盖 {value9}/{value10}）',
      {
        value1: cost.requests,
        value2: cost.tokens,
        value3: cost.reported,
        value4: cost.requests,
        value5: (cost.modelMs / 1000).toFixed(2),
        value6: (cost.capabilityMs / 1000).toFixed(2),
        value7: cost.executions,
        value8: cost.toolRequests,
        value9: cost.knownToolRequests,
        value10: cost.requests,
      },
    );
  const sessionCostText = () =>
    language.t(
      'platform.screen.useAssistantConversation.session-observations-value-user-messages-value-continuations-value-confirmations-value',
      '会话观测累计：{value1} · 用户消息 {value2} · 自动续办 {value3} · 确认点击 {value4} · 结果核实 {value5} · 确认与核实等待 {value6} 秒',
      {
        value1: costText(observedCost),
        value2: observedCost.userMessages,
        value3: observedCost.continuations,
        value4: observedCost.confirmations,
        value5: observedCost.checks,
        value6: (observedCost.confirmationMs / 1000).toFixed(2),
      },
    );
  const draft = ref('');
  const interruptedRequest = ref<{ userGoal: string; readOnly: boolean }>();
  const items = ref<ConversationItem[]>([]);
  const completedHistory = ref<AssistantConversationMessage[]>([]);
  const busy = ref(false);
  const operationPending = ref(false);
  const activity = ref<'idle' | 'understanding' | 'executing' | 'responding' | 'cancelling'>('idle');
  const activityText = computed(() => {
    switch (activity.value) {
      case 'executing':
        return language.t(
          'platform.screen.useAssistantConversation.executing-page-operations',
          '正在执行页面操作…',
        );
      case 'responding':
        return language.t('platform.screen.useAssistantConversation.preparing-a-reply', '正在组织回复…');
      case 'cancelling':
        return language.t('platform.screen.useAssistantConversation.cancelling', '正在取消…');
      case 'understanding':
      default:
        return language.t(
          'platform.screen.useAssistantConversation.understanding-your-goal',
          '正在理解你的目标…',
        );
    }
  });
  const activeRequiredSelection = computed(() =>
    items.value.find(
      ({ selection }) =>
        selection?.state === 'open' &&
        !selection.retryable &&
        selection.value.inputPolicy === 'selection_required',
    ),
  );
  let nextItemId = 0;
  let controller: AbortController | undefined;
  let conversationEpoch = 0;
  let executionScope: string | undefined;
  let executionGeneration = 0;
  let requestGeneration: number | undefined;
  let identityScope: string | undefined;
  let streamingItemId: number | undefined;
  let pendingStreamText = '';
  const MAX_HISTORY_MESSAGE_LENGTH = 4_000;

  const restored = ref(false);
  const restoredThroughId = ref(0);
  const restoredRequest = ref('');
  const recoveryRequest = computed(() => interruptedRequest.value?.userGoal ?? '');
  const recoveryReadOnly = computed(() => interruptedRequest.value?.readOnly ?? true);
  function adjustRequest() {
    draft.value = recoveryRequest.value || restoredRequest.value;
    interruptedRequest.value = undefined;
  }
  function continueConversation() {
    if (
      draft.value.trim() ||
      busy.value ||
      archive.loading.value ||
      activeRequiredSelection.value ||
      !props.registry.snapshot()
    )
      return;
    const userGoal = recoveryRequest.value || restoredRequest.value;
    const readOnly = interruptedRequest.value?.readOnly ?? true;
    const message = readOnly
      ? language.t(
          'platform.screen.useAssistantConversation.check-the-actual-progress-of-the-current-request-and-suggest',
          '请核实当前需求的实际进度并建议下一步，暂不修改或保存。用户需求：{value1}',
          { value1: userGoal },
        )
      : language.t(
          'platform.screen.useAssistantConversation.check-current-facts-first-then-continue-unfinished-work-in-the',
          '请先核实当前事实，再继续完成用户需求中尚未完成的事项；已有操作结果未知时先查询结果，不重提。新的保存仍须重新确认。用户需求：{value1}',
          { value1: userGoal },
        );
    restored.value = false;
    interruptedRequest.value = undefined;
    supersedePendingSelections();
    void submitMessage(message, conversationHistory(), undefined, undefined, { userGoal }, readOnly);
  }
  const linkedPlanId = ref<string>();
  function clearConversation(resetDesign = false) {
    conversationEpoch++;
    observedCost = emptyCost();
    capabilitySelection.clear();
    displayObservations.clear();
    controller?.abort();
    controller = undefined;
    busy.value = false;
    operationPending.value = false;
    activity.value = 'idle';
    for (const item of items.value) item.confirmation?.cancel();
    items.value = [];
    completedHistory.value = [];
    draft.value = '';
    interruptedRequest.value = undefined;
    streamingItemId = undefined;
    pendingStreamText = '';
    restored.value = false;
    restoredThroughId.value = 0;
    restoredRequest.value = '';
    linkedPlanId.value = undefined;
    if (resetDesign) props.constructionPlan?.resetConversation();
    props.configurationCollaboration?.restore();
  }
  const archive = useAssistantConversationArchive(
    props.conversationClient,
    async () => {
      const snapshot = {
        title:
          items.value.find((item) => item.role === 'user')?.text.slice(0, 120) ||
          language.t('platform.screen.WorkbenchAssistantPanel.new-conversation', '新对话'),
        messages: items.value.map((item) => archiveMessage(item, language)),
        history: completedHistory.value,
        configurationTask: props.configurationCollaboration?.task.value,
        executionScopeKey: executionScope,
        pendingRequest:
          busy.value && requestGeneration === executionGeneration
            ? items.value.filter((item) => item.role === 'user').at(-1)?.text
            : undefined,
        planId:
          linkedPlanId.value ??
          props.constructionPlan?.current().saved?.planId ??
          (props.constructionPlan?.recovery.value ? props.constructionPlan.current().planId : undefined),
      };
      return { ...snapshot, messages: await Promise.all(snapshot.messages) };
    },
    async (content) => {
      clearConversation(true);
      const restoredEpoch = conversationEpoch;
      const restoredItems = await Promise.all(
        content.messages.map(async (message) => {
          const item: ConversationItem = { role: message.role, text: message.text, id: ++nextItemId };
          if (message.controlText && message.role !== 'user' && !message.operationReceipt) {
            item.archivedControlText = message.controlText;
            item.controlText = await restoreControlText(message.controlText);
          }
          if (message.operationReceipt && props.conversationClient?.lookupOperation) {
            try {
              const reference = parseOperationReceiptReference(message.operationReceipt.reference);
              const scope = message.operationReceipt.executionScopeKey;
              item.confirmationScope = scope;
              item.confirmation = markRaw(
                restoreAssistantOperationReceipt(
                  reference,
                  () => props.conversationClient!.lookupOperation!(reference),
                  () =>
                    restoredEpoch === conversationEpoch &&
                    props.registry.snapshot()?.token.executionScopeKey === scope,
                  language,
                ),
              );
              item.confirmationState = 'unknown';
            } catch {
              /* Historical invalid references never become requests. */
            }
          }
          return item;
        }),
      );
      if (restoredEpoch !== conversationEpoch) return;
      items.value = restoredItems;
      const sameScope = content.executionScopeKey === executionScope;
      completedHistory.value = sameScope
        ? boundedHistory([
            ...content.history,
            ...(content.pendingRequest ? [{ role: 'user' as const, text: content.pendingRequest }] : []),
          ])
        : [];
      restoredThroughId.value = items.value.at(-1)?.id ?? 0;
      restoredRequest.value = sameScope
        ? (content.pendingRequest ??
          content.messages.filter((message) => message.role === 'user').at(-1)?.text ??
          '')
        : '';
      restored.value = true;
      linkedPlanId.value = content.planId;
      props.constructionPlan?.resetConversation(content.planId);
      props.configurationCollaboration?.restore(content.configurationTask);
      const epoch = conversationEpoch;
      if (content.planId && props.constructionPlan) {
        try {
          await props.constructionPlan.restore(content.planId, () => epoch === conversationEpoch);
        } catch (cause) {
          if (epoch === conversationEpoch)
            append(
              'status',
              cause instanceof AppError && (cause.status === 403 || cause.status === 404)
                ? codeControlText(
                    'platform.screen.useAssistantConversation.the-conversation-was-restored-but-the-linked-plan-is-missing',
                    '聊天记录已恢复，但关联方案不存在或当前身份无权访问，暂不能继续修改该方案。可以核对登录身份与业务范围；如需重新整理，请新建对话。已有业务仍可按当前配置核实。',
                  )
                : codeControlText(
                    'platform.screen.useAssistantConversation.historical-construction-records-are-temporarily-unavailable-you-can-still-check',
                    '历史建设记录暂时无法读取，仍可直接核实和改进当前业务。未完成设计请稍后重试读取。',
                  ),
            );
        }
      }
    },
    () => clearConversation(true),
    () =>
      props.constructionPlan?.recovery.value
        ? language.t(
            'platform.screen.useAssistantConversation.the-construction-confirmation-result-is-unknown-check-it-before-switching',
            '建设确认结果尚未确定，请先查询结果再切换对话。',
          )
        : undefined,
    language,
  );

  function append(
    role: ConversationItem['role'],
    text: string | ControlText,
    details?: AssistantResultPresentation['details'],
    diagnostic?: string,
  ) {
    const controlText = typeof text === 'string' ? undefined : structuredClone(text);
    if (role === 'user' && controlText)
      throw new Error('User messages cannot declare platform control sources');
    const normalized = (typeof text === 'string' ? text : resolveControlText(text, language)).trim();
    if (!normalized) return;
    items.value.push({
      id: ++nextItemId,
      role,
      text: normalized,
      ...(controlText ? { controlText } : {}),
      details,
      diagnostic,
    });
  }

  function appendAssistant(text: string | undefined, selection?: AssistantSelectionInteraction) {
    const normalized = text?.trim() ?? '';
    if (!normalized && !selection) return;
    items.value.push({
      id: ++nextItemId,
      role: 'assistant',
      text: normalized,
      ...(selection ? { selection: newSelection(selection) } : {}),
    });
  }

  function submit() {
    const message = draft.value.trim();
    if (!message || archive.loading.value || busy.value || !props.registry.snapshot()) return;
    restored.value = false;
    const history = conversationHistory();
    draft.value = '';
    supersedePendingSelections();
    interruptedRequest.value = undefined;
    void submitMessage(message, history);
  }

  async function submitMessage(
    message: string,
    history: AssistantConversationMessage[],
    selectionResponse?: AssistantSelectionResponse,
    sourceSelection?: ConversationSelection,
    continuation?: { userGoal: string; isCurrent?: () => boolean },
    readOnly = false,
  ) {
    const beforeSync = conversationEpoch;
    expireStaleSelections();
    if (beforeSync !== conversationEpoch) return;
    if (!message || busy.value || archive.loading.value || !props.registry.snapshot()) return;
    if (continuation?.isCurrent?.() === false) return;
    const epoch = conversationEpoch;
    let requestScope = executionGeneration;
    requestGeneration = requestScope;
    const userGoal = continuation?.userGoal ?? message;
    const rememberInterruption = () => {
      interruptedRequest.value = { userGoal, readOnly };
    };
    const commitTurn = (entries: AssistantConversationMessage[]) =>
      commitConversation(continuation ? undefined : userGoal, entries);
    if (continuation)
      append(
        'status',
        readOnly
          ? codeControlText(
              'platform.screen.useAssistantConversation.checking-current-progress-without-modifying-or-saving',
              '正在核实当前进度，暂不修改或保存。',
            )
          : codeControlText(
              'platform.screen.useAssistantConversation.checking-current-status-and-preparing-the-next-step-you-can',
              '正在核实当前状态并准备下一步，可随时停止。',
            ),
      );
    else append('user', message);
    busy.value = true;
    activity.value = 'understanding';
    controller = new AbortController();
    const turnHistory: AssistantConversationMessage[] = [];
    const diagnostics: string[] = [];
    const started = performance.now();
    const turnCost = emptyCost();
    if (continuation) observedCost.continuations++;
    else observedCost.userMessages++;
    const diagnosticText = () =>
      [
        ...diagnostics,
        language.t(
          'platform.screen.useAssistantConversation.turn-observations-value-processing-time-value-s',
          '本轮观测：{value1} · 处理耗时 {value2} 秒',
          { value1: costText(turnCost), value2: ((performance.now() - started) / 1000).toFixed(2) },
        ),
        sessionCostText(),
        language.t(
          'platform.screen.useAssistantConversation.only-calls-observed-since-opening-this-conversation-are-counted-missing',
          '仅统计本次打开会话后观测到的调用；用量缺失不记零，处理耗时仅覆盖本轮处理；确认与核实等待另计，不含用户阅读与审阅时间。',
        ),
      ].join('\n');
    try {
      const saved = await archive.save();
      if (epoch !== conversationEpoch || requestScope !== executionGeneration) return;
      if (continuation?.isCurrent?.() === false) return;
      if (!saved) {
        rememberInterruption();
        reopenSelection(sourceSelection);
        return;
      }
      const result = await runAssistantConversation(props.registry, message, {
        signal: controller.signal,
        capabilitySelection,
        displayObservations,
        executionPolicy: { readOnly },
        history,
        ...(selectionResponse ? { selectionResponse } : {}),
        onDiagnostic(event) {
          if (epoch !== conversationEpoch) return;
          if (event.type === 'model.request.completed') {
            for (const cost of [turnCost, observedCost]) {
              cost.requests++;
              cost.modelMs += event.durationMs;
              if (event.usage?.totalTokens !== undefined) {
                cost.reported++;
                cost.tokens += event.usage.totalTokens;
              }
              if (event.toolCallCount !== undefined) {
                cost.knownToolRequests++;
                cost.toolRequests += event.toolCallCount;
              }
            }
            diagnostics.push(
              language.t(
                'platform.screen.useAssistantConversation.value-value-value-value-value-s',
                '{value1} {value2} · {value3} · {value4} · {value5} 秒',
                {
                  value1:
                    event.phase === 'summary'
                      ? language.t(
                          'platform.screen.useAssistantConversation.result-summary-request',
                          '结果整理请求',
                        )
                      : language.t('platform.screen.useAssistantConversation.model-request', '模型请求'),
                  value2: turnCost.requests,
                  value3:
                    event.outcome === 'received'
                      ? language.t('platform.screen.useAssistantConversation.responded', '已响应')
                      : language.t('platform.screen.useAssistantConversation.not-accepted', '未接受'),
                  value4:
                    event.usage?.totalTokens === undefined
                      ? language.t('platform.screen.useAssistantConversation.usage-unknown', '用量未知')
                      : `${event.usage.totalTokens} tokens`,
                  value5: (event.durationMs / 1000).toFixed(2),
                },
              ),
            );
          } else if (event.type === 'capability.elapsed') {
            for (const cost of [turnCost, observedCost]) {
              cost.executions++;
              cost.capabilityMs += event.durationMs;
            }
          } else if (event.type === 'decision.restarted') {
            diagnostics.push(
              language.t(
                'platform.screen.useAssistantConversation.value-replanned-value',
                '{value1}: 重新规划 · {value2}',
                { value1: event.stepIndex + 1, value2: event.reason },
              ),
            );
          } else if (event.type === 'decision.completed')
            diagnostics.push(
              language.t(
                'platform.screen.useAssistantConversation.value-decision-accepted-value-tool-requests',
                '{value1}: 决策已接受 · {value2} 次工具请求',
                { value1: event.stepIndex + 1, value2: event.toolCallCount },
              ),
            );
          else if (event.type === 'capability.completed')
            diagnostics.push(`${event.stepIndex + 1}: ${event.capabilityCode} · ${event.outcome}`);
          else if (event.type === 'decision.failed') {
            const stages = {
              'context-changed': language.t(
                'platform.screen.useAssistantConversation.page-context-changed',
                '页面上下文已变化',
              ),
              cancelled: language.t('platform.screen.useAssistantConversation.turn-cancelled', '本轮已取消'),
              'surface-unavailable': language.t(
                'platform.screen.useAssistantConversation.page-operations-are-not-ready',
                '页面操作入口尚未就绪',
              ),
              'surface-settlement-failed': language.t(
                'platform.screen.useAssistantConversation.page-state-preparation-failed',
                '页面状态准备失败',
              ),
              'model-request-failed': language.t(
                'platform.screen.useAssistantConversation.model-request-incomplete',
                '模型请求未完成',
              ),
            };
            diagnostics.push(`${event.stepIndex + 1}: ${stages[event.reason]}`);
          } else if (event.type === 'summary.completed')
            diagnostics.push(
              language.t(
                'platform.screen.useAssistantConversation.result-summary-value',
                '结果整理：{value1}',
                {
                  value1: event.succeeded
                    ? language.t('platform.screen.ModuleDataExchangeSurface.done', '完成')
                    : (event.reason ??
                      language.t('platform.screen.RecordQueryListPanel.incomplete', '未完成')),
                },
              ),
            );
        },
        onExecutionScopeChange() {
          if (epoch !== conversationEpoch) return;
          expireStaleSelections();
          requestScope = executionGeneration;
          requestGeneration = requestScope;
          turnHistory.length = 0;
          streamingItemId = undefined;
          pendingStreamText = '';
        },
        onActivity(phase) {
          if (epoch !== conversationEpoch || requestScope !== executionGeneration) return;
          activity.value = phase;
        },
        onTextDelta(text) {
          if (epoch !== conversationEpoch || requestScope !== executionGeneration) return;
          if (!text) return;
          if (streamingItemId === undefined) {
            pendingStreamText += text;
            if (!pendingStreamText.trim()) return;
          }
          if (streamingItemId === undefined) {
            streamingItemId = ++nextItemId;
            items.value.push({ id: streamingItemId, role: 'assistant', text: pendingStreamText });
            pendingStreamText = '';
            return;
          }
          const item = items.value.find(({ id }) => id === streamingItemId);
          if (item) item.text += text;
        },
        onTextDiscard() {
          if (epoch !== conversationEpoch || requestScope !== executionGeneration) return;
          if (streamingItemId !== undefined) {
            items.value = items.value.filter(({ id }) => id !== streamingItemId);
          }
          streamingItemId = undefined;
          pendingStreamText = '';
        },
        onStep(step) {
          if (epoch !== conversationEpoch || requestScope !== executionGeneration) return;
          refreshConfirmations();
          if (step.output.text || step.output.selection) {
            turnHistory.push({
              role: 'assistant',
              text: assistantHistoryText(step.output.text, step.output.selection),
            });
            if (streamingItemId === undefined) appendAssistant(step.output.text, step.output.selection);
            else {
              const item = items.value.find(({ id }) => id === streamingItemId);
              if (item) {
                item.text = step.output.text?.trim() ?? '';
                if (step.output.selection) item.selection = newSelection(step.output.selection);
              }
            }
          }
          streamingItemId = undefined;
          pendingStreamText = '';
          for (const confirmation of step.confirmations ?? []) {
            items.value.push({
              id: ++nextItemId,
              role: 'assistant',
              text: '',
              confirmation: markRaw(confirmation),
              confirmationState: confirmation.state,
              confirmationScope: props.registry.snapshot()?.token.executionScopeKey,
              confirmationUserGoal: userGoal,
            });
          }
          if (step.results.length > 0) {
            for (const result of step.results) {
              if (result.error) {
                append(
                  'status',
                  result.execution === 'unknown'
                    ? codeControlText(
                        'platform.screen.useAssistantConversation.this-step-s-result-is-unknown-check-it-before-resubmitting',
                        '这一步的结果尚未确定，请先核实，不要重复提交。已完成的其他步骤保留。',
                      )
                    : codeControlText(
                        'platform.screen.useAssistantConversation.this-step-did-not-complete-revise-it-using-the-validation',
                        '这一步未完成，可根据校验结果继续调整。已完成的其他步骤保留。',
                      ),
                  undefined,
                  result.error.code !== 'CAPABILITY_FAILED' ? result.error.message : undefined,
                );
              }
              if (!result.presentation) continue;
              const fact = [result.presentation.title, ...result.presentation.lines].join('\n');
              append('status', fact, result.presentation.details);
              turnHistory.push({ role: 'status', text: fact });
            }
          }
        },
      });
      if (epoch !== conversationEpoch || requestScope !== executionGeneration) return;
      if (result.termination === 'step-limit')
        append(
          'status',
          result.steps.some((step) => step.appliedEffectCount > 0)
            ? codeControlText(
                'platform.screen.useAssistantConversation.this-turn-is-paused-at-the-automatic-processing-limit-completed',
                '本轮已暂停：本次自动处理已达上限。已完成的操作保留，但不代表业务已保存；点击“继续处理”即可接着核对剩余事项，无需重复刚才的要求。',
              )
            : codeControlText(
                'platform.screen.useAssistantConversation.this-turn-is-paused-at-the-automatic-processing-limit-select',
                '本轮已暂停：本次自动处理已达上限。点击“继续处理”可接着核对剩余事项，无需重复刚才的要求；读取信息不代表修改或保存。',
              ),
          undefined,
          diagnosticText(),
        );
      else if (result.termination === 'repeated-call') {
        append(
          'status',
          codeControlText(
            'platform.screen.useAssistantConversation.repeated-operations-stopped-continue-checking-known-facts-or-clarify-your',
            '已停止重复操作，可以根据已查明的内容继续核实或补充需求。',
          ),
          undefined,
          diagnosticText(),
        );
      } else if (
        result.steps.every(
          (step) => !step.output.text && !step.output.selection && !step.confirmations?.length,
        )
      ) {
        const applied = result.steps.reduce((total, step) => total + step.appliedEffectCount, 0);
        const succeeded = result.steps.some((step) => step.results.some((candidate) => !candidate.error));
        if (applied > 0) {
          append(
            'assistant',
            codeControlText(
              'platform.screen.useAssistantConversation.page-operations-completed-check-the-current-page',
              '页面操作已完成，请检查当前页面。',
            ),
          );
          turnHistory.push({
            role: 'status',
            text: language.t(
              'platform.screen.useAssistantConversation.page-operations-completed-check-the-current-page',
              '页面操作已完成，请检查当前页面。',
            ),
          });
        } else if (succeeded) {
          append(
            'assistant',
            codeControlText(
              'platform.screen.useAssistantConversation.information-was-read-but-no-explanation-was-produced-please-ask',
              '信息已读取，但未生成可展示的说明，请重新提问。',
            ),
          );
          turnHistory.push({
            role: 'status',
            text: language.t(
              'platform.screen.useAssistantConversation.information-was-read-but-no-explanation-was-produced-please-ask',
              '信息已读取，但未生成可展示的说明，请重新提问。',
            ),
          });
        }
      }
      if (result.termination === 'step-limit' || result.termination === 'repeated-call')
        rememberInterruption();
      const lastItem = items.value.at(-1);
      if (lastItem && lastItem.role !== 'user') lastItem.diagnostic = diagnosticText();
      commitTurn(turnHistory);
      if (sourceSelection) sourceSelection.state = 'answered';
    } catch (error) {
      if (epoch !== conversationEpoch || requestScope !== executionGeneration) return;
      rememberInterruption();
      if (isAbortError(error)) {
        interruptedRequest.value = undefined;
        commitTurn([
          ...turnHistory,
          {
            role: 'status',
            text: language.t(
              'platform.screen.useAssistantConversation.the-user-stopped-this-turn-the-request-is-retained-only',
              '用户已停止本轮执行。需求仅作为讨论记录保留，不得自动继续执行。',
            ),
          },
        ]);
        reopenSelection(sourceSelection);
        append(
          'status',
          codeControlText(
            'platform.screen.useAssistantConversation.this-operation-stopped',
            '已停止本次操作。',
          ),
        );
      } else if (error instanceof StaleAssistantInvocationError) {
        reopenSelection(sourceSelection);
        commitTurn([]);
        append(
          'status',
          codeControlText(
            'platform.screen.useAssistantConversation.the-page-changed-in-a-way-that-conflicts-with-this',
            '页面发生了与当前任务冲突的变化，本轮已暂停。请确认当前页面后告诉我继续或调整目标。',
          ),
        );
      } else if (error instanceof AssistantConversationInterruptedError) {
        if (error.termination === 'cancelled') interruptedRequest.value = undefined;
        if (sourceSelection) sourceSelection.state = 'answered';
        commitTurn(turnHistory);
        const applied = error.steps.reduce((total, step) => total + step.appliedEffectCount, 0);
        const unknown = error.steps.some((step) =>
          step.results.some((result) => result.execution === 'unknown'),
        );
        const hasPresentedEffects =
          error.steps
            .flatMap((step) => step.results)
            .filter((result) => result.execution === 'effect-applied' && result.presentation).length >=
          applied;
        const reason = () =>
          error.termination === 'cancelled'
            ? codeControlText('platform.screen.useAssistantConversation.turn-cancelled', '本轮已取消')
            : error.termination === 'context-changed'
              ? codeControlText(
                  'platform.screen.useAssistantConversation.page-context-changed',
                  '页面上下文已变化',
                )
              : codeControlText(
                  'platform.screen.useAssistantConversation.subsequent-processing-failed',
                  '后续处理失败',
                );
        append(
          'status',
          unknown
            ? codeControlText(
                'platform.screen.useAssistantConversation.value-some-page-operation-results-are-unknown-check-the-current',
                '{value1}，有页面操作的结果尚不确定。请检查当前页面；不会自动重试。',
                { value1: reason() },
              )
            : joinControlText(
                joinControlText(
                  codeControlText(
                    'platform.screen.useAssistantConversation.value-the-goal-has-not-been-verified-as-complete',
                    '{value1}，目标尚未核实完成。',
                    { value1: reason() },
                  ),
                  '\n',
                  applied > 0
                    ? hasPresentedEffects
                      ? codeControlText(
                          'platform.screen.useAssistantConversation.see-completed-steps-in-the-operation-records-above-opening-a',
                          '已生效步骤见上方平台操作记录；打开页面、准备草稿不代表保存。',
                        )
                      : codeControlText(
                          'platform.screen.useAssistantConversation.some-page-operations-took-effect-but-their-specific-results-were',
                          '本轮已有页面操作生效，但缺少具体结果说明，请核对当前页面；不能据此判断已保存。',
                        )
                    : codeControlText(
                        'platform.screen.useAssistantConversation.no-page-operations-are-confirmed-to-have-taken-effect-in',
                        '本轮没有已确认生效的页面操作。',
                      ),
                ),
                error.termination === 'model-failed'
                  ? joinControlText('\n', assistantFailureText(error.cause))
                  : '',
              ),
          undefined,
          diagnosticText(),
        );
      } else {
        reopenSelection(sourceSelection);
        commitTurn(turnHistory);
        append('status', assistantFailureText(error), undefined, diagnosticText());
      }
    } finally {
      if (epoch === conversationEpoch) {
        refreshConfirmations();
        streamingItemId = undefined;
        pendingStreamText = '';
        controller = undefined;
        busy.value = false;
        activity.value = 'idle';
      }
    }
  }

  function reopenSelection(selection?: ConversationSelection) {
    if (!selection) return;
    selection.state = selectionIsCurrent(selection) ? 'open' : 'superseded';
    selection.selectedOptionId = undefined;
    selection.retryable = true;
    append(
      'status',
      codeControlText(
        'platform.screen.useAssistantConversation.this-selection-could-not-be-processed-retry-it-or-describe',
        '本次选择未能处理完成。可以重试选项，也可以用文字继续说明；已有内容保留。',
      ),
    );
  }

  function newSelection(value: AssistantSelectionInteraction): ConversationSelection {
    return { value, state: 'open', token: props.registry.snapshot()?.token };
  }

  function selectionIsCurrent(selection: ConversationSelection) {
    const current = props.registry.snapshot()?.token;
    return current !== undefined && sameAssistantInvocationToken(current, selection.token);
  }

  function refreshConfirmations() {
    for (const item of items.value) {
      if (item.confirmation) item.confirmationState = item.confirmation.state;
    }
  }

  function expireStaleSelections() {
    const token = props.registry.snapshot()?.token;
    capabilitySelection.forScope(token);
    displayObservations.forScope(token);
    const scope = token?.executionScopeKey;
    const identity = token?.identityScopeKey;
    if (identity !== undefined && identity !== identityScope) {
      const hadIdentity = identityScope !== undefined;
      identityScope = identity;
      executionScope = scope;
      clearConversation(hadIdentity);
      // Personal history belongs to the authenticated identity, not the active page tenant.
      archive.changeScope(JSON.stringify([identity]));
    } else if (scope !== undefined && scope !== executionScope) {
      const hadScope = executionScope !== undefined;
      executionScope = scope;
      executionGeneration++;
      if (hadScope) {
        // Retain the task and transcript, but never feed old business observations or
        // pending requests into a different execution scope. Invocation tokens invalidate
        // outstanding tools and confirmations independently of this conversation.
        completedHistory.value = [];
        interruptedRequest.value = undefined;
        restoredRequest.value = '';
        supersedePendingSelections();
        append(
          'status',
          codeControlText(
            'platform.screen.useAssistantConversation.business-scope-changed-the-conversation-and-goal-are-retained-subsequent',
            '业务范围已变化，对话与目标保留；后续操作将重新核实当前页面的数据。',
          ),
        );
      }
    }
    refreshConfirmations();
    for (const item of items.value) {
      if (item.selection?.state === 'open' && !selectionIsCurrent(item.selection)) {
        item.selection.state = 'superseded';
      }
    }
  }

  function abandonSelection(item: ConversationItem) {
    if (archive.loading.value || busy.value || item.selection?.state !== 'open') return;
    item.selection.state = 'superseded';
    const message =
      language.t('platform.screen.useAssistantConversation.abandon-this-proposal', '放弃本次提议：') +
      item.selection.value.prompt;
    append('user', message);
    commitConversation(message, []);
  }

  function selectOption(item: ConversationItem, option: AssistantSelectionOption) {
    const selection = item.selection;
    if (!selection || selection.state !== 'open' || busy.value || archive.loading.value) return;
    if (!selectionIsCurrent(selection)) {
      selection.state = 'superseded';
      return;
    }
    const history = conversationHistory();
    supersedePendingSelections(selection);
    selection.state = 'submitting';
    selection.retryable = false;
    selection.selectedOptionId = option.id;
    void submitMessage(
      option.label,
      history,
      {
        interactionId: selection.value.interactionId,
        optionId: option.id,
        label: option.label,
      },
      selection,
    );
  }

  function supersedePendingSelections(except?: ConversationSelection) {
    for (const item of items.value) {
      if (
        item.selection &&
        item.selection !== except &&
        (item.selection.state === 'open' || item.selection.state === 'submitting')
      ) {
        item.selection.state = 'superseded';
      }
    }
  }

  function conversationHistory(): AssistantConversationMessage[] {
    const pending = items.value.filter((item) => item.confirmation?.state === 'pending').slice(-3);
    return boundedHistory([
      ...completedHistory.value,
      ...(restoredThroughId.value
        ? [
            {
              role: 'status' as const,
              text: language.t(
                'platform.screen.useAssistantConversation.the-historical-conversation-was-restored-do-not-ask-the-user',
                '历史会话已恢复。已商定的需求无需用户重新描述；聊天历史不存储未保存草稿，也不恢复旧确认授权；当前工作区仍可能保留未保存候选，必须读取后判断，不能直接宣称草稿丢失。先读取当前业务事实，区分历史讨论、已证实结果和待核实事项，再说明最少的后续步骤。历史提议不是新的执行授权，写入需重新准备确认。区分“记录里曾经试填”与“当前草稿未恢复”，不能因后者否认前者。当前查看模式没有暴露编辑能力，不等于平台不支持；未重新发现相应编辑场景能力前，只说明尚待核实，不要求用户绕开助手手工完成。',
              ),
            },
          ]
        : []),
      ...pending.map(
        (item): AssistantConversationMessage => ({
          role: 'status',
          text: language.t(
            'platform.screen.useAssistantConversation.platform-content-awaiting-confirmation-not-executed-the-user-may-ask',
            '平台待确认内容（尚未执行，用户可追问）：{value1}',
            { value1: item.confirmation!.modelSummary },
          ),
        }),
      ),
    ]);
  }

  function commitConversation(message: string | undefined, turnHistory: AssistantConversationMessage[]) {
    completedHistory.value = boundedHistory([
      ...completedHistory.value,
      ...(message ? [{ role: 'user' as const, text: message }] : []),
      ...turnHistory,
    ]);
  }

  function boundedHistory(history: AssistantConversationMessage[]): AssistantConversationMessage[] {
    const selected = boundedAssistantConversationHistory(history);
    if (selected[0]?.role === 'assistant') selected.shift();
    return selected;
  }

  async function confirmOperation(item: ConversationItem, check = false) {
    if (!item.confirmation || busy.value || archive.loading.value) return;
    const epoch = conversationEpoch;
    const operationScope = executionGeneration;
    const confirmationStarted = performance.now();
    let resultItem: ConversationItem | undefined;
    busy.value = true;
    activity.value = 'executing';
    operationPending.value = true;
    try {
      // Persist the read-only identity before sending a write, so refresh during transport can recover it.
      if (!check && item.confirmation.receiptReference) {
        item.confirmationState = 'executing';
        if (
          !(await archive.save()) ||
          epoch !== conversationEpoch ||
          operationScope !== executionGeneration
        ) {
          item.confirmationState = item.confirmation.state;
          return;
        }
      }
      if (check) observedCost.checks++;
      else observedCost.confirmations++;
      const pending = check ? item.confirmation.check() : item.confirmation.confirm();
      item.confirmationState = item.confirmation.state;
      await pending;
      if (epoch !== conversationEpoch) return;
      item.confirmationState = item.confirmation.state;
      const result = item.confirmation.result;
      if (result) {
        append('status', [result.title, ...result.lines].join('\n'), result.details);
        resultItem = items.value.at(-1);
        if (operationScope === executionGeneration)
          commitConversation(undefined, [
            { role: 'status', text: [result.title, ...result.lines].join('\n') },
          ]);
      }
    } finally {
      if (epoch === conversationEpoch) {
        observedCost.confirmationMs += Math.max(0, performance.now() - confirmationStarted);
        if (resultItem)
          resultItem.diagnostic = [
            check
              ? language.t(
                  'platform.screen.useAssistantConversation.stage-result-verification',
                  '阶段：结果核实',
                )
              : language.t(
                  'platform.screen.useAssistantConversation.stage-user-confirmed-execution',
                  '阶段：人工确认执行',
                ),
            sessionCostText(),
            language.t(
              'platform.screen.useAssistantConversation.only-calls-observed-since-opening-this-conversation-are-counted-missing-label',
              '仅统计本次打开会话后观测到的调用；用量缺失不记零，不含用户阅读与审阅时间。',
            ),
          ].join('\n');
        operationPending.value = false;
        busy.value = false;
        activity.value = 'idle';
      }
    }
    if (
      epoch === conversationEpoch &&
      operationScope === executionGeneration &&
      props.open &&
      !draft.value.trim() &&
      !activeRequiredSelection.value
    ) {
      const next = item.confirmation.takeContinuation();
      if (next) {
        // A read-only question while reviewing must not replace the goal which
        // produced this confirmation. Later corrections remain authoritative.
        const request = item.confirmationUserGoal;
        let corrections = '';
        let correctionsTruncated = false;
        for (const entry of items.value) {
          if (entry.role !== 'user' || entry.id <= item.id) continue;
          const addition = `${corrections ? '\n' : ''}${entry.text}`;
          if (corrections.length + addition.length > 2_000) {
            correctionsTruncated = true;
            break;
          }
          corrections += addition;
        }
        // Carry user revisions into later confirmations and interruption recovery,
        // without retaining internal continuation instructions as a user goal.
        const userGoal = `${request ?? ''}${corrections ? language.t('platform.screen.useAssistantConversation.user-additions-and-corrections-after-confirmation-preparation-value', '\n确认准备后的用户补充与纠正：{value1}', { value1: corrections }) : ''}`;
        const confirmationFact = language.t(
          'platform.screen.useAssistantConversation.the-user-subsequently-confirmed-the-now-completed-operation-through-its',
          '用户随后通过确认卡片确认了本次已完成操作；确认前的暂缓不否定本次已完成结果。本次确认只覆盖已完成操作；用户原先授权的其余读取、导航和草稿准备继续有效，无需重复询问是否继续。后续正式保存或应用仍须通过各自的标准确认，不因本次确认扩大授权。',
        );
        const message = request
          ? language.t(
              'platform.screen.useAssistantConversation.value-the-user-s-latest-explicit-request-value-respect-later',
              '{value1}\n用户最近明确提出的要求：{value2}\n以用户后续纠正、暂缓和取消为准；只读追问不替代原目标。\n{value3}\n仅续办该要求；任务清单不是扩大范围的授权。用户暂缓的事项继续保留，不重新提议建设；当前目标完成后说明结果并停止。',
              { value1: next, value2: userGoal, value3: confirmationFact },
            )
          : `${next}\n${confirmationFact}`;
        if (correctionsTruncated || message.length > MAX_HISTORY_MESSAGE_LENGTH) {
          interruptedRequest.value = undefined;
          append(
            'status',
            codeControlText(
              'platform.screen.useAssistantConversation.this-operation-completed-but-subsequent-requirements-exceed-the-safe-retention',
              '本次操作已完成，但后续需求超过安全保留范围，已暂停自动处理。请重新明确剩余目标；已有结果保留，不会重复提交。',
            ),
          );
          return;
        }
        await submitMessage(
          message,
          conversationHistory(),
          undefined,
          undefined,
          { userGoal, isCurrent: item.confirmation.continuationIsCurrent },
          item.confirmation.continuationReadOnly === true,
        );
      }
    }
  }

  function cancelOperation(item: ConversationItem) {
    item.confirmation?.cancel();
    item.confirmationState = item.confirmation?.state;
  }

  function cancel() {
    if (controller) activity.value = 'cancelling';
    controller?.abort();
  }

  watch(
    () => props.open,
    (open) => {
      if (!open) cancel();
    },
  );
  onBeforeUnmount(cancel);

  watch(() => props.registry.snapshot()?.token, expireStaleSelections, { deep: true, flush: 'sync' });
  watch(
    () => props.registry,
    (registry, _previous, onCleanup) => {
      onCleanup(registry.subscribe(expireStaleSelections));
      expireStaleSelections();
    },
    { immediate: true },
  );

  watch(
    () => [
      busy.value,
      JSON.stringify(items.value.map((item) => [item.text, item.selection?.state, item.confirmationState])),
      completedHistory.value,
    ],
    () => {
      if (!busy.value && !archive.loading.value && !archive.saveError.value) void archive.save();
    },
    { deep: true },
  );

  function handleKeydown(event: KeyboardEvent) {
    if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
    event.preventDefault();
    void submit();
  }

  function isAbortError(error: unknown) {
    return error instanceof DOMException && error.name === 'AbortError';
  }

  return {
    archive,
    restored,
    linkedPlanId,
    draft,
    restoredThroughId,
    restoredRequest,
    recoveryRequest,
    recoveryReadOnly,
    adjustRequest,
    continueConversation,
    items,
    itemText: (item: ConversationItem) =>
      item.controlText ? resolveControlText(item.controlText, language) : item.text,
    busy,
    activityText,
    operationPending,
    activeRequiredSelection,
    submit: () => submit(),
    cancel,
    abandonSelection,
    selectOption,
    handleKeydown,
    confirmOperation,
    cancelOperation,
  };
}

function assistantFailureText(error: unknown): ControlText {
  const resultBoundary = codeControlText(
    'platform.screen.useAssistantConversation.pending-content-was-not-submitted-any-earlier-saved-results-are',
    '待确认内容没有提交；如此前已有保存结果，不受本次失败影响。',
  );
  if (error instanceof AppError) {
    if (error.code === 'CONFIG_MISSING') {
      return codeControlText(
        'platform.screen.useAssistantConversation.this-identity-has-no-available-model-configuration-ask-an-administrator',
        '当前身份缺少可用的模型配置。请联系管理员检查租户模型连接、平台共享范围及凭据配置。{value1}',
        { value1: resultBoundary },
      );
    }
    const modelKey = `platform.error.${error.code}`;
    const modelOriginal = modelControlOriginal(modelKey);
    const modelMessage = modelOriginal ? codeControlText(modelKey, modelOriginal) : undefined;
    if (modelMessage) {
      const recovery = ['AI_MODEL_TIMEOUT', 'AI_MODEL_INCOMPLETE_RESPONSE', 'AI_MODEL_INTERRUPTED'].includes(
        error.code,
      )
        ? codeControlText(
            'platform.screen.useAssistantConversation.check-the-current-page-before-continuing',
            '请核实当前页面后继续处理。',
          )
        : '';
      return recovery
        ? joinControlText(modelMessage, '\n', recovery, '\n', resultBoundary)
        : joinControlText(modelMessage, '\n', resultBoundary);
    }
  }
  return codeControlText(
    'platform.screen.useAssistantConversation.this-reply-could-not-be-completed-valuerevise-the-request-and',
    '本轮回复未能完成。{value1}可以调整需求后继续处理。',
    { value1: resultBoundary },
  );
}

function assistantHistoryText(text: string | undefined, selection?: AssistantSelectionInteraction) {
  return [text?.trim(), selection?.prompt, selection?.options.map(({ label }) => `- ${label}`).join('\n')]
    .filter(Boolean)
    .join('\n');
}

async function archiveMessage(item: ConversationItem, language: LanguageContext) {
  const snapshot = {
    role: item.role,
    ...(item.confirmation?.receiptReference &&
    item.confirmationScope &&
    ['executing', 'checking', 'unknown'].includes(item.confirmationState ?? '')
      ? {
          operationReceipt: {
            reference: item.confirmation.receiptReference,
            executionScopeKey: item.confirmationScope,
          },
        }
      : {}),
    text: [
      item.text,
      ...(item.details?.lines ?? []),
      item.selection
        ? assistantHistoryText('', item.selection.value) +
          language.t('platform.screen.useAssistantConversation.selection-status', '\n选择状态：') +
          item.selection.state +
          (item.selection.selectedOptionId
            ? language.t('platform.screen.useAssistantConversation.selected', '\n已选：') +
              item.selection.value.options.find((option) => option.id === item.selection!.selectedOptionId)
                ?.label
            : '')
        : '',
      item.confirmation
        ? [
            item.confirmation.presentation.title,
            ...item.confirmation.presentation.lines,
            ...(item.confirmation.presentation.details?.lines ?? []),
            language.t(
              'platform.screen.useAssistantConversation.historical-operation-status',
              '历史操作状态：',
            ) +
              {
                pending: language.t(
                  'platform.screen.useAssistantConversation.awaiting-confirmation-prepare-again-after-restoring',
                  '待确认（恢复后需重新准备）',
                ),
                executing: language.t(
                  'platform.screen.useAssistantConversation.result-needs-verification',
                  '结果需核实',
                ),
                checking: language.t(
                  'platform.screen.useAssistantConversation.result-needs-verification',
                  '结果需核实',
                ),
                unknown: language.t(
                  'platform.screen.useAssistantConversation.result-needs-verification',
                  '结果需核实',
                ),
                succeeded: language.t('platform.workflow.completed', '已完成'),
                rejected: language.t('platform.workflow.unsubmitted', '未提交'),
                expired: language.t('platform.screen.useAssistantConversation.expired', '已失效'),
                cancelled: language.t('platform.workflow.canceled', '已取消'),
              }[item.confirmationState ?? 'expired'],
          ].join('\n')
        : '',
    ]
      .filter(Boolean)
      .join('\n'),
  };
  // Display provenance is optional. A bad source must not prevent saving the immutable transcript.
  if (item.role !== 'user' && !item.confirmation && !item.selection) {
    try {
      const controlText =
        item.controlText && (JSON.parse(JSON.stringify(item.controlText)) as ControlText | undefined);
      const archivedControlText =
        item.archivedControlText &&
        (JSON.parse(JSON.stringify(item.archivedControlText)) as
          | import('@muyun/web-core').AssistantControlText
          | undefined);
      if (archivedControlText) return { ...snapshot, controlText: archivedControlText };
      if (controlText) return { ...snapshot, controlText: await archiveControlText(controlText) };
    } catch {
      return snapshot;
    }
  }
  return snapshot;
}
