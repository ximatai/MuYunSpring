<script setup lang="ts">
import { nextTick, ref } from 'vue';
import { UiButton, UiIcon, UiTextArea } from '@muyun/vue-ui-antdv';
import {
  AssistantConversationFollowUpError,
  runAssistantConversation,
  StaleAssistantInvocationError,
  normalizeError,
  type AssistantSurfaceRegistry,
  userFacingErrorMessage,
} from '@muyun/web-core';

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
}

const draft = ref('');
const items = ref<ConversationItem[]>([]);
const busy = ref(false);
let nextItemId = 0;
let controller: AbortController | undefined;

function append(role: ConversationItem['role'], text: string) {
  const normalized = text.trim();
  if (!normalized) return;
  items.value.push({ id: ++nextItemId, role, text: normalized });
}

async function submit() {
  const message = draft.value.trim();
  if (!message || busy.value || !props.registry.snapshot()) return;
  draft.value = '';
  append('user', message);
  busy.value = true;
  controller = new AbortController();
  try {
    const result = await runAssistantConversation(props.registry, message, {
      signal: controller.signal,
      onStep(step) {
        if (step.output.text) append('assistant', step.output.text);
        if (step.results.length > 0) {
          const succeeded = step.results.filter((candidate) => !candidate.error).length;
          const failed = step.results.length - succeeded;
          append('status', capabilityResultStatus(succeeded, failed, step.appliedEffectCount));
        }
      },
    });
    if (!result.completed) append('status', '本次任务步骤较多，已暂停。请重新完整描述后续目标。');
    else if (result.steps.every((step) => !step.output.text)) {
      const applied = result.steps.reduce((total, step) => total + step.appliedEffectCount, 0);
      const succeeded = result.steps.some((step) => step.results.some((candidate) => !candidate.error));
      if (applied > 0) append('assistant', '页面操作已完成，请检查当前页面。');
      else if (succeeded) append('assistant', '信息已读取，但未生成可展示的说明，请重新提问。');
    }
  } catch (error) {
    if (isAbortError(error)) append('status', '已停止本次操作。');
    else if (error instanceof StaleAssistantInvocationError) {
      append('status', '页面状态已经变化，请基于当前页面重新发送。');
    } else if (error instanceof AssistantConversationFollowUpError) {
      const applied = error.steps.reduce((total, step) => total + step.appliedEffectCount, 0);
      append(
        'status',
        `前面的 ${applied} 项页面操作已生效，但后续说明未能生成。请检查当前页面，必要时继续告诉我下一步。`,
      );
    } else append('status', userFacingErrorMessage(normalizeError(error)));
  } finally {
    controller = undefined;
    busy.value = false;
    await nextTick();
  }
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
  controller?.abort();
}

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
      <UiButton type="text" aria-label="关闭智能助手" @click="emit('close')">
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
        {{ item.text }}
      </article>
      <div v-if="busy" class="assistant-panel__working">正在理解并执行…</div>
    </section>

    <footer class="assistant-panel__composer">
      <UiTextArea
        v-model:value="draft"
        :rows="3"
        :maxlength="4000"
        :disabled="busy"
        placeholder="描述你想完成的事情"
        @keydown="handleKeydown"
      />
      <div class="assistant-panel__actions">
        <span>Enter 发送，Shift + Enter 换行</span>
        <UiButton v-if="busy" @click="cancel">停止</UiButton>
        <UiButton v-else type="primary" :disabled="!draft.trim() || !registry.snapshot()" @click="submit">
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
  white-space: pre-wrap;
  overflow-wrap: anywhere;
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
