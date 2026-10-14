import { AppError, createLanguageContext } from '@muyun/web-core';
import { describe, expect, it, vi } from 'vitest';
import { useAssistantConversationArchive } from '@/platform-workbench/useAssistantConversationArchive';
import type {
  AssistantConversationClient,
  AssistantConversationContent,
  AssistantConversationSnapshot,
} from '@muyun/web-core';

function fixture() {
  let content: AssistantConversationContent = {
    title: '合同管理',
    messages: [{ role: 'user', text: '记录合同' }],
    history: [],
  };
  const client: AssistantConversationClient = {
    list: vi.fn(async () => []),
    read: vi.fn(),
    save: vi.fn(async (id, _scope, revision, value) => ({
      id,
      revision: revision + 1,
      updatedAt: '2026-09-26',
      content: value,
    })),
  };
  const restore = vi.fn();
  const clear = vi.fn();
  const archive = useAssistantConversationArchive(client, () => content, restore, clear);
  archive.changeScope('tenant-a');
  return {
    archive,
    client,
    restore,
    clear,
    change: (text: string) => {
      content = { ...content, title: text };
    },
  };
}
describe('assistant conversation archive', () => {
  it('checkpoints once, restores history and starts another conversation without deleting it', async () => {
    const { archive, client, restore, clear } = fixture();
    expect(await archive.save()).toBe(true);
    await archive.save();
    expect(client.save).toHaveBeenCalledTimes(1);
    const old = archive.id.value;
    vi.mocked(client.read).mockResolvedValue({
      id: 'saved',
      revision: 5,
      updatedAt: '',
      content: { title: '原来的事', messages: [], history: [] },
    });
    await archive.open('saved');
    expect(restore).toHaveBeenCalledOnce();
    expect(archive.id.value).toBe('saved');
    await archive.startNew();
    expect(clear).toHaveBeenCalledOnce();
    expect(archive.id.value).toBeUndefined();
    expect(old).toBeTruthy();
  });
  it('retains unsaved work and blocks switching on failure', async () => {
    const { archive, client, clear } = fixture();
    vi.mocked(client.save).mockRejectedValue(new Error('offline'));
    await archive.startNew();
    expect(clear).not.toHaveBeenCalled();
    expect(archive.saveError.value).toBe('offline');
    await archive.open('other');
    expect(client.read).not.toHaveBeenCalled();
  });
  it('recovers from synchronous transport failure and saves a conflict copy with a fresh identity', async () => {
    const { archive, client } = fixture();
    vi.mocked(client.save).mockImplementationOnce(() => {
      throw new Error('conflict');
    });
    expect(await archive.save()).toBe(false);
    const original = archive.id.value;
    await archive.saveCopy();
    expect(archive.id.value).not.toBe(original);
    expect(archive.id.value).toHaveLength(32);
    expect(archive.status.value).toBe('saved');
    expect(vi.mocked(client.save).mock.calls.at(-1)?.[2]).toBe(0);
  });
  it('ignores reads and writes arriving after a scope switch', async () => {
    const { archive, client, restore } = fixture();
    let finish!: (result: AssistantConversationSnapshot) => void;
    vi.mocked(client.save).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const pending = archive.save();
    archive.changeScope('tenant-b');
    finish({ id: 'old', revision: 1, updatedAt: '', content: { title: 'old', messages: [], history: [] } });
    expect(await pending).toBe(false);
    expect(archive.id.value).toBeUndefined();
    expect(archive.status.value).toBe('idle');
    vi.mocked(client.save).mockResolvedValue({
      id: 'new',
      revision: 1,
      updatedAt: '',
      content: { title: 'new', messages: [], history: [] },
    });
    vi.mocked(client.read).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const opening = archive.open('history');
    await vi.waitFor(() => expect(client.read).toHaveBeenCalled());
    archive.changeScope('tenant-c');
    finish({
      id: 'history',
      revision: 1,
      updatedAt: '',
      content: { title: 'history', messages: [], history: [] },
    });
    await opening;
    expect(restore).not.toHaveBeenCalled();
  });
});

it.each(['startNew', 'open'] as const)('does not let queued %s cross a scope boundary', async (operation) => {
  const { archive, client, clear, change } = fixture();
  let finish!: (value: AssistantConversationSnapshot) => void;
  vi.mocked(client.save).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const saving = archive.save();
  const pending = operation === 'open' ? archive.open('old-history') : archive.startNew();
  archive.changeScope('tenant-b');
  change('新范围的讨论');
  finish({ id: 'old', revision: 1, updatedAt: '', content: { title: 'old', messages: [], history: [] } });
  await Promise.all([saving, pending]);
  expect(client.save).toHaveBeenCalledTimes(1);
  expect(client.read).not.toHaveBeenCalled();
  expect(clear).not.toHaveBeenCalled();
  expect(archive.id.value).toBeUndefined();
});

it('keeps history read failures separate from save failures and retries the failed read', async () => {
  const { archive, client, change } = fixture();
  await archive.save();
  vi.mocked(client.list).mockRejectedValueOnce(new Error('history offline'));
  await archive.list(true);
  expect(archive.saveError.value).toBe('');
  expect(archive.readError.value?.message).toBe('history offline');
  change('继续讨论');
  expect(await archive.save()).toBe(true);
  expect(archive.readError.value?.message).toBe('history offline');
  await archive.retryRead();
  expect(client.list).toHaveBeenLastCalledWith('tenant-a', 2);
  expect(archive.readError.value).toBeUndefined();
  change('新内容');
  vi.mocked(client.save).mockRejectedValueOnce(new Error('save offline'));
  await archive.save();
  await archive.list();
  expect(archive.saveError.value).toBe('save offline');
});

it('retries the failed conversation read without starting another conversation', async () => {
  const { archive, client, restore } = fixture();
  vi.mocked(client.read)
    .mockRejectedValueOnce(new Error('read offline'))
    .mockResolvedValueOnce({
      id: 'target',
      revision: 1,
      updatedAt: '',
      content: { title: '原来的事', messages: [], history: [] },
    });
  await archive.open('target');
  expect(archive.readError.value?.kind).toBe('open');
  expect(archive.saveError.value).toBe('');
  await archive.retryRead();
  expect(client.read).toHaveBeenLastCalledWith('target', 'tenant-a');
  expect(restore).toHaveBeenCalledOnce();
  expect(archive.readError.value).toBeUndefined();
});

it('does not leave a conversation while a construction confirmation has an unknown result', async () => {
  const clear = vi.fn();
  const read = vi.fn();
  const archive = useAssistantConversationArchive(
    { read, list: vi.fn(), save: vi.fn() },
    () => ({ title: '建设', messages: [], history: [] }),
    vi.fn(),
    clear,
    () => '先查询原确认结果',
  );
  archive.changeScope('scope');
  await archive.open('other');
  await archive.startNew();
  archive.discardAndStartNew();
  expect(read).not.toHaveBeenCalled();
  expect(clear).not.toHaveBeenCalled();
  expect(archive.saveError.value).toBe('先查询原确认结果');
});

it('refreshes default titles and failure explanations in the archive owner language without transport or content changes', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const content: AssistantConversationContent = {
    title: '原始用户标题',
    messages: [{ role: 'user', text: '业务原文' }],
    history: [],
  };
  const save = vi.fn().mockRejectedValue(new AppError('private failure', { code: 'NETWORK_ERROR' }));
  const list = vi.fn().mockRejectedValue(null);
  const client = { save, list, read: vi.fn() } as AssistantConversationClient;
  const archive = useAssistantConversationArchive(
    client,
    () => content,
    vi.fn(),
    vi.fn(),
    undefined,
    language,
  );
  archive.changeScope('own-identity');
  expect(archive.title.value).toBe('New conversation');
  language.setLanguage('zh-CN');
  expect(archive.title.value).toBe('新对话');
  language.setLanguage('en');
  expect(await archive.save()).toBe(false);
  await archive.list();
  expect(archive.saveError.value).toContain('Cannot connect to the service');
  expect(archive.readError.value?.message).toBe('Conversation history could not be loaded');
  const id = archive.id.value;
  language.setLanguage('zh-CN');
  expect(archive.saveError.value).toContain('暂时无法连接服务');
  expect(archive.readError.value?.message).toBe('历史会话加载失败');
  expect(archive.title.value).toBe('原始用户标题');
  expect(archive.id.value).toBe(id);
  expect(save).toHaveBeenCalledOnce();
  expect(list).toHaveBeenCalledOnce();
  expect(content.messages[0]!.text).toBe('业务原文');
});

it('coalesces asynchronous source serialization and discards late snapshots after an identity change', async () => {
  let finish!: (value: AssistantConversationContent) => void;
  let value: AssistantConversationContent = {
    title: '旧身份原文',
    messages: [{ role: 'user', text: '旧需求' }],
    history: [],
  };
  const client: AssistantConversationClient = {
    save: vi.fn(async (id, _scope, revision, content) => ({
      id,
      revision: revision + 1,
      updatedAt: '',
      content,
    })),
    list: vi.fn(),
    read: vi.fn(),
  };
  let serialize = true;
  const content = vi.fn(() =>
    serialize
      ? new Promise<AssistantConversationContent>((resolve) => {
          finish = resolve;
        })
      : value,
  );
  const archive = useAssistantConversationArchive(client, content, vi.fn(), vi.fn());
  archive.changeScope('old-owner');
  const first = archive.save();
  const queued = archive.save();
  expect(content).toHaveBeenCalledOnce();
  archive.changeScope('new-owner');
  serialize = false;
  value = { title: '新身份原文', messages: [{ role: 'user', text: '新需求' }], history: [] };
  expect(await archive.save()).toBe(true);
  const id = archive.id.value;
  finish({ title: '旧身份原文', messages: [{ role: 'user', text: '旧需求' }], history: [] });
  expect(await first).toBe(false);
  expect(await queued).toBe(false);
  expect(archive.id.value).toBe(id);
  expect(archive.title.value).toBe('新身份原文');
  expect(client.save).toHaveBeenCalledOnce();
  expect(vi.mocked(client.save).mock.calls[0]![1]).toBe('new-owner');
});

it.each(['startNew', 'open'] as const)(
  'keeps %s busy through serialization and prevents overlapping conversation transitions',
  async (operation) => {
    let finish!: (value: AssistantConversationContent) => void;
    const value: AssistantConversationContent = {
      title: '原始对话',
      messages: [{ role: 'user', text: '原文' }],
      history: [],
    };
    const client: AssistantConversationClient = {
      save: vi.fn(async (id, _scope, revision, content) => ({
        id,
        revision: revision + 1,
        updatedAt: '',
        content,
      })),
      list: vi.fn(),
      read: vi.fn(async () => ({ id: 'saved', revision: 1, updatedAt: '', content: value })),
    };
    const clear = vi.fn();
    const archive = useAssistantConversationArchive(
      client,
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
      vi.fn(),
      clear,
    );
    archive.changeScope('owner');
    const pending = operation === 'open' ? archive.open('saved') : archive.startNew();
    expect(archive.loading.value).toBe(true);
    await archive.startNew();
    await archive.open('other');
    expect(client.read).not.toHaveBeenCalled();
    finish(value);
    await pending;
    expect(archive.loading.value).toBe(false);
    expect(client.save).toHaveBeenCalledOnce();
    expect(clear).toHaveBeenCalledTimes(operation === 'startNew' ? 1 : 0);
    expect(client.read).toHaveBeenCalledTimes(operation === 'open' ? 1 : 0);
  },
);
