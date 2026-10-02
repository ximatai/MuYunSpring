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
  modelFailureMessage,
  restoreAssistantOperationReceipt,
  parseOperationReceiptReference,
  AssistantConversationInterruptedError,
  runAssistantConversation,
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
  selection?: ConversationSelection;
  confirmation?: AssistantOperationConfirmation;
  confirmationState?: AssistantConfirmationState;
  confirmationScope?: string;
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
  registry: AssistantSurfaceRegistry;
  conversationClient?: AssistantConversationClient;
  constructionPlan?: ConstructionPlanSession;
  configurationCollaboration?: ConfigurationCollaboration;
}) {
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
        return '正在执行页面操作…';
      case 'responding':
        return '正在组织回复…';
      case 'cancelling':
        return '正在取消…';
      case 'understanding':
      default:
        return '正在理解你的目标…';
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
  const MAX_HISTORY_MESSAGES = 12;
  const MAX_HISTORY_MESSAGE_LENGTH = 4_000;
  const MAX_HISTORY_LENGTH = 16_000;

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
      ? `请核实当前需求的实际进度并建议下一步，暂不修改或保存。用户需求：${userGoal}`
      : `请先核实当前事实，再继续准备用户需求尚缺的配置或业务候选；已有操作结果未知时先查询结果，不重提。新的保存仍须重新确认。用户需求：${userGoal}`;
    restored.value = false;
    interruptedRequest.value = undefined;
    supersedePendingSelections();
    void submitMessage(message, conversationHistory(), undefined, undefined, { userGoal }, readOnly);
  }
  const linkedPlanId = ref<string>();
  function clearConversation(resetDesign = false) {
    conversationEpoch++;
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
    () => ({
      title: items.value.find((item) => item.role === 'user')?.text.slice(0, 120) || '新对话',
      messages: items.value.map(archiveMessage),
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
    }),
    async (content) => {
      clearConversation(true);
      const restoredEpoch = conversationEpoch;
      items.value = content.messages.map((message) => {
        const item: ConversationItem = { role: message.role, text: message.text, id: ++nextItemId };
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
              ),
            );
            item.confirmationState = 'unknown';
          } catch {
            /* Historical invalid references never become requests. */
          }
        }
        return item;
      });
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
                ? '聊天记录已恢复，但关联方案不存在或当前身份无权访问，暂不能继续修改该方案。可以核对登录身份与业务范围；如需重新整理，请新建对话。已有业务仍可按当前配置核实。'
                : '历史建设记录暂时无法读取，仍可直接核实和改进当前业务。未完成设计请稍后重试读取。',
            );
        }
      }
    },
    () => clearConversation(true),
    () =>
      props.constructionPlan?.recovery.value ? '建设确认结果尚未确定，请先查询结果再切换对话。' : undefined,
  );

  function append(
    role: ConversationItem['role'],
    text: string,
    details?: AssistantResultPresentation['details'],
    diagnostic?: string,
  ) {
    const normalized = text.trim();
    if (!normalized) return;
    items.value.push({ id: ++nextItemId, role, text: normalized, details, diagnostic });
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
    continuation?: { userGoal: string },
    readOnly = false,
  ) {
    const beforeSync = conversationEpoch;
    expireStaleSelections();
    if (beforeSync !== conversationEpoch) return;
    if (!message || busy.value || archive.loading.value || !props.registry.snapshot()) return;
    const epoch = conversationEpoch;
    let requestScope = executionGeneration;
    requestGeneration = requestScope;
    const userGoal = continuation?.userGoal ?? message;
    const rememberInterruption = () => {
      interruptedRequest.value = { userGoal, readOnly };
    };
    const commitTurn = (texts: string[]) => commitConversation(continuation ? undefined : userGoal, texts);
    append(
      continuation ? 'status' : 'user',
      continuation
        ? readOnly
          ? '正在核实当前进度，暂不修改或保存。'
          : '正在核实当前状态并准备下一步，可随时停止。'
        : message,
    );
    busy.value = true;
    activity.value = 'understanding';
    controller = new AbortController();
    const assistantTexts: string[] = [];
    const diagnostics: string[] = [];
    try {
      const saved = await archive.save();
      if (epoch !== conversationEpoch || requestScope !== executionGeneration) return;
      if (!saved) {
        rememberInterruption();
        reopenSelection(sourceSelection);
        return;
      }
      const result = await runAssistantConversation(props.registry, message, {
        signal: controller.signal,
        executionPolicy: { readOnly },
        history,
        ...(selectionResponse ? { selectionResponse } : {}),
        onDiagnostic(event) {
          if (event.type === 'capability.completed')
            diagnostics.push(`${event.stepIndex + 1}: ${event.capabilityCode} · ${event.outcome}`);
          else if (event.type === 'decision.failed') {
            const stages = {
              'context-changed': '页面上下文已变化',
              cancelled: '本轮已取消',
              'surface-unavailable': '页面操作入口尚未就绪',
              'surface-settlement-failed': '页面状态准备失败',
              'model-request-failed': '模型请求未完成',
            };
            diagnostics.push(`${event.stepIndex + 1}: ${stages[event.reason]}`);
          } else if (event.type === 'summary.completed')
            diagnostics.push(`结果整理：${event.succeeded ? '完成' : (event.reason ?? '未完成')}`);
        },
        onExecutionScopeChange() {
          if (epoch !== conversationEpoch) return;
          expireStaleSelections();
          requestScope = executionGeneration;
          requestGeneration = requestScope;
          assistantTexts.length = 0;
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
            assistantTexts.push(assistantHistoryText(step.output.text, step.output.selection));
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
            });
          }
          if (step.results.length > 0) {
            for (const result of step.results) {
              if (result.error) {
                items.value.push({
                  id: ++nextItemId,
                  role: 'status',
                  text:
                    result.execution === 'unknown'
                      ? '这一步的结果尚未确定，请先核实，不要重复提交。已完成的其他步骤保留。'
                      : '这一步未完成，可根据校验结果继续调整。已完成的其他步骤保留。',
                  ...(result.error.code !== 'CAPABILITY_FAILED' ? { diagnostic: result.error.message } : {}),
                });
              }
              if (!result.presentation) continue;
              const fact = [result.presentation.title, ...result.presentation.lines].join('\n');
              append('status', fact, result.presentation.details);
              assistantTexts.push(`平台操作事实：${fact}`);
            }
          }
        },
      });
      if (epoch !== conversationEpoch || requestScope !== executionGeneration) return;
      if (result.termination === 'step-limit')
        append(
          'status',
          result.steps.some((step) => step.appliedEffectCount > 0)
            ? '本轮已暂停，已完成的操作保留；这不代表配置或业务已保存。可以继续核实剩余事项。'
            : '本轮已暂停，尚未完成的事项可以继续核实；读取信息不代表修改或保存。',
          undefined,
          diagnostics.join('\n'),
        );
      else if (result.termination === 'repeated-call') {
        append(
          'status',
          '已停止重复操作，可以根据已查明的内容继续核实或补充需求。',
          undefined,
          diagnostics.join('\n'),
        );
      } else if (
        result.steps.every(
          (step) => !step.output.text && !step.output.selection && !step.confirmations?.length,
        )
      ) {
        const applied = result.steps.reduce((total, step) => total + step.appliedEffectCount, 0);
        const succeeded = result.steps.some((step) => step.results.some((candidate) => !candidate.error));
        if (applied > 0) {
          append('assistant', '页面操作已完成，请检查当前页面。');
          assistantTexts.push('页面操作已完成，请检查当前页面。');
        } else if (succeeded) {
          append('assistant', '信息已读取，但未生成可展示的说明，请重新提问。');
          assistantTexts.push('信息已读取，但未生成可展示的说明，请重新提问。');
        }
      }
      if (result.termination === 'step-limit' || result.termination === 'repeated-call')
        rememberInterruption();
      commitTurn(assistantTexts);
      if (sourceSelection) sourceSelection.state = 'answered';
    } catch (error) {
      if (epoch !== conversationEpoch || requestScope !== executionGeneration) return;
      rememberInterruption();
      if (isAbortError(error)) {
        interruptedRequest.value = undefined;
        commitTurn([...assistantTexts, '用户已停止本轮执行。需求仅作为讨论记录保留，不得自动继续执行。']);
        reopenSelection(sourceSelection);
        append('status', '已停止本次操作。');
      } else if (error instanceof StaleAssistantInvocationError) {
        reopenSelection(sourceSelection);
        commitTurn([]);
        append(
          'status',
          '页面发生了与当前任务冲突的变化，本轮已暂停。请确认当前页面后告诉我继续或调整目标。',
        );
      } else if (error instanceof AssistantConversationInterruptedError) {
        if (error.termination === 'cancelled') interruptedRequest.value = undefined;
        if (sourceSelection) sourceSelection.state = 'answered';
        commitTurn(assistantTexts);
        const applied = error.steps.reduce((total, step) => total + step.appliedEffectCount, 0);
        const unknown = error.steps.some((step) =>
          step.results.some((result) => result.execution === 'unknown'),
        );
        const hasPresentedEffects =
          error.steps
            .flatMap((step) => step.results)
            .filter((result) => result.execution === 'effect-applied' && result.presentation).length >=
          applied;
        const reason =
          error.termination === 'cancelled'
            ? '本轮已取消'
            : error.termination === 'context-changed'
              ? '页面上下文已变化'
              : '后续处理失败';
        append(
          'status',
          unknown
            ? `${reason}，有页面操作的结果尚不确定。请检查当前页面；不会自动重试。`
            : `${reason}，目标尚未核实完成。` +
                (applied > 0
                  ? hasPresentedEffects
                    ? '已生效步骤见上方平台操作记录；打开页面、准备草稿不代表保存。'
                    : '本轮已有页面操作生效，但缺少具体结果说明，请核对当前页面；不能据此判断已保存。'
                  : '本轮没有已确认生效的页面操作。') +
                (error.termination === 'model-failed' ? `\n${assistantFailureMessage(error.cause)}` : ''),
          undefined,
          diagnostics.join('\n'),
        );
      } else {
        reopenSelection(sourceSelection);
        commitTurn(assistantTexts);
        append('status', assistantFailureMessage(error), undefined, diagnostics.join('\n'));
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
    append('status', '本次选择未能处理完成。可以重试选项，也可以用文字继续说明；已有内容保留。');
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
        append('status', '业务范围已变化，对话与建设目标保留；后续操作将重新核实当前页面的数据。');
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
    const message = '放弃本次提议：' + item.selection.value.prompt;
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
              role: 'assistant' as const,
              text: '历史会话已恢复。已商定的需求无需用户重新描述；聊天历史不存储未保存草稿，也不恢复旧确认授权；当前工作区仍可能保留未保存候选，必须读取后判断，不能直接宣称草稿丢失。先读取当前业务事实，区分历史讨论、已证实结果和待核实事项，再说明最少的后续步骤。历史提议不是新的执行授权，写入需重新准备确认。区分“记录里曾经试填”与“当前草稿未恢复”，不能因后者否认前者。当前查看模式没有暴露编辑能力，不等于平台不支持；未重新发现相应编辑场景能力前，只说明尚待核实，不要求用户绕开助手手工完成。',
            },
          ]
        : []),
      ...pending.map(
        (item): AssistantConversationMessage => ({
          role: 'assistant',
          text: `平台待确认内容（尚未执行，用户可追问）：${item.confirmation!.modelSummary}`,
        }),
      ),
    ]);
  }

  function commitConversation(message: string | undefined, assistantTexts: string[]) {
    completedHistory.value = boundedHistory([
      ...completedHistory.value,
      ...(message ? [{ role: 'user' as const, text: message }] : []),
      ...assistantTexts.map((text): AssistantConversationMessage => ({ role: 'assistant', text })),
    ]);
  }

  function boundedHistory(history: AssistantConversationMessage[]): AssistantConversationMessage[] {
    const candidates = history
      .slice(-MAX_HISTORY_MESSAGES)
      .map(({ role, text }) => ({ role, text: text.slice(0, MAX_HISTORY_MESSAGE_LENGTH) }));
    const selected: AssistantConversationMessage[] = [];
    let length = 0;
    for (let index = candidates.length - 1; index >= 0; index -= 1) {
      const candidate = candidates[index]!;
      if (length + candidate.text.length > MAX_HISTORY_LENGTH) break;
      selected.unshift(candidate);
      length += candidate.text.length;
    }
    if (selected[0]?.role === 'assistant') selected.shift();
    return selected;
  }

  async function confirmOperation(item: ConversationItem, check = false) {
    if (!item.confirmation || busy.value || archive.loading.value) return;
    const epoch = conversationEpoch;
    const operationScope = executionGeneration;
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
      const pending = check ? item.confirmation.check() : item.confirmation.confirm();
      item.confirmationState = item.confirmation.state;
      await pending;
      if (epoch !== conversationEpoch) return;
      item.confirmationState = item.confirmation.state;
      const result = item.confirmation.result;
      if (result) {
        append('status', [result.title, ...result.lines].join('\n'));
        if (operationScope === executionGeneration)
          commitConversation(undefined, [[result.title, ...result.lines].join('\n')]);
      }
    } finally {
      if (epoch === conversationEpoch) {
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
        // Confirmation receipts can displace the user's request from bounded model history.
        // Recover it from the conversation, where automatic continuation is never a user message.
        const request = items.value.filter((entry) => entry.role === 'user').at(-1)?.text;
        const message = request
          ? `${next}\n用户最近明确提出的要求：${request.slice(0, MAX_HISTORY_MESSAGE_LENGTH)}\n仅续办该要求；任务清单不是扩大范围的授权。用户暂缓的事项继续保留，不重新提议建设；当前目标完成后说明结果并停止。`
          : next;
        await submitMessage(message, conversationHistory(), undefined, undefined, {
          userGoal: request ?? '',
        });
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

function assistantFailureMessage(error: unknown) {
  if (error instanceof AppError) {
    if (error.code === 'CONFIG_MISSING') {
      return '当前身份缺少可用的模型配置。请联系管理员检查租户模型连接、平台共享范围及凭据配置。已确认的保存结果仍有效，待确认内容没有提交。';
    }
    const modelMessage = modelFailureMessage(error);
    if (modelMessage) {
      const recovery = ['AI_MODEL_TIMEOUT', 'AI_MODEL_INCOMPLETE_RESPONSE', 'AI_MODEL_INTERRUPTED'].includes(
        error.code,
      )
        ? '请核实当前页面后继续处理。'
        : '';
      return `${modelMessage}${recovery}已确认的保存结果仍有效，待确认内容没有提交。`;
    }
  }
  const message = error instanceof Error ? error.message : '';
  if (message === '模型本次回复在返回可用内容前中止，请稍后重试')
    return '模型服务未返回可用内容。请稍后重试；持续失败时联系管理员检查模型服务状态。已确认的保存结果仍有效，待确认内容没有提交。';
  if (message === '模型响应被截断，请缩短描述后重试')
    return '模型本次回复达到长度上限，未能完成。已确认的保存结果仍有效，待确认内容没有提交。可以分步处理，或联系管理员调整回复上限。';
  if (message.startsWith('本次内容预计超过模型上下文预算') || message.startsWith('本次输出预算超过模型容量'))
    return `${message}。已确认的保存结果仍有效，待确认内容没有提交。`;
  if (message.startsWith('AI model request was rejected'))
    return '模型服务拒绝了本次请求。请稍后重试；持续失败时联系管理员检查模型配置与服务状态。已确认的保存结果仍有效，待确认内容没有提交。';
  if (message === 'AI model response body timed out')
    return '等待模型回复超时。已确认的保存结果仍有效，待确认内容没有提交。稍后可以继续核实当前需求。';
  return '本轮回复未能完成。已确认的保存结果仍有效，待确认内容不会因此自动提交。可以调整需求后继续处理。';
}

function assistantHistoryText(text: string | undefined, selection?: AssistantSelectionInteraction) {
  return [text?.trim(), selection?.prompt, selection?.options.map(({ label }) => `- ${label}`).join('\n')]
    .filter(Boolean)
    .join('\n');
}

function archiveMessage(item: ConversationItem) {
  return {
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
          '\n选择状态：' +
          item.selection.state +
          (item.selection.selectedOptionId
            ? '\n已选：' +
              item.selection.value.options.find((option) => option.id === item.selection!.selectedOptionId)
                ?.label
            : '')
        : '',
      item.confirmation
        ? [
            item.confirmation.presentation.title,
            ...item.confirmation.presentation.lines,
            ...(item.confirmation.presentation.details?.lines ?? []),
            '历史操作状态：' +
              {
                pending: '待确认（恢复后需重新准备）',
                executing: '结果需核实',
                checking: '结果需核实',
                unknown: '结果需核实',
                succeeded: '已完成',
                rejected: '未提交',
                expired: '已失效',
                cancelled: '已取消',
              }[item.confirmationState ?? 'expired'],
          ].join('\n')
        : '',
    ]
      .filter(Boolean)
      .join('\n'),
  };
}
