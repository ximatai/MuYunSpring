import { computed, onBeforeUnmount, ref, watch } from 'vue';
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
  normalizeError,
  type AssistantSurfaceRegistry,
  type AssistantInvocationToken,
  userFacingErrorMessage,
} from '@muyun/web-core';

interface ConversationItem {
  id: number;
  role: 'user' | 'assistant' | 'status';
  text: string;
  selection?: ConversationSelection;
}

interface ConversationSelection {
  value: AssistantSelectionInteraction;
  token?: AssistantInvocationToken;
  state: 'open' | 'submitting' | 'answered' | 'superseded';
  selectedOptionId?: string;
}

export function useAssistantConversation(props: { open: boolean; registry: AssistantSurfaceRegistry }) {
  const draft = ref('');
  const resumableRequest = ref('');
  let lastTypedRequest = '';
  const items = ref<ConversationItem[]>([]);
  const completedHistory = ref<AssistantConversationMessage[]>([]);
  const busy = ref(false);
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
      ({ selection }) => selection?.state === 'open' && selection.value.inputPolicy === 'selection_required',
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
    void submitMessage(message, history);
  }

  async function submitMessage(
    message: string,
    history: AssistantConversationMessage[],
    selectionResponse?: AssistantSelectionResponse,
    sourceSelection?: ConversationSelection,
  ) {
    const beforeSync = conversationEpoch;
    expireStaleSelections();
    if (beforeSync !== conversationEpoch) return;
    if (!message || busy.value || !props.registry.snapshot()) return;
    const epoch = conversationEpoch;
    append('user', message);
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
            else if (step.output.selection) {
              const item = items.value.find(({ id }) => id === streamingItemId);
              if (item) item.selection = newSelection(step.output.selection);
            }
          }
          streamingItemId = undefined;
          pendingStreamText = '';
          if (step.results.length > 0) {
            const succeeded = step.results.filter((candidate) => !candidate.error).length;
            const failed = step.results.length - succeeded;
            append('status', capabilityResultStatus(succeeded, failed, step.appliedEffectCount));
            for (const result of step.results) {
              if (!result.presentation) continue;
              append('status', [result.presentation.title, ...result.presentation.lines].join('\n'));
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
      } else if (result.steps.every((step) => !step.output.text && !step.output.selection)) {
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
      if (result.termination !== 'step-limit') commitConversation(message, assistantTexts);
      if (sourceSelection) sourceSelection.state = 'answered';
    } catch (error) {
      if (epoch !== conversationEpoch) return;
      if (isAbortError(error)) {
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
        append('status', userFacingErrorMessage(normalizeError(error)));
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
      activity.value = 'idle';
      streamingItemId = undefined;
      pendingStreamText = '';
      if (previous !== undefined) append('status', '业务身份或租户范围已变化，已开始新会话。');
    }
    for (const item of items.value) {
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
    return boundedHistory(completedHistory.value);
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
    items,
    busy,
    activityText,
    activeRequiredSelection,
    reusePreviousRequest,
    submit,
    cancel,
    abandonSelection,
    selectOption,
    handleKeydown,
  };
}
