<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';
import { UiButton, UiIcon, UiTextArea } from '@muyun/vue-ui-antdv';
import type { AssistantSurfaceRegistry } from '@muyun/web-core';
import ConstructionPlanCard from './ConstructionPlanCard.vue';
import type { ConstructionPlanSession } from './constructionPlanSession';
import AssistantMarkdownContent from './AssistantMarkdownContent.vue';
import AssistantSelectionCard from './AssistantSelectionCard.vue';
import { useAssistantConversation } from './useAssistantConversation';

defineOptions({ name: 'WorkbenchAssistantPanel' });
const props = defineProps<{
  open: boolean;
  registry: AssistantSurfaceRegistry;
  constructionPlan?: ConstructionPlanSession;
}>();
const emit = defineEmits<{ close: [] }>();
const {
  draft,
  resumableRequest,
  interruptedRequest,
  reuseInterruptedRequest,
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
} = useAssistantConversation(props);
const conversationElement = ref<HTMLElement>();
const followLatest = ref(true);
const hasNewReply = ref(false);
function trackConversationScroll() {
  const element = conversationElement.value;
  if (!element) return;
  followLatest.value = element.scrollHeight - element.scrollTop - element.clientHeight < 48;
  if (followLatest.value) hasNewReply.value = false;
}
function showLatest() {
  followLatest.value = true;
  hasNewReply.value = false;
  const element = conversationElement.value;
  if (element) element.scrollTop = element.scrollHeight;
}
watch(
  () => [items.value.length, items.value.at(-1)?.text, busy.value, operationPending.value],
  async () => {
    await nextTick();
    if (followLatest.value) showLatest();
    else hasNewReply.value = true;
  },
);
watch(busy, (active) => {
  if (active) followLatest.value = true;
});
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

    <section
      ref="conversationElement"
      class="assistant-panel__conversation"
      aria-live="polite"
      @scroll="trackConversationScroll"
    >
      <ConstructionPlanCard v-if="constructionPlan" :session="constructionPlan" :disabled="busy" />
      <div v-if="items.length === 0" class="assistant-panel__welcome">
        <strong>我可以帮你操作当前工作区</strong>
        <span>例如：梳理订单管理的本期范围，或填写当前表单。</span>
      </div>
      <article
        v-for="item in items"
        :key="item.id"
        class="assistant-message"
        :class="`assistant-message--${item.role}`"
      >
        <template v-if="item.role === 'assistant'">
          <AssistantMarkdownContent v-if="item.text" :content="item.text" />
          <section v-if="item.confirmation" class="assistant-panel__welcome" aria-label="保存确认">
            <strong>{{ item.confirmation.presentation.title }}</strong>
            <span v-for="(line, index) in item.confirmation.presentation.lines" :key="index">{{ line }}</span>
            <details v-if="item.confirmation.presentation.details" class="assistant-confirmation__details">
              <summary>{{ item.confirmation.presentation.details.title }}</summary>
              <p v-for="(line, index) in item.confirmation.presentation.details.lines" :key="index">
                {{ line }}
              </p>
            </details>
            <template v-if="item.confirmationState === 'pending'">
              <UiButton type="primary" :disabled="busy" @click="confirmOperation(item)">{{
                item.confirmation.confirmLabel
              }}</UiButton>
              <UiButton :disabled="busy" @click="cancelOperation(item)">继续修改或取消</UiButton>
            </template>
            <template v-else-if="item.confirmationState === 'unknown'">
              <span>操作结果尚不确定，请先查询结果；不会自动重复提交。</span>
              <UiButton :disabled="busy" @click="confirmOperation(item, true)">查询操作结果</UiButton>
            </template>
            <span v-else-if="item.confirmationState === 'executing' || item.confirmationState === 'checking'"
              >正在核实操作结果…</span
            >
            <span v-else-if="item.confirmationState === 'succeeded'">已完成</span>
            <template v-else-if="item.confirmationState === 'rejected'">
              <div role="alert">
                <strong>操作未提交，已准备的内容保留。</strong>
                <p v-for="(line, index) in item.confirmation.result?.lines" :key="index">{{ line }}</p>
              </div>
              <UiButton type="primary" :disabled="busy" @click="confirmOperation(item)"
                >重试本次确认</UiButton
              >
              <UiButton :disabled="busy" @click="cancelOperation(item)">继续修改或取消</UiButton>
            </template>
            <span v-else-if="item.confirmationState === 'expired'">内容或范围已变化，请重新准备确认。</span>
            <span v-else>已取消本次确认，草稿保留。</span>
          </section>
          <AssistantSelectionCard
            v-if="item.selection"
            :selection="item.selection.value"
            :state="item.selection.state"
            :selected-option-id="item.selection.selectedOptionId"
            @select="(option) => selectOption(item, option)"
            @abandon="abandonSelection(item)"
          />
        </template>
        <template v-else>
          {{ item.text }}
          <details v-if="item.diagnostic">
            <summary>查看诊断信息</summary>
            {{ item.diagnostic }}
          </details>
        </template>
      </article>
      <div v-if="interruptedRequest && !busy" class="assistant-panel__welcome">
        <span>已有内容保留。带回原请求后可补充说明，再继续处理；不会自动重做保存。</span>
        <UiButton @click="reuseInterruptedRequest">带回这条请求</UiButton>
      </div>
      <div v-if="resumableRequest && !busy" class="assistant-panel__welcome">
        <span>范围已变更。可将上一条输入带回编辑框，检查后重新发送。</span>
        <UiButton @click="reusePreviousRequest">复用上一条输入</UiButton>
      </div>
      <div v-if="busy" class="assistant-panel__working">{{ activityText }}</div>
    </section>

    <footer class="assistant-panel__composer">
      <UiButton v-if="hasNewReply && !followLatest" @click="showLatest">查看最新回复</UiButton>
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
        <span v-if="operationPending">正在核实操作结果</span>
        <UiButton v-else-if="busy" @click="cancel">停止</UiButton>
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

<style scoped>
.assistant-message:has(.assistant-confirmation__details) {
  max-width: 100%;
}
.assistant-confirmation__details {
  margin: 8px 0;
  font-size: 13px;
}
.assistant-confirmation__details summary {
  cursor: pointer;
  font-weight: 600;
}
.assistant-confirmation__details p {
  margin: 8px 0;
}
.assistant-panel__welcome:has(.assistant-confirmation__details) > span {
  color: var(--muyun-support-text);
  font-size: 14px;
}
</style>
