import { assert, it } from 'vitest';
import { ref } from 'vue';
import { AppError, platformErrorCodes } from '@/web-core/index.ts';
import {
  createPlatformActionResultReactionHandlers,
  handlePlatformActionSuccess,
} from '@/platform-components/platformActionResultFeedback.ts';
import {
  executeStaticFormSave,
  executeStaticRecordAction,
} from '@/platform-components/staticFormActionFlow.ts';
import { normalizeRecordDraft } from '@/platform-components/recordDraftNormalizer.ts';

interface TestRecord {
  id?: string;
  version?: number;
  title?: string;
  username?: string;
  enabled?: boolean;
}

it('static form save executes mutation once and calls saved callback', async () => {
  const loading = ref(false);
  const calls: TestRecord[] = [];
  let saved: TestRecord | undefined;

  await executeStaticFormSave<TestRecord>({
    loading,
    mode: 'create',
    canSave: () => true,
    deniedMessage: '无权保存',
    createRecord: () => ({ title: '销售' }),
    save: async (record) => {
      calls.push(record);
      return { record: { ...record, id: 'sales' }, message: '已保存' };
    },
    onSaved: (result) => {
      saved = result.record;
    },
  });

  assert.deepEqual(calls, [{ title: '销售' }]);
  assert.deepEqual(saved, { id: 'sales', title: '销售' });
  assert.equal(loading.value, false);
});

it('record draft normalizer preserves standard fields while overriding normalized fields', () => {
  const draft = {
    id: 'user-1',
    version: 7,
    username: ' admin ',
    enabled: true,
  };

  assert.deepEqual(
    normalizeRecordDraft<TestRecord>(draft, {
      username: draft.username.trim(),
      enabled: false,
    }),
    {
      id: 'user-1',
      version: 7,
      username: 'admin',
      enabled: false,
    },
  );
});

it('static form save dispatches local result reactions', async () => {
  const loading = ref(false);
  const reactions: string[] = [];

  await executeStaticFormSave<TestRecord>({
    loading,
    mode: 'create',
    canSave: () => true,
    deniedMessage: '无权保存',
    createRecord: () => ({ title: '销售' }),
    save: async (record) => ({
      record: { ...record, id: 'sales' },
      message: '已保存',
      reactions: [{ type: 'refresh-list', payload: { moduleAlias: 'iam.employee' } }],
    }),
    onSaved: () => undefined,
    reactionHandlers: {
      'refresh-list': (reaction) => {
        reactions.push(`${reaction.type}:${reaction.payload?.moduleAlias}`);
      },
    },
  });

  assert.deepEqual(reactions, ['refresh-list:iam.employee']);
  assert.equal(loading.value, false);
});

it('static form save lets local action error handlers own matching failures', async () => {
  const loading = ref(false);
  const handled: string[] = [];

  const result = await executeStaticFormSave<TestRecord>({
    loading,
    mode: 'edit',
    canSave: () => true,
    deniedMessage: '无权保存',
    createRecord: () => ({ id: 'emp-1', title: '职员' }),
    save: async () => {
      throw new AppError('record version conflict', {
        code: platformErrorCodes.conflictVersion,
        status: 409,
      });
    },
    onSaved: () => undefined,
    actionErrorHandlers: [
      {
        code: platformErrorCodes.conflictVersion,
        handle: (_error, context) => {
          handled.push(`${context.mode}:${context.record.id}`);
        },
      },
    ],
  });

  assert.equal(result, undefined);
  assert.deepEqual(handled, ['edit:emp-1']);
  assert.equal(loading.value, false);
});

it('platform action result success dispatches standard reaction handlers', async () => {
  const calls: string[] = [];
  const reactionHandlers = createPlatformActionResultReactionHandlers({
    refreshList: (reaction) => {
      calls.push(`${reaction.type}:${reaction.payload?.resourceKey ?? ''}`);
    },
    closeEditor: (reaction) => {
      calls.push(reaction.type);
    },
  });

  await handlePlatformActionSuccess(
    {
      message: '已保存',
      reactions: [{ type: 'refresh-list', payload: { resourceKey: 'employee' } }, { type: 'close-editor' }],
    },
    { reactionHandlers },
  );

  assert.deepEqual(calls, ['refresh-list:employee', 'close-editor']);
});

it('static record action dispatches local result reactions after callback', async () => {
  const loading = ref(false);
  const calls: string[] = [];

  await executeStaticRecordAction<
    TestRecord,
    { message: string; reactions: { type: string; payload?: Record<string, unknown> }[] }
  >({
    loading,
    record: () => ({ id: 'emp-1', title: '职员' }),
    canExecute: () => true,
    deniedMessage: '无权操作',
    execute: async () => ({
      message: '已启用',
      reactions: [{ type: 'refresh-detail', payload: { recordId: 'emp-1' } }],
    }),
    onExecuted: () => {
      calls.push('executed');
    },
    reactionHandlers: {
      'refresh-detail': (reaction) => {
        calls.push(`${reaction.type}:${reaction.payload?.recordId}`);
      },
    },
  });

  assert.deepEqual(calls, ['executed', 'refresh-detail:emp-1']);
  assert.equal(loading.value, false);
});

it('static form save ignores duplicate submit while loading', async () => {
  const loading = ref(false);
  const calls: TestRecord[] = [];
  let releaseSave: ((record: TestRecord) => void) | undefined;

  const firstSave = executeStaticFormSave<TestRecord>({
    loading,
    mode: 'create',
    canSave: () => true,
    deniedMessage: '无权保存',
    createRecord: () => ({ title: '销售' }),
    save: async (record) => {
      calls.push(record);
      return new Promise((resolve) => {
        releaseSave = () => resolve({ record: { ...record, id: 'sales' } });
      });
    },
    onSaved: () => undefined,
  });
  await Promise.resolve();
  const secondSave = executeStaticFormSave<TestRecord>({
    loading,
    mode: 'create',
    canSave: () => true,
    deniedMessage: '无权保存',
    createRecord: () => ({ title: '重复' }),
    save: async (record) => {
      calls.push(record);
      return { record };
    },
    onSaved: () => undefined,
  });

  assert.equal(calls.length, 1);
  releaseSave?.({ title: '销售' });
  await Promise.all([firstSave, secondSave]);
  assert.equal(calls.length, 1);
  assert.equal(loading.value, false);
});

it('static form save stops before mutation when context, permission, or record validation fails', async () => {
  const loading = ref(false);
  let calls = 0;
  const save = async (record: TestRecord) => {
    calls += 1;
    return { record };
  };
  const baseOptions = {
    loading,
    mode: 'create' as const,
    deniedMessage: '无权保存',
    createRecord: () => ({ title: '' }),
    save,
    onSaved: () => undefined,
  };

  await executeStaticFormSave<TestRecord>({
    ...baseOptions,
    validateContext: () => '请先选择机构',
    canSave: () => true,
  });
  await executeStaticFormSave<TestRecord>({
    ...baseOptions,
    canSave: () => false,
  });
  await executeStaticFormSave<TestRecord>({
    ...baseOptions,
    canSave: () => true,
    validateRecord: (record) => (record.title ? undefined : '请填写标题'),
  });

  assert.equal(calls, 0);
  assert.equal(loading.value, false);
});

it('static record action executes action and waits for executed callback', async () => {
  const loading = ref(false);
  const calls: string[] = [];
  let refreshed = false;

  await executeStaticRecordAction<TestRecord, { data: number; message: string; changeSetId: string }>({
    loading,
    record: () => ({ id: 'emp-1', title: '职员' }),
    canExecute: () => true,
    deniedMessage: '无权操作',
    execute: async (record) => {
      calls.push(`execute:${record.id}`);
      return { data: 1, message: '已启用', changeSetId: 'change-1' };
    },
    onExecuted: async (_, record) => {
      await Promise.resolve();
      calls.push(`refresh:${record.id}`);
      refreshed = true;
    },
  });

  assert.deepEqual(calls, ['execute:emp-1', 'refresh:emp-1']);
  assert.equal(refreshed, true);
  assert.equal(loading.value, false);
});

it('static record action ignores duplicate submit while loading', async () => {
  const loading = ref(false);
  const calls: string[] = [];
  let releaseAction: (() => void) | undefined;
  const options = {
    loading,
    record: () => ({ id: 'emp-1', title: '职员' }),
    canExecute: () => true,
    deniedMessage: '无权操作',
    execute: async (record: TestRecord) => {
      calls.push(record.id ?? '');
      return new Promise<number>((resolve) => {
        releaseAction = () => resolve(1);
      });
    },
    onExecuted: () => undefined,
  };

  const firstAction = executeStaticRecordAction(options);
  await Promise.resolve();
  const secondAction = executeStaticRecordAction(options);

  assert.equal(calls.length, 1);
  releaseAction?.();
  await Promise.all([firstAction, secondAction]);
  assert.equal(calls.length, 1);
  assert.equal(loading.value, false);
});

it('static record action stops before execute when record is absent or permission is denied', async () => {
  const loading = ref(false);
  let calls = 0;
  const baseOptions = {
    loading,
    deniedMessage: '无权操作',
    execute: async (record: TestRecord) => {
      calls += 1;
      return { record };
    },
    onExecuted: () => undefined,
  };

  await executeStaticRecordAction<TestRecord, { record: TestRecord }>({
    ...baseOptions,
    record: () => undefined,
    canExecute: () => true,
  });
  await executeStaticRecordAction<TestRecord, { record: TestRecord }>({
    ...baseOptions,
    record: () => ({ id: 'emp-1', title: '职员' }),
    canExecute: () => false,
  });

  assert.equal(calls, 0);
  assert.equal(loading.value, false);
});

it('static record action confirms before execute', async () => {
  const loading = ref(false);
  const calls: string[] = [];
  let confirmed = false;
  const options = {
    loading,
    record: () => ({ id: 'emp-1', title: '职员' }),
    canExecute: () => true,
    deniedMessage: '无权操作',
    confirm: async (record: TestRecord) => {
      calls.push(`confirm:${record.id}`);
      return confirmed;
    },
    execute: async (record: TestRecord) => {
      calls.push(`execute:${record.id}`);
      return 1;
    },
    onExecuted: () => {
      calls.push('executed');
    },
  };

  await executeStaticRecordAction(options);
  assert.deepEqual(calls, ['confirm:emp-1']);
  assert.equal(loading.value, false);

  confirmed = true;
  await executeStaticRecordAction(options);
  assert.deepEqual(calls, ['confirm:emp-1', 'confirm:emp-1', 'execute:emp-1', 'executed']);
  assert.equal(loading.value, false);
});
