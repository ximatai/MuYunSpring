import { assert, it } from 'vitest';
import {
  applyRecordExternalChange,
  createRecordEditorSessionState,
} from '@/platform-components/recordEditorSessionState.ts';

interface DemoRecord {
  id?: string;
  title: string;
}

it('record editor session closes creation when canceling without selected record', () => {
  const session = createRecordEditorSessionState<DemoRecord, 'view' | 'edit' | 'create'>({
    viewMode: 'view',
    createMode: 'create',
    editMode: 'edit',
    emptyDraft: () => ({ title: '' }),
  });

  session.startCreate();
  session.draft.value.title = '临时记录';
  session.cancel();

  assert.equal(session.mode.value, 'view');
  assert.equal(session.readonly.value, true);
  assert.equal(session.selected.value, undefined);
  assert.deepEqual(session.draft.value, { title: '' });
});

it('record editor session restores selected draft when canceling edit', () => {
  const session = createRecordEditorSessionState<DemoRecord, 'view' | 'edit' | 'create'>({
    viewMode: 'view',
    createMode: 'create',
    editMode: 'edit',
    emptyDraft: () => ({ title: '' }),
  });

  session.select({ id: 'record-1', title: '正式记录' });
  assert.equal(session.startEdit(), true);
  session.draft.value.title = '已修改';
  session.cancel();

  assert.equal(session.mode.value, 'view');
  assert.deepEqual(session.selected.value, { id: 'record-1', title: '正式记录' });
  assert.deepEqual(session.draft.value, { id: 'record-1', title: '正式记录' });
});

it('record editor session can create while preserving selected context', () => {
  const session = createRecordEditorSessionState<DemoRecord, 'view' | 'edit' | 'create'>({
    viewMode: 'view',
    createMode: 'create',
    editMode: 'edit',
    emptyDraft: () => ({ title: '' }),
  });

  session.select({ id: 'record-1', title: '正式记录' });
  session.startCreate({
    preserveSelection: true,
    draft: () => ({ title: '临时记录' }),
  });
  session.cancel();

  assert.equal(session.mode.value, 'view');
  assert.deepEqual(session.selected.value, { id: 'record-1', title: '正式记录' });
  assert.deepEqual(session.draft.value, { id: 'record-1', title: '正式记录' });
});

it('record editor session can create with selected context and custom mode', () => {
  const session = createRecordEditorSessionState<DemoRecord, 'view' | 'edit' | 'create' | 'create-child'>({
    viewMode: 'view',
    createMode: 'create',
    editMode: 'edit',
    emptyDraft: () => ({ title: '' }),
  });

  session.startCreate({
    mode: 'create-child',
    selectedRecord: { id: 'parent-1', title: '父记录' },
    draft: { title: '子记录草稿' },
  });

  assert.equal(session.mode.value, 'create-child');
  assert.deepEqual(session.selected.value, { id: 'parent-1', title: '父记录' });
  assert.deepEqual(session.draft.value, { title: '子记录草稿' });
});

it('record editor session can refresh selected without touching draft or mode', () => {
  const session = createRecordEditorSessionState<DemoRecord, 'view' | 'edit' | 'create'>({
    viewMode: 'view',
    createMode: 'create',
    editMode: 'edit',
    emptyDraft: () => ({ title: '' }),
  });

  session.select({ id: 'record-1', title: '正式记录' });
  session.startEdit();
  session.draft.value.title = '未保存草稿';
  session.replaceSelected({ id: 'record-1', title: '刷新后的记录' });

  assert.equal(session.mode.value, 'edit');
  assert.deepEqual(session.selected.value, { id: 'record-1', title: '刷新后的记录' });
  assert.deepEqual(session.draft.value, { id: 'record-1', title: '未保存草稿' });
});

it('record editor session marks external changes only for the editing selected record', () => {
  const session = createRecordEditorSessionState<DemoRecord, 'view' | 'edit' | 'create'>({
    viewMode: 'view',
    createMode: 'create',
    editMode: 'edit',
    emptyDraft: () => ({ title: '' }),
  });

  session.select({ id: 'record-1', title: '正式记录' });

  assert.equal(session.markExternalRecordChanged('record-1'), false);
  assert.equal(session.externallyChanged.value, false);

  assert.equal(session.startEdit(), true);
  assert.equal(session.markExternalRecordChanged('record-2'), false);
  assert.equal(session.externallyChanged.value, false);

  assert.equal(session.markExternalRecordChanged('record-1'), true);
  assert.equal(session.externallyChanged.value, true);
  assert.equal(session.externalChangedRecordId.value, 'record-1');
});

it('record editor session keeps external change marker while refreshing selected record', () => {
  const session = createRecordEditorSessionState<DemoRecord, 'view' | 'edit' | 'create'>({
    viewMode: 'view',
    createMode: 'create',
    editMode: 'edit',
    emptyDraft: () => ({ title: '' }),
  });

  session.select({ id: 'record-1', title: '正式记录' });
  session.startEdit();
  session.draft.value.title = '未保存草稿';
  session.markExternalRecordChanged('record-1');
  session.replaceSelected({ id: 'record-1', title: '外部更新后的记录' });

  assert.equal(session.externallyChanged.value, true);
  assert.deepEqual(session.selected.value, { id: 'record-1', title: '外部更新后的记录' });
  assert.deepEqual(session.draft.value, { id: 'record-1', title: '未保存草稿' });
});

it('record editor session clears external change marker on explicit editor transitions', () => {
  const session = createRecordEditorSessionState<DemoRecord, 'view' | 'edit' | 'create'>({
    viewMode: 'view',
    createMode: 'create',
    editMode: 'edit',
    emptyDraft: () => ({ title: '' }),
  });

  session.select({ id: 'record-1', title: '正式记录' });
  session.startEdit();
  session.markExternalRecordChanged('record-1');
  session.cancel();
  assert.equal(session.externallyChanged.value, false);

  session.startEdit();
  session.markExternalRecordChanged('record-1');
  session.select({ id: 'record-2', title: '另一条记录' });
  assert.equal(session.externallyChanged.value, false);

  session.startEdit();
  session.markExternalRecordChanged('record-2');
  session.clearSelection();
  assert.equal(session.externallyChanged.value, false);
});

it('record editor session supports custom external change record identity', () => {
  const session = createRecordEditorSessionState<{ code: string; title: string }, 'view' | 'edit' | 'create'>(
    {
      viewMode: 'view',
      createMode: 'create',
      editMode: 'edit',
      emptyDraft: () => ({ code: '', title: '' }),
      recordIdOf: (record) => record.code,
    },
  );

  session.select({ code: 'record-1', title: '正式记录' });
  session.startEdit();

  assert.equal(session.markExternalRecordChanged('record-1'), true);
  assert.equal(session.externallyChanged.value, true);
});

it('record editor session applies matching data changes as external changes', () => {
  const session = createRecordEditorSessionState<DemoRecord, 'view' | 'edit' | 'create'>({
    viewMode: 'view',
    createMode: 'create',
    editMode: 'edit',
    emptyDraft: () => ({ title: '' }),
  });
  session.select({ id: 'record-1', title: '正式记录' });
  session.startEdit();

  assert.equal(
    applyRecordExternalChange(
      session,
      { type: 'record-updated', moduleAlias: 'iam.user', recordId: 'record-2' },
      { moduleAlias: 'iam.user' },
    ),
    false,
  );
  assert.equal(
    applyRecordExternalChange(
      session,
      { type: 'collection-changed', moduleAlias: 'iam.user', recordId: 'record-1' },
      { moduleAlias: 'iam.user' },
    ),
    false,
  );
  assert.equal(
    applyRecordExternalChange(
      session,
      { type: 'record-updated', moduleAlias: 'iam.user', recordId: 'record-1' },
      { moduleAlias: 'iam.user' },
    ),
    true,
  );
  assert.equal(session.externallyChanged.value, true);
});

it('record editor session rejects view or edit mode for create sessions', () => {
  const session = createRecordEditorSessionState<DemoRecord, 'view' | 'edit' | 'create'>({
    viewMode: 'view',
    createMode: 'create',
    editMode: 'edit',
    emptyDraft: () => ({ title: '' }),
  });

  assert.throws(
    () => session.startCreate({ mode: 'edit' }),
    /Record editor create mode cannot be view or edit mode/,
  );
  assert.throws(
    () => session.startCreate({ mode: 'view' }),
    /Record editor create mode cannot be view or edit mode/,
  );
});
