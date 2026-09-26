import { markRaw, computed, onBeforeUnmount, ref, watch } from 'vue';
import type {
  AssistantConversationMessage,
  AssistantSelectionInteraction,
  AssistantSelectionOption,
  AssistantSelectionResponse,
} from '@muyun/web-contracts';
import {
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
  diagnostic?: string;
}

interface ConversationSelection {
  value: AssistantSelectionInteraction;
  token?: AssistantInvocationToken;
  state: 'open' | 'submitting' | 'answered' | 'superseded';
  selectedOptionId?: string;
  retryable?: boolean;
}

export function useAssistantConversation(props: { open: boolean; registry: AssistantSurfaceRegistry }) {
  const draft = ref('');
  const resumableRequest = ref('');
  const interruptedRequest = ref('');
  let lastTypedRequest = '';
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
  let conversationScope: string | undefined;
  let identityScope: string | undefined;
  let streamingItemId: number | undefined;
  let pendingStreamText = '';
  const MAX_HISTORY_MESSAGES = 12;
  const MAX_HISTORY_MESSAGE_LENGTH = 4_000;
  const MAX_HISTORY_LENGTH = 16_000;

  function append(role: ConversationItem['role'], text: string) {
    const normalized = text.trim();
    if (!normalized) return;
    items.value.push({ id: ++nextItemId, role, text: normalized });
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

  function reusePreviousRequest() {
    draft.value = resumableRequest.value;
    resumableRequest.value = '';
  }

  function submit() {
    const message = draft.value.trim();
    if (!message || busy.value || activeRequiredSelection.value || !props.registry.snapshot()) return;
    const history = conversationHistory();
    lastTypedRequest = message;
    resumableRequest.value = '';
    draft.value = '';
    supersedeOpenSelections();
    interruptedRequest.value = '';
    void submitMessage(message, history);
  }

  async function submitMessage(
    message: string,
    history: AssistantConversationMessage[],
    selectionResponse?: AssistantSelectionResponse,
    sourceSelection?: ConversationSelection,
    automatic = false,
  ) {
    const beforeSync = conversationEpoch;
    expireStaleSelections();
    if (beforeSync !== conversationEpoch) return;
    if (!message || busy.value || !props.registry.snapshot()) return;
    const epoch = conversationEpoch;
    append(
      automatic ? 'status' : 'user',
      automatic ? '正在核实已完成结果并准备下一步，可随时停止。' : message,
    );
    busy.value = true;
    activity.value = 'understanding';
    controller = new AbortController();
    const assistantTexts: string[] = [];
    try {
      const result = await runAssistantConversation(props.registry, message, {
        signal: controller.signal,
        history,
        ...(selectionResponse ? { selectionResponse } : {}),
        onActivity(phase) {
          if (epoch !== conversationEpoch) return;
          activity.value = phase;
        },
        onTextDelta(text) {
          if (epoch !== conversationEpoch) return;
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
          if (epoch !== conversationEpoch) return;
          if (streamingItemId !== undefined) {
            items.value = items.value.filter(({ id }) => id !== streamingItemId);
          }
          streamingItemId = undefined;
          pendingStreamText = '';
        },
        onStep(step) {
          if (epoch !== conversationEpoch) return;
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
            });
          }
          if (step.results.length > 0) {
            const succeeded = step.results.filter((candidate) => !candidate.error).length;
            const failed = step.results.length - succeeded;
            if (failed || step.results.some((result) => !result.presentation))
              append('status', capabilityResultStatus(succeeded, failed, step.appliedEffectCount));
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
              append('status', fact);
              assistantTexts.push(`平台操作事实：${fact}`);
            }
          }
        },
      });
      if (epoch !== conversationEpoch) return;
      if (result.termination === 'step-limit')
        append(
          'status',
          '本轮已达到步骤上限，已完成的草稿修改会保留。请检查当前页面，仍有未完成项时可告诉我继续。',
        );
      else if (
        result.termination === 'repeated-call' &&
        result.steps.at(-1)?.results.some((candidate) => candidate.error)
      ) {
        append('status', '相同操作未能完成，已停止重复尝试。请检查当前页面或补充信息。');
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
      if (
        result.termination === 'step-limit' ||
        (result.termination === 'repeated-call' &&
          result.steps.at(-1)?.results.some((candidate) => candidate.error))
      )
        interruptedRequest.value = message;
      commitConversation(message, assistantTexts);
      if (sourceSelection) sourceSelection.state = 'answered';
    } catch (error) {
      if (epoch !== conversationEpoch) return;
      interruptedRequest.value = message;
      if (isAbortError(error)) {
        interruptedRequest.value = '';
        reopenSelection(sourceSelection);
        append('status', '已停止本次操作。');
      } else if (error instanceof StaleAssistantInvocationError) {
        reopenSelection(sourceSelection);
        commitConversation(message, []);
        append(
          'status',
          '页面发生了与当前任务冲突的变化，本轮已暂停。请确认当前页面后告诉我继续或调整目标。',
        );
      } else if (error instanceof AssistantConversationInterruptedError) {
        if (error.termination === 'cancelled') interruptedRequest.value = '';
        if (sourceSelection) sourceSelection.state = 'answered';
        commitConversation(message, assistantTexts);
        const applied = error.steps.reduce((total, step) => total + step.appliedEffectCount, 0);
        const unknown = error.steps.some((step) =>
          step.results.some((result) => result.execution === 'unknown'),
        );
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
            : `前面的 ${applied} 项页面操作已生效，${reason}，目标可能尚未完成。请检查草稿和待填项，再告诉我继续。`,
        );
      } else {
        reopenSelection(sourceSelection);
        commitConversation(message, assistantTexts);
        append(
          'status',
          '本轮回复未能完成。已确认的保存结果仍有效，待确认内容不会因此自动提交。可以带回这条请求继续处理。',
        );
      }
    } finally {
      if (epoch === conversationEpoch) {
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

  function expireStaleSelections() {
    const token = props.registry.snapshot()?.token;
    const scope = token?.conversationScopeKey;
    const identity = token?.identityScopeKey;
    if (identity === identityScope && conversationScope === undefined && scope !== undefined) {
      conversationScope = scope;
    }
    if (
      (identity !== undefined && identity !== identityScope) ||
      (scope !== undefined && scope !== conversationScope)
    ) {
      const previous = conversationScope;
      interruptedRequest.value = '';
      resumableRequest.value = identity === identityScope && previous !== undefined ? lastTypedRequest : '';
      lastTypedRequest = '';
      identityScope = identity;
      conversationScope = scope;
      conversationEpoch += 1;
      controller?.abort();
      controller = undefined;
      items.value = [];
      completedHistory.value = [];
      draft.value = '';
      busy.value = false;
      operationPending.value = false;
      activity.value = 'idle';
      streamingItemId = undefined;
      pendingStreamText = '';
      if (previous !== undefined) append('status', '业务身份或租户范围已变化，已开始新会话。');
    }
    for (const item of items.value) {
      if (item.confirmation) item.confirmationState = item.confirmation.state;
      if (item.selection?.state === 'open' && !selectionIsCurrent(item.selection)) {
        item.selection.state = 'superseded';
      }
    }
  }

  function abandonSelection(item: ConversationItem) {
    if (busy.value || item.selection?.state !== 'open') return;
    item.selection.state = 'superseded';
    const message = '放弃本次提议：' + item.selection.value.prompt;
    append('user', message);
    commitConversation(message, []);
  }

  function selectOption(item: ConversationItem, option: AssistantSelectionOption) {
    const selection = item.selection;
    if (!selection || selection.state !== 'open' || busy.value) return;
    if (!selectionIsCurrent(selection)) {
      selection.state = 'superseded';
      return;
    }
    const history = conversationHistory();
    supersedeOpenSelections(selection);
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

  function supersedeOpenSelections(except?: ConversationSelection) {
    for (const item of items.value) {
      if (item.selection && item.selection !== except && item.selection.state === 'open') {
        item.selection.state = 'superseded';
      }
    }
  }

  function assistantHistoryText(text: string | undefined, selection?: AssistantSelectionInteraction) {
    return [text?.trim(), selection?.prompt, selection?.options.map(({ label }) => `- ${label}`).join('\n')]
      .filter(Boolean)
      .join('\n');
  }

  function conversationHistory(): AssistantConversationMessage[] {
    const pending = items.value.filter((item) => item.confirmation?.state === 'pending').slice(-3);
    return boundedHistory([
      ...completedHistory.value,
      ...pending.map(
        (item): AssistantConversationMessage => ({
          role: 'assistant',
          text: `平台待确认内容（尚未执行，用户可追问）：${item.confirmation!.modelSummary}`,
        }),
      ),
    ]);
  }

  function commitConversation(message: string, assistantTexts: string[]) {
    completedHistory.value = boundedHistory([
      ...completedHistory.value,
      { role: 'user', text: message },
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

  function capabilityResultStatus(succeeded: number, failed: number, applied: number) {
    const successfulText =
      applied === 0
        ? `已获取 ${succeeded} 项结果`
        : applied === succeeded
          ? `已应用 ${applied} 项页面操作`
          : `已完成 ${succeeded} 项调用，其中 ${applied} 项已应用到页面`;
    return failed > 0 ? `${successfulText}，${failed} 项未完成` : successfulText;
  }

  async function confirmOperation(item: ConversationItem, check = false) {
    if (!item.confirmation || busy.value) return;
    const epoch = conversationEpoch;
    busy.value = true;
    activity.value = 'executing';
    operationPending.value = true;
    const pending = check ? item.confirmation.check() : item.confirmation.confirm();
    item.confirmationState = item.confirmation.state;
    try {
      await pending;
      if (epoch !== conversationEpoch) return;
      item.confirmationState = item.confirmation.state;
      const result = item.confirmation.result;
      if (result) {
        append('status', [result.title, ...result.lines].join('\n'));
        commitConversation(check ? '查询操作结果' : item.confirmation.confirmLabel, [
          [result.title, ...result.lines].join('\n'),
        ]);
      }
    } finally {
      if (epoch === conversationEpoch) {
        operationPending.value = false;
        busy.value = false;
        activity.value = 'idle';
      }
    }
    if (epoch === conversationEpoch && props.open && !draft.value.trim() && !activeRequiredSelection.value) {
      const next = item.confirmation.takeContinuation();
      if (next) await submitMessage(next, conversationHistory(), undefined, undefined, true);
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

  function handleKeydown(event: KeyboardEvent) {
    if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
    event.preventDefault();
    void submit();
  }

  function isAbortError(error: unknown) {
    return error instanceof DOMException && error.name === 'AbortError';
  }

  return {
    draft,
    resumableRequest,
    interruptedRequest,
    reuseInterruptedRequest() {
      draft.value = interruptedRequest.value;
      interruptedRequest.value = '';
    },
    items,
    busy,
    activityText,
    operationPending,
    activeRequiredSelection,
    reusePreviousRequest,
    submit,
    cancel,
    abandonSelection,
    selectOption,
    handleKeydown,
    confirmOperation,
    cancelOperation,
  };
}
