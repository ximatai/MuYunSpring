<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { UiButton, UiIcon, UiTextArea } from '@muyun/vue-ui-antdv';
import type { AssistantSurfaceRegistry, AssistantConversationClient } from '@muyun/web-core';
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
  conversationClient?: AssistantConversationClient;
}>();
const emit = defineEmits<{ close: [] }>();
const {
  archive,
  restored,
  linkedPlanId,
  draft,
  restoredThroughId,
  restoredRequest,
  recoveryRequest,
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
const planRestoreError = ref('');
const planRestoring = ref(false);
let planRestoreEpoch = 0;
watch([linkedPlanId, archive.id], () => {
  planRestoreEpoch++;
  planRestoreError.value = '';
  planRestoring.value = false;
});
async function restoreLinkedPlan() {
  if (planRestoring.value || !linkedPlanId.value || !props.constructionPlan || props.constructionPlan.dirty())
    return;
  const epoch = planRestoreEpoch;
  planRestoreError.value = '';
  planRestoring.value = true;
  try {
    await props.constructionPlan.restore(linkedPlanId.value);
  } catch {
    if (epoch === planRestoreEpoch)
      planRestoreError.value = '关联方案暂时无法恢复，请检查当前身份和方案状态。';
  } finally {
    if (epoch === planRestoreEpoch) planRestoring.value = false;
  }
}
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

    <section v-if="archive.enabled" class="assistant-panel__archive" aria-label="会话记录">
      <strong>{{ conversationTitle }}</strong>
      <span role="status">{{ archiveLoading ? '正在加载会话…' : archiveStatusText }}</span>
      <small>当前身份和业务范围内保存；聊天保存不代表业务已保存。</small>
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
        <span>当前范围的历史会话；切换回原业务范围可找回此前对话。</span>
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
      <ConstructionPlanCard v-if="constructionPlan" :session="constructionPlan" :disabled="busy" />
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
      <div v-if="busy" class="assistant-panel__working">{{ activityText }}</div>
    </section>

    <footer class="assistant-panel__composer">
      <div v-if="restored" class="assistant-panel__welcome" aria-label="继续会话">
        <strong>需求和讨论已保留，可以接着处理。</strong>
        <span v-if="restoredRequest" class="assistant-panel__last-request"
          >上次提出的需求：{{ restoredRequest }}</span
        >
        <span>未保存草稿和旧确认按钮没有恢复。继续操作时会重新读取当前业务状态。</span>
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
        <UiButton
          v-if="linkedPlanId && constructionPlan"
          :disabled="busy || planRestoring || constructionPlan.dirty()"
          @click="restoreLinkedPlan"
          >{{ planRestoring ? '正在读取关联方案…' : '查看关联的已保存建设方案' }}</UiButton
        >
        <span v-if="planRestoreError" role="alert">{{ planRestoreError }}</span>
        <span v-if="linkedPlanId && constructionPlan?.dirty()"
          >当前建设方案有未确认修改，请先处理后再切换方案。</span
        >
      </div>
      <div v-if="recoveryRequest && !busy" class="assistant-panel__welcome">
        <span>本轮已暂停。已有内容保留，可调整需求后继续；切换业务范围后请核对操作对象。</span>
        <UiButton :disabled="archiveLoading || Boolean(draft.trim())" @click="adjustRequest"
          >调整需求</UiButton
        >
      </div>
      <UiButton v-if="hasNewReply && !followLatest" @click="showLatest">查看最新回复</UiButton>
      <UiTextArea
        v-model:value="draft"
        :rows="3"
        :maxlength="4000"
        :disabled="busy || archiveLoading || Boolean(activeRequiredSelection)"
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
.assistant-panel__archive {
  min-height: 0;
  max-height: 40vh;
  overflow: auto;
  display: grid;
  gap: 6px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--muyun-support-border);
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
.assistant-panel__archive > strong {
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
