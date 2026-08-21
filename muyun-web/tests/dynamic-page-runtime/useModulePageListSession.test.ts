import { ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { useModulePageListSession } from '@/dynamic-page-runtime/composables/useModulePageListSession.ts';

function createSession() {
  const selectedRecord = ref<{ id?: string; title?: string; rendererType?: string }>();
  const saving = ref(false);
  const resetDetail = vi.fn();
  const invalidateDetailLoad = vi.fn();
  const resetTreeSelection = vi.fn();
  const openRecord = vi.fn();
  const openRecycleBinRecord = vi.fn();
  const session = useModulePageListSession({
    selectedRecord,
    saving,
    resetDetail,
    invalidateDetailLoad,
    resetTreeSelection,
    openRecord,
    openRecycleBinRecord,
  });
  return {
    session,
    selectedRecord,
    saving,
    resetDetail,
    invalidateDetailLoad,
    resetTreeSelection,
    openRecord,
    openRecycleBinRecord,
  };
}

describe('module page list session', () => {
  it('owns active-list snapshots while preserving the loaded detail projection', () => {
    const { session, selectedRecord } = createSession();
    selectedRecord.value = { id: '1', title: '详情字段', rendererType: 'TEXTAREA' };

    session.handleLoaded([{ id: '1', title: '列表字段' }]);

    expect(session.cardAssistantRecords.value).toEqual([{ id: '1', title: '列表字段' }]);
    expect(selectedRecord.value).toEqual({ id: '1', title: '列表字段', rendererType: 'TEXTAREA' });
    session.listMode.value = 'recycleBin';
    session.handleLoaded([{ id: '2', title: '已删除' }]);
    expect(session.cardAssistantRecords.value).toEqual([{ id: '1', title: '列表字段' }]);
  });

  it('resets detail state on a mode transition and sends recycle-bin selections to its supplied detail transition', () => {
    const {
      session,
      selectedRecord,
      resetDetail,
      invalidateDetailLoad,
      resetTreeSelection,
      openRecycleBinRecord,
    } = createSession();

    session.handleListModeChange('recycleBin');
    expect(session.listMode.value).toBe('recycleBin');
    expect(selectedRecord.value).toBeUndefined();
    expect(invalidateDetailLoad).toHaveBeenCalledOnce();
    expect(resetDetail).toHaveBeenCalledOnce();
    expect(resetTreeSelection).toHaveBeenCalledOnce();

    const record = { id: 'deleted-1' };
    session.selectListDetailRecord(record, false);
    expect(openRecycleBinRecord).toHaveBeenCalledWith(record);
  });

  it('does not import request clients or authorization runtime', async () => {
    const { readFile } = await import('node:fs/promises');
    const source = await readFile(
      new URL('../../src/dynamic-page-runtime/composables/useModulePageListSession.ts', import.meta.url),
      'utf8',
    );
    expect(source).not.toMatch(/from ['"]@muyun\/web-core['"]|\.can\(|request\(/);
  });
});
