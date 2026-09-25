<script setup lang="ts">
import { UiButton, UiIcon, UiTextArea } from '@muyun/vue-ui-antdv';
import type { AssistantSurfaceRegistry } from '@muyun/web-core';
import AssistantMarkdownContent from './AssistantMarkdownContent.vue';
import AssistantSelectionCard from './AssistantSelectionCard.vue';
import { useAssistantConversation } from './useAssistantConversation';

defineOptions({ name: 'WorkbenchAssistantPanel' });
const props = defineProps<{ open: boolean; registry: AssistantSurfaceRegistry }>();
const emit = defineEmits<{ close: [] }>();
const {
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
} = useAssistantConversation(props);
function close() {
  cancel();
  emit('close');
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
      <div v-if="resumableRequest && !busy" class="assistant-panel__welcome">
        <span>范围已变更。可将上一条输入带回编辑框，检查后重新发送。</span>
        <UiButton @click="reusePreviousRequest">复用上一条输入</UiButton>
      </div>
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
  position: relative;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  display: grid;
  width: 100%;
  grid-template-rows: auto minmax(0, 1fr) auto;
  border-left: 1px solid var(--muyun-support-border);
  background: var(--muyun-support-surface);
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
