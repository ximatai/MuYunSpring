<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { UiButton, UiIcon, UiTextArea } from '@muyun/vue-ui-antdv';
import type {
  AssistantConversationMessage,
  AssistantSelectionInteraction,
  AssistantSelectionOption,
  AssistantSelectionResponse,
} from '@muyun/web-contracts';
import {
  AssistantConversationFollowUpError,
  runAssistantConversation,
  StaleAssistantInvocationError,
  normalizeError,
  type AssistantSurfaceRegistry,
  type AssistantInvocationToken,
  userFacingErrorMessage,
} from '@muyun/web-core';
import AssistantMarkdownContent from './AssistantMarkdownContent.vue';
import AssistantSelectionCard from './AssistantSelectionCard.vue';

defineOptions({ name: 'WorkbenchAssistantPanel' });

const props = defineProps<{
  open: boolean;
  registry: AssistantSurfaceRegistry;
}>();

const emit = defineEmits<{ close: [] }>();

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

const draft = ref('');
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

function submit() {
  const message = draft.value.trim();
  if (!message || busy.value || activeRequiredSelection.value || !props.registry.snapshot()) return;
  const history = conversationHistory();
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
  if (!message || busy.value || !props.registry.snapshot()) return;
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
        activity.value = phase;
      },
      onTextDelta(text) {
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
        if (streamingItemId !== undefined) {
          items.value = items.value.filter(({ id }) => id !== streamingItemId);
        }
        streamingItemId = undefined;
        pendingStreamText = '';
      },
      onStep(step) {
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
        }
      },
    });
    if (!result.completed) append('status', '本次任务步骤较多，已暂停。请重新完整描述后续目标。');
    else if (result.steps.every((step) => !step.output.text && !step.output.selection)) {
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
    if (result.completed) commitConversation(message, assistantTexts);
    if (sourceSelection) sourceSelection.state = 'answered';
  } catch (error) {
    if (isAbortError(error)) {
      reopenSelection(sourceSelection);
      append('status', '已停止本次操作。');
    } else if (error instanceof StaleAssistantInvocationError) {
      reopenSelection(sourceSelection);
      commitConversation(message, []);
      append('status', '页面发生了与当前任务冲突的变化，本轮已暂停。请确认当前页面后告诉我继续或调整目标。');
    } else if (error instanceof AssistantConversationFollowUpError) {
      if (sourceSelection) sourceSelection.state = 'answered';
      commitConversation(message, assistantTexts);
      const applied = error.steps.reduce((total, step) => total + step.appliedEffectCount, 0);
      append(
        'status',
        `前面的 ${applied} 项页面操作已生效，但后续说明未能生成。请检查当前页面，必要时继续告诉我下一步。`,
      );
    } else {
      reopenSelection(sourceSelection);
      append('status', userFacingErrorMessage(normalizeError(error)));
    }
  } finally {
    streamingItemId = undefined;
    pendingStreamText = '';
    controller = undefined;
    busy.value = false;
    activity.value = 'idle';
    await nextTick();
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
  return current !== undefined && JSON.stringify(current) === JSON.stringify(selection.token);
}

function expireStaleSelections() {
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

function close() {
  cancel();
  emit('close');
}

watch(
  () => props.open,
  (open) => {
    if (!open) cancel();
  },
);
onBeforeUnmount(cancel);

watch(() => props.registry.snapshot()?.token, expireStaleSelections, { deep: true });
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
</script>

<template>
  <aside v-if="open" class="assistant-panel" aria-label="智能助手">
    <header class="assistant-panel__header">
      <div>
        <strong>智能助手</strong>
        <span>基于当前页面提供帮助</span>
      </div>
      <UiButton type="text" aria-label="关闭智能助手" @click="close">
        <UiIcon name="close" />
      </UiButton>
    </header>

    <section class="assistant-panel__conversation" aria-live="polite">
      <div v-if="items.length === 0" class="assistant-panel__welcome">
        <strong>我可以帮你操作当前工作区</strong>
        <span>例如：打开智能模型配置，或填写当前表单中可编辑的字段。</span>
      </div>
      <article
        v-for="item in items"
        :key="item.id"
        class="assistant-message"
        :class="`assistant-message--${item.role}`"
      >
        <template v-if="item.role === 'assistant'">
          <AssistantMarkdownContent v-if="item.text" :content="item.text" />
          <AssistantSelectionCard
            v-if="item.selection"
            :selection="item.selection.value"
            :state="item.selection.state"
            :selected-option-id="item.selection.selectedOptionId"
            @select="(option) => selectOption(item, option)"
            @abandon="abandonSelection(item)"
          />
        </template>
        <template v-else>{{ item.text }}</template>
      </article>
      <div v-if="busy" class="assistant-panel__working">{{ activityText }}</div>
    </section>

    <footer class="assistant-panel__composer">
      <UiTextArea
        v-model:value="draft"
        :rows="3"
        :maxlength="4000"
        :disabled="busy || Boolean(activeRequiredSelection)"
        :placeholder="activeRequiredSelection ? '请先完成上方选择' : '描述你想完成的事情'"
        @keydown="handleKeydown"
      />
      <div class="assistant-panel__actions">
        <span>Enter 发送，Shift + Enter 换行</span>
        <UiButton v-if="busy" @click="cancel">停止</UiButton>
        <UiButton
          v-else
          type="primary"
          :disabled="!draft.trim() || Boolean(activeRequiredSelection) || !registry.snapshot()"
          @click="submit"
        >
          发送
        </UiButton>
      </div>
    </footer>
  </aside>
</template>

<style scoped>
.assistant-panel {
  position: absolute;
  z-index: 7;
  top: 0;
  right: 0;
  bottom: 0;
  display: grid;
  width: min(400px, calc(100vw - 24px));
  grid-template-rows: auto minmax(0, 1fr) auto;
  border-left: 1px solid var(--muyun-support-border);
  background: var(--muyun-support-surface);
  box-shadow: -10px 0 28px rgb(15 23 42 / 12%);
}

.assistant-panel__header,
.assistant-panel__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.assistant-panel__header {
  min-height: 58px;
  padding: 10px 12px 10px 16px;
  border-bottom: 1px solid var(--muyun-support-border);
}

.assistant-panel__header > div,
.assistant-panel__welcome {
  display: grid;
  gap: 3px;
}

.assistant-panel__header span,
.assistant-panel__welcome span,
.assistant-panel__actions span,
.assistant-panel__working {
  color: var(--muyun-support-text-muted);
  font-size: 12px;
}

.assistant-panel__conversation {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 10px;
  overflow-y: auto;
  padding: 16px;
}

.assistant-panel__welcome {
  padding: 14px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  background: var(--muyun-support-canvas);
}

.assistant-message {
  max-width: 88%;
  padding: 9px 12px;
  border-radius: 10px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.assistant-message--user,
.assistant-message--status {
  white-space: pre-wrap;
}

.assistant-message--user {
  align-self: flex-end;
  background: var(--muyun-brand-accent-base);
  color: var(--muyun-brand-accent-on-base);
}

.assistant-message--assistant {
  align-self: flex-start;
  background: var(--muyun-support-canvas);
  color: var(--muyun-support-text);
}

.assistant-message--status {
  align-self: center;
  padding: 2px 8px;
  color: var(--muyun-support-text-muted);
  font-size: 12px;
}

.assistant-panel__composer {
  display: grid;
  gap: 8px;
  padding: 12px 16px 16px;
  border-top: 1px solid var(--muyun-support-border);
}

.assistant-panel__actions span {
  min-width: 0;
}
</style>
