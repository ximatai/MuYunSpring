import { ref } from 'vue';
import type {
  AssistantConversationClient,
  AssistantConversationContent,
  AssistantConversationSummary,
} from '@muyun/web-core';

/** Serializes checkpoints; scope generations prevent late reads/writes from replacing another conversation. */
export function useAssistantConversationArchive(
  client: AssistantConversationClient | undefined,
  content: () => AssistantConversationContent,
  restore: (content: AssistantConversationContent) => void,
  clear: () => void,
) {
  const id = ref<string>();
  const title = ref('新对话');
  const status = ref<'idle' | 'saving' | 'saved' | 'unsaved' | 'restored'>('idle');
  const saveError = ref('');
  const readError = ref<
    { kind: 'list'; more: boolean; message: string } | { kind: 'open'; id: string; message: string }
  >();
  const loading = ref(false);
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
    title.value = '新对话';
    status.value = 'idle';
    saveError.value = '';
    readError.value = undefined;
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
    loading.value = false;
  }
  async function save(): Promise<boolean> {
    if (!client) return true;
    if (!scope || loading.value) return false;
    if (saving) {
      const epoch = generation;
      const succeeded = await saving;
      if (epoch !== generation || !succeeded) return false;
      return save();
    }
    const value = content();
    if (!value.messages.some((message) => message.role === 'user')) return true;
    const encoded = JSON.stringify(value);
    if (encoded === saved) return true;
    const epoch = generation,
      key = scope;
    const conversationId = id.value ?? crypto.randomUUID().replaceAll('-', '');
    id.value = conversationId;
    title.value = value.title;
    status.value = 'saving';
    saveError.value = '';
    const pending = (async () => {
      try {
        const result = await client.save(conversationId, key, revision, value);
        if (epoch !== generation) return false;
        revision = result.revision;
        saved = encoded;
        status.value = 'saved';
        return true;
      } catch (cause) {
        if (epoch === generation) {
          status.value = 'unsaved';
          saveError.value = cause instanceof Error ? cause.message : '保存失败，请重试';
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
    loading.value = true;
    readError.value = undefined;
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
        readError.value = {
          kind: 'list',
          more,
          message: cause instanceof Error ? cause.message : '历史会话加载失败',
        };
    } finally {
      if (epoch === generation) loading.value = false;
    }
  }
  async function open(conversationId: string) {
    const beforeSave = generation;
    if (!client || !scope || loading.value || !(await save()) || beforeSave !== generation) return;
    const epoch = ++generation,
      key = scope;
    loading.value = true;
    readError.value = undefined;
    try {
      const result = await client.read(conversationId, key);
      if (epoch !== generation) return;
      id.value = result.id;
      revision = result.revision;
      saved = JSON.stringify(result.content);
      title.value = result.content.title;
      restore(result.content);
      status.value = 'restored';
      historyOpen.value = false;
    } catch (cause) {
      if (epoch === generation)
        readError.value = {
          kind: 'open',
          id: conversationId,
          message: cause instanceof Error ? cause.message : '会话恢复失败',
        };
    } finally {
      if (epoch === generation) loading.value = false;
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
    const epoch = generation;
    if (loading.value || !(await save()) || epoch !== generation) return;
    discardAndStartNew();
  }
  function discardAndStartNew() {
    if (loading.value || saving) return;
    generation++;
    resetRecord();
    historyOpen.value = false;
    clear();
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
