<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { UiButton, UiIcon, UiTextArea } from '@muyun/vue-ui-antdv';
import type {
  AssistantSurfaceRegistry,
  AssistantConversationClient,
  AssistantConfigurationEditor,
} from '@muyun/web-core';
import ConstructionPlanCard from './ConstructionPlanCard.vue';
import type { ConstructionPlanSession } from './constructionPlanSession';
import AssistantMarkdownContent from './AssistantMarkdownContent.vue';
import AssistantSelectionCard from './AssistantSelectionCard.vue';
import type { ConfigurationCollaboration } from './configurationCollaboration';
import { useAssistantConversation } from './useAssistantConversation';

defineOptions({ name: 'WorkbenchAssistantPanel' });
const props = defineProps<{
  open: boolean;
  registry: AssistantSurfaceRegistry;
  constructionPlan?: ConstructionPlanSession;
  conversationClient?: AssistantConversationClient;
  configurationCollaboration?: ConfigurationCollaboration;
  configurationEditor?: AssistantConfigurationEditor;
}>();
const emit = defineEmits<{ close: [] }>();
const {
  archive,
  restored,
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
  submit,
  cancel,
  abandonSelection,
  selectOption,
  handleKeydown,
  confirmOperation,
  cancelOperation,
} = useAssistantConversation(props);
const {
  title: conversationTitle,
  status: archiveStatus,
  saveError,
  readError,
  loading: archiveLoading,
  ready: archiveReady,
  historyOpen,
  entries: historyEntries,
  hasMore,
} = archive;
const archiveStatusText = computed(
  () =>
    ({
      idle: '尚未开始',
      saving: '正在保存对话…',
      saved: busy.value ? '输入已保存，回复完成后保存记录' : '对话已保存',
      unsaved: '对话尚未保存',
      restored: '历史对话已恢复',
    })[archiveStatus.value],
);
const showEarlierMessages = ref(false);
watch(restoredThroughId, () => {
  showEarlierMessages.value = false;
});
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
      v-if="archive.enabled || configurationCollaboration?.task.value"
      class="assistant-panel__archive"
      aria-label="会话记录"
    >
      <div
        v-if="configurationCollaboration?.task.value"
        class="assistant-panel__configuration"
        aria-label="本次配置协作方式"
      >
        <strong>{{ configurationCollaboration.task.value.goal }}</strong>
        <span>{{
          configurationCollaboration.task.value.mode === 'conversation'
            ? '在对话中准备和确认'
            : '边看配置页面，边在对话中确认'
        }}</span>
        <UiButton
          v-if="
            configurationCollaboration.task.value.mode === 'visual' &&
            configurationEditor?.open &&
            !configurationEditor.visible
          "
          :disabled="busy || archiveLoading"
          @click="configurationEditor.open()"
          >返回配置页</UiButton
        >
      </div>
      <template v-if="archive.enabled">
        <strong v-if="!configurationCollaboration?.task.value">{{ conversationTitle }}</strong>
        <span role="status"
          >{{ archiveLoading ? '正在加载会话…' : archiveStatusText
          }}<template v-if="configurationEditor?.hasUnsavedChanges"> · 配置尚未保存</template></span
        >
        <small>聊天记录保存不代表配置或业务已保存。</small>
        <div class="assistant-panel__archive-actions">
          <UiButton :disabled="busy || archiveLoading || !archiveReady" @click="archive.list()"
            >历史会话</UiButton
          >
          <UiButton :disabled="busy || archiveLoading || !archiveReady" @click="archive.startNew()"
            >新对话</UiButton
          >
        </div>
        <div v-if="saveError" role="alert">
          {{ saveError }}
          <UiButton :disabled="busy || archiveLoading" @click="archive.save()">重试保存</UiButton>
          <UiButton :disabled="busy || archiveLoading" @click="archive.saveCopy()"
            >将当前内容另存为新对话</UiButton
          >
        </div>
        <UiButton v-if="saveError" :disabled="busy || archiveLoading" @click="archive.discardAndStartNew()"
          >放弃未保存的聊天内容并新建</UiButton
        >
        <div v-if="readError" role="alert">
          {{ readError.message }}
          <UiButton :disabled="busy || archiveLoading" @click="archive.retryRead()">重试读取会话</UiButton>
        </div>
        <div v-if="historyOpen" class="assistant-panel__history">
          <span>当前登录身份的历史会话；切换页面可以继续同一个目标。</span>
          <UiButton :disabled="archiveLoading" @click="historyOpen = false">收起历史</UiButton>
          <span v-if="!archiveLoading && !historyEntries.length">暂无已保存会话</span>
          <UiButton
            v-for="entry in historyEntries"
            :key="entry.id"
            :disabled="busy || archiveLoading"
            @click="archive.open(entry.id)"
          >
            {{ entry.title }} · {{ new Date(entry.updatedAt).toLocaleString() }}
          </UiButton>
          <UiButton v-if="hasMore" :disabled="archiveLoading" @click="archive.list(true)">更多会话</UiButton>
        </div>
      </template>
    </section>
    <section
      ref="conversationElement"
      class="assistant-panel__conversation"
      aria-live="polite"
      @scroll="trackConversationScroll"
    >
      <UiButton
        v-if="restoredThroughId"
        :aria-expanded="showEarlierMessages"
        @click="showEarlierMessages = !showEarlierMessages"
        >{{ showEarlierMessages ? '收起之前的对话' : '查看之前的对话' }}</UiButton
      >
      <ConstructionPlanCard
        v-if="
          constructionPlan &&
          (constructionPlan.state.value.planId || constructionPlan.savedPlans.value.length)
        "
        :session="constructionPlan"
        :disabled="busy"
      />
      <div v-if="items.length === 0" class="assistant-panel__welcome">
        <strong>我可以帮你操作当前工作区</strong>
        <span>例如：梳理订单管理的本期范围，或填写当前表单。</span>
      </div>
      <article
        v-for="item in items"
        :key="item.id"
        v-show="item.id > restoredThroughId || showEarlierMessages"
        class="assistant-message"
        :class="`assistant-message--${item.role}`"
      >
        <template v-if="item.role === 'assistant'">
          <AssistantMarkdownContent v-if="item.text" :content="item.text" />
          <section
            v-if="item.confirmation"
            class="assistant-panel__welcome"
            :aria-label="item.confirmation.presentation.title"
          >
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
            <span v-else-if="item.confirmationState === 'expired'"
              >内容或操作对象已变化，请重新核对并确认最新内容。</span
            >
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
          <details v-if="item.details">
            <summary>{{ item.details.title }}</summary>
            <p v-for="(line, index) in item.details.lines" :key="index">{{ line }}</p>
          </details>
        </template>
        <details v-if="item.diagnostic">
          <summary>查看诊断信息</summary>
          {{ item.diagnostic }}
        </details>
      </article>
      <div v-if="busy" class="assistant-panel__working">{{ activityText }}</div>
    </section>

    <footer class="assistant-panel__composer">
      <div v-if="restored" class="assistant-panel__welcome" aria-label="继续会话">
        <strong>讨论已保留，可以继续描述要做的事。</strong>
        <span v-if="restoredRequest" class="assistant-panel__last-request"
          >上次提出的需求：{{ restoredRequest }}</span
        >
        <span v-if="configurationEditor?.hasUnsavedChanges"
          >当前工作区仍有「{{
            configurationEditor.title
          }}」的未保存配置。继续处理时会核实是否属于这次任务。</span
        >
        <span>已完成事项会先核实结果；未完成目标会核实后接续。历史记录不会恢复旧确认授权。</span>
        <div class="assistant-panel__archive-actions">
          <UiButton
            :disabled="busy || archiveLoading || Boolean(draft.trim()) || !registry.snapshot()"
            @click="continueConversation"
            >继续处理</UiButton
          >
          <UiButton :disabled="busy || archiveLoading || Boolean(draft.trim())" @click="adjustRequest"
            >调整需求</UiButton
          >
        </div>
        <small>继续处理会先核实进度并建议下一步，保存仍需重新确认。</small>
      </div>
      <div v-if="recoveryRequest && !busy" class="assistant-panel__welcome">
        <span
          >本轮已暂停。可点击下方“{{
            recoveryReadOnly ? '继续核实' : '继续处理'
          }}”核对当前状态和剩余事项，或从页面接手操作。</span
        >
        <UiButton :disabled="archiveLoading || Boolean(draft.trim())" @click="continueConversation">{{
          recoveryReadOnly ? '继续核实' : '继续处理'
        }}</UiButton>
        <UiButton :disabled="archiveLoading || Boolean(draft.trim())" @click="adjustRequest"
          >调整需求</UiButton
        >
      </div>
      <UiButton v-if="hasNewReply && !followLatest" @click="showLatest">查看最新回复</UiButton>
      <UiTextArea
        v-model:value="draft"
        :rows="3"
        :maxlength="4000"
        :disabled="busy || archiveLoading"
        :placeholder="activeRequiredSelection ? '可以选择上方选项，也可以补充说明' : '描述你想完成的事情'"
        @keydown="handleKeydown"
      />
      <div class="assistant-panel__actions">
        <span>Enter 发送，Shift + Enter 换行</span>
        <span v-if="operationPending">正在核实操作结果</span>
        <UiButton v-else-if="busy" @click="cancel">停止</UiButton>
        <UiButton v-else type="primary" :disabled="!draft.trim() || !registry.snapshot()" @click="submit">
          发送
        </UiButton>
      </div>
    </footer>
  </aside>
</template>

<style scoped>
.assistant-panel__archive {
  min-height: 0;
  max-height: 40vh;
  overflow: auto;
  display: grid;
  gap: 6px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--muyun-support-border);
}
.assistant-panel__configuration {
  display: grid;
  gap: 4px;
}
.assistant-panel__archive-actions {
  display: flex;
  gap: 8px;
}
.assistant-panel__history {
  display: grid;
  gap: 6px;
  max-height: 220px;
  overflow: auto;
}
.assistant-panel__history :deep(button) {
  white-space: normal;
  height: auto;
  text-align: left;
}

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

.assistant-panel:has(> .assistant-panel__archive) {
  grid-template-rows: auto auto minmax(0, 1fr) auto;
}
.assistant-panel__archive > strong,
.assistant-panel__configuration > strong {
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow-wrap: anywhere;
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

.assistant-panel__last-request {
  max-height: 4.8em;
  overflow: auto;
  white-space: pre-wrap;
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
