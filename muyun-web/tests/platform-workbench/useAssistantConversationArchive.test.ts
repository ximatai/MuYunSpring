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
