import { expect, it } from 'vitest';
import { useRecordDetailController } from '@/dynamic-page-runtime/recordDetailController.ts';

type RecordDetail = { id?: string; title?: string; enabled?: boolean };

it('keeps one existing record detail open when an edit is cancelled', () => {
  const detail = useRecordDetailController<RecordDetail>();
  detail.beginLoad({ id: 'device-1', title: '初始标题' }, 'view');
  detail.resolveLoad({ id: 'device-1', title: '已加载标题' });
  detail.finishLoad();

  expect(detail.beginEdit()).toBe(true);
  detail.draft.value = { ...detail.draft.value, title: '未保存标题' };
  detail.cancelEdit();

  expect(detail.open.value).toBe(true);
  expect(detail.mode.value).toBe('view');
  expect(detail.record.value?.title).toBe('已加载标题');
  expect(detail.draft.value?.title).toBe('已加载标题');
});

it('reopens a closed loaded detail when editing the same record', () => {
  const detail = useRecordDetailController<RecordDetail>();
  detail.beginLoad({ id: 'device-1', title: '已加载标题' }, 'view');
  detail.resolveLoad({ id: 'device-1', title: '已加载标题' });
  detail.finishLoad();
  detail.close();

  expect(detail.open.value).toBe(false);
  expect(detail.beginEdit()).toBe(true);
  expect(detail.open.value).toBe(true);
  expect(detail.mode.value).toBe('edit');
  expect(detail.draft.value?.title).toBe('已加载标题');
});

it('clears an unscoped create draft on cancellation but keeps saved records as the new view state', () => {
  const detail = useRecordDetailController<RecordDetail>();
  detail.beginCreate({ title: '新设备' });
  detail.cancelEdit();

  expect(detail.open.value).toBe(false);
  expect(detail.record.value).toBeUndefined();
  expect(detail.draft.value).toBeUndefined();

  detail.beginLoad({ id: 'device-2', title: '旧设备' }, 'view');
  detail.resolveLoad({ id: 'device-2', title: '旧设备' });
  detail.applySaved({ id: 'device-2', title: '新设备' });

  expect(detail.mode.value).toBe('view');
  expect(detail.record.value?.title).toBe('新设备');
  expect(detail.draft.value?.title).toBe('新设备');
});

it('restores an open detail when a create draft requests restore-view cancellation', () => {
  const detail = useRecordDetailController<RecordDetail>();
  const selected = { id: 'device-3', title: '已选设备' };

  detail.beginLoad(selected, 'view');
  detail.resolveLoad(selected);
  detail.finishLoad();
  detail.beginCreate({ title: '新设备' }, { cancelDestination: 'restore-view' });
  detail.cancelEdit();

  expect(detail.open.value).toBe(true);
  expect(detail.mode.value).toBe('view');
  expect(detail.record.value).toEqual(selected);
  expect(detail.draft.value).toEqual(selected);
});

it('closes a row-initiated edit when it is cancelled', () => {
  const detail = useRecordDetailController<RecordDetail>();
  detail.beginLoad({ id: 'device-4', title: '编辑对象' }, 'edit', { cancelDestination: 'close' });
  detail.resolveLoad({ id: 'device-4', title: '编辑对象' });
  detail.finishLoad();
  detail.cancelEdit();

  expect(detail.open.value).toBe(false);
  expect(detail.mode.value).toBe('view');
  expect(detail.record.value?.title).toBe('编辑对象');
});
