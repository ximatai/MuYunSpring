import {
  platformLanguageContext,
  type LanguageContext,
  normalizeError,
  userFacingErrorMessage,
  createUuid,
} from '@muyun/web-core';
import { computed, ref, shallowRef } from 'vue';
import type {
  AssistantConversationClient,
  AssistantConversationContent,
  AssistantConversationSummary,
} from '@muyun/web-core';

/** Serializes checkpoints; scope generations prevent late reads/writes from replacing another conversation. */
export function useAssistantConversationArchive(
  client: AssistantConversationClient | undefined,
  content: () => AssistantConversationContent | Promise<AssistantConversationContent>,
  restore: (content: AssistantConversationContent) => void | Promise<void>,
  clear: () => void,
  leaveBlockedReason: () => string | undefined = () => undefined,
  language: LanguageContext = platformLanguageContext,
) {
  const id = ref<string>();
  const storedTitle = ref<string>();
  const title = computed(
    () =>
      storedTitle.value ?? language.t('platform.screen.WorkbenchAssistantPanel.new-conversation', '新对话'),
  );
  const status = ref<'idle' | 'saving' | 'saved' | 'unsaved' | 'restored'>('idle');
  const saveFailure = shallowRef<() => string>();
  const saveError = computed(() => saveFailure.value?.() ?? '');
  const readFailure = shallowRef<
    | { kind: 'list'; more: boolean; message: () => string }
    | { kind: 'open'; id: string; message: () => string }
  >();
  const readError = computed(() => {
    const failure = readFailure.value;
    return failure && { ...failure, message: failure.message() };
  });
  const reading = ref(false);
  const transitioning = shallowRef<symbol>();
  const loading = computed(() => reading.value || transitioning.value !== undefined);
  const ready = ref(false);
  const historyOpen = ref(false);
  const entries = ref<AssistantConversationSummary[]>([]);
  const page = ref(1);
  const hasMore = ref(false);
  let scope: string | undefined;
  let generation = 0;
  let revision = 0;
  let saved = '';
  let saving: Promise<boolean> | undefined;

  function resetRecord() {
    id.value = undefined;
    revision = 0;
    saved = '';
    storedTitle.value = undefined;
    status.value = 'idle';
    saveFailure.value = undefined;
    readFailure.value = undefined;
  }
  function changeScope(value: string | undefined) {
    if (scope === value) return;
    scope = value;
    ready.value = Boolean(value);
    generation++;
    resetRecord();
    saving = undefined;
    entries.value = [];
    historyOpen.value = false;
    reading.value = false;
    transitioning.value = undefined;
  }
  async function save(): Promise<boolean> {
    if (!client) return true;
    if (!scope || reading.value) return false;
    if (saving) {
      const epoch = generation;
      const succeeded = await saving;
      if (epoch !== generation || !succeeded) return false;
      return save();
    }
    const epoch = generation,
      key = scope;
    const pending = (async () => {
      try {
        const pendingContent = content();
        const value = 'then' in pendingContent ? await pendingContent : pendingContent;
        if (epoch !== generation || reading.value) return false;
        if (!value.messages.some((message) => message.role === 'user')) return true;
        const encoded = JSON.stringify(value);
        if (encoded === saved) return true;
        const conversationId = id.value ?? createUuid().replaceAll('-', '');
        id.value = conversationId;
        storedTitle.value = value.title;
        status.value = 'saving';
        saveFailure.value = undefined;
        const result = await client.save(conversationId, key, revision, value);
        if (epoch !== generation) return false;
        revision = result.revision;
        saved = encoded;
        status.value = 'saved';
        return true;
      } catch (cause) {
        if (epoch === generation) {
          status.value = 'unsaved';
          saveFailure.value = () =>
            cause instanceof Error
              ? userFacingErrorMessage(normalizeError(cause), language)
              : language.t(
                  'platform.screen.useAssistantConversationArchive.save-failed-please-retry',
                  '保存失败，请重试',
                );
        }
        return false;
      }
    })();
    saving = pending;
    try {
      return await pending;
    } finally {
      if (epoch === generation) saving = undefined;
    }
  }
  async function list(more = false) {
    if (!client || !scope || loading.value) return;
    const epoch = generation,
      key = scope;
    reading.value = true;
    readFailure.value = undefined;
    historyOpen.value = true;
    const next = more ? page.value + 1 : 1;
    try {
      const result = await client.list(key, next);
      if (epoch !== generation) return;
      entries.value = more ? [...entries.value, ...result] : result;
      page.value = next;
      hasMore.value = result.length === 30;
    } catch (cause) {
      if (epoch === generation)
        readFailure.value = {
          kind: 'list',
          more,
          message: () =>
            cause instanceof Error
              ? userFacingErrorMessage(normalizeError(cause), language)
              : language.t(
                  'platform.screen.useAssistantConversationArchive.conversation-history-could-not-be-loaded',
                  '历史会话加载失败',
                ),
        };
    } finally {
      if (epoch === generation) reading.value = false;
    }
  }
  function canLeave() {
    const reason = leaveBlockedReason();
    if (!reason) return true;
    saveFailure.value = () => leaveBlockedReason() ?? reason;
    return false;
  }
  async function open(conversationId: string) {
    if (!client || !scope || loading.value || !canLeave()) return;
    const transition = Symbol('open-conversation');
    transitioning.value = transition;
    const beforeSave = generation;
    try {
      if (!(await save()) || beforeSave !== generation || !canLeave()) return;
      const epoch = ++generation,
        key = scope;
      reading.value = true;
      readFailure.value = undefined;
      try {
        const result = await client.read(conversationId, key);
        if (epoch !== generation) return;
        id.value = result.id;
        revision = result.revision;
        saved = JSON.stringify(result.content);
        storedTitle.value = result.content.title;
        await restore(result.content);
        if (epoch !== generation) return;
        status.value = 'restored';
        historyOpen.value = false;
      } catch (cause) {
        if (epoch === generation)
          readFailure.value = {
            kind: 'open',
            id: conversationId,
            message: () =>
              cause instanceof Error
                ? userFacingErrorMessage(normalizeError(cause), language)
                : language.t(
                    'platform.screen.useAssistantConversationArchive.conversation-could-not-be-restored',
                    '会话恢复失败',
                  ),
          };
      } finally {
        if (epoch === generation) reading.value = false;
      }
    } finally {
      if (transitioning.value === transition) transitioning.value = undefined;
    }
  }
  async function saveCopy() {
    if (loading.value || saving) return;
    generation++;
    id.value = undefined;
    revision = 0;
    saved = '';
    await save();
  }
  async function startNew() {
    if (loading.value || !canLeave()) return;
    const transition = Symbol('new-conversation');
    transitioning.value = transition;
    const epoch = generation;
    try {
      if (!(await save()) || epoch !== generation || !canLeave()) return;
      clearRecordAndConversation();
    } finally {
      if (transitioning.value === transition) transitioning.value = undefined;
    }
  }
  function clearRecordAndConversation() {
    generation++;
    resetRecord();
    historyOpen.value = false;
    clear();
  }
  function discardAndStartNew() {
    if (loading.value || saving || !canLeave()) return;
    clearRecordAndConversation();
  }
  return {
    enabled: Boolean(client),
    id,
    title,
    status,
    saveError,
    readError,
    retryRead() {
      const failed = readError.value;
      if (failed?.kind === 'list') return list(failed.more);
      if (failed?.kind === 'open') return open(failed.id);
    },
    loading,
    ready,
    historyOpen,
    entries,
    hasMore,
    changeScope,
    save,
    list,
    open,
    startNew,
    saveCopy,
    discardAndStartNew,
  };
}
