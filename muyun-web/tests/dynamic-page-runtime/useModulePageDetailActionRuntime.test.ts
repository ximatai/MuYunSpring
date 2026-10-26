import { AppError } from '@muyun/web-core';
import { effectScope, ref, type Ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { useModulePageDetailActionRuntime } from '@/dynamic-page-runtime/composables/useModulePageDetailActionRuntime.ts';

function createRuntime(localEditValid?: Ref<boolean>, refreshDetail = false, readOnlyTitle = false) {
  const request = vi.fn().mockResolvedValue({ message: '已提交' });
  const view = vi.fn().mockResolvedValue({ id: 'record-1', version: 2, title: '已刷新' });
  const resolveLoad = vi.fn();
  const refreshList = vi.fn();
  const presentSuccess = vi.fn();
  const presentError = vi.fn();
  const reportRefreshFailure = vi.fn();
  const selectedRecord = ref({
    id: 'record-1',
    version: 1,
    title: '旧标题',
    amount: '0.123456789012345678',
    hidden: '不提交',
  });
  const runtime = useModulePageDetailActionRuntime({
    context: {
      moduleAlias: 'crm.customer',
      runtimeAction: (code: string) => ({ title: `动作:${code}` }),
      http: { request },
      crud: { view },
    } as never,
    pageBootstrap: ref({
      resolvedConfig: {
        actionBlocks: [
          {
            type: 'localEdit',
            actionCode: 'rename',
            title: '改名',
            submitPath: '/crm.customer/rename/{recordId}',
            refreshStrategy: { detail: refreshDetail, list: true },
            localEditForm: {
              uiConfigId: 'rename-form',
              fieldUiControls: [
                {
                  alias: 'date',
                  rendererType: 'DATE',
                  valueShape: 'SCALAR',
                },
              ],
              submitContract: {
                recordRequired: true,
                recordVersionRequired: true,
                fieldNamesRequired: true,
                uiConfigIdPayloadKey: 'uiConfigId',
              },
              fields: [
                {
                  fieldName: 'title',
                  fieldTitle: '名称',
                  readOnly: readOnlyTitle,
                  fieldUiControlAlias: 'date',
                  valueType: 'LONG',
                },
                {
                  fieldName: 'amount',
                  fieldTitle: '金额',
                  fieldUiControlAlias: 'date',
                  valueType: 'DECIMAL',
                },
                { fieldName: 'hidden', visible: false },
              ],
            },
          },
        ],
      },
    } as never),
    selectedRecord: selectedRecord as never,
    editorMode: ref<'create' | 'edit' | 'view'>('view'),
    localEditValid,
    detail: { resolveLoad },
    refreshList,
    presentSuccess,
    presentError,
    reportRefreshFailure,
  });
  return {
    runtime,
    selectedRecord,
    request,
    view,
    resolveLoad,
    refreshList,
    presentSuccess,
    presentError,
    reportRefreshFailure,
  };
}

describe('module page detail action runtime', () => {
  it('submits only declared local-edit fields and honours its independent refresh policy', async () => {
    const { runtime, request, view, resolveLoad, refreshList, presentSuccess } = createRuntime();
    runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);
    runtime.localEditDraft.value!.title = '新名称';

    await runtime.submitLocalEdit();

    expect(request).toHaveBeenCalledWith({
      method: 'POST',
      path: '/crm.customer/rename/record-1',
      body: {
        recordId: 'record-1',
        record: {
          id: 'record-1',
          version: 1,
          values: { title: '新名称', amount: '0.123456789012345678' },
        },
        fieldNames: ['title', 'amount'],
        payload: { uiConfigId: 'rename-form' },
      },
    });
    expect(view).not.toHaveBeenCalled();
    expect(resolveLoad).not.toHaveBeenCalled();
    expect(refreshList).toHaveBeenCalledOnce();
    expect(presentSuccess).toHaveBeenCalledWith({ message: '已提交' }, '改名成功', 'module-local-edit');
  });

  it('binds the published resolved control to each local-edit field instead of re-inferring uiType', () => {
    const { runtime } = createRuntime();
    runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);

    expect(runtime.localEditFields.value.get('title')).toMatchObject({
      uiType: 'date',
      fieldControl: { alias: 'date', rendererType: 'DATE', valueShape: 'SCALAR' },
      valueType: 'LONG',
    });
  });

  it('tracks a local-edit draft against its opening baseline and clears after reverting or dismissal', () => {
    const { runtime } = createRuntime();
    runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);

    expect(runtime.localEditDirty.value).toBe(false);
    runtime.localEditDraft.value!.title = '新名称';
    expect(runtime.localEditDirty.value).toBe(true);

    runtime.localEditDraft.value!.title = '旧标题';
    expect(runtime.localEditDirty.value).toBe(false);

    runtime.localEditDraft.value!.title = '再次修改';
    runtime.dismissLocalEdit();
    expect(runtime.localEditOpen.value).toBe(false);
    expect(runtime.localEditDirty.value).toBe(false);
  });

  it('preserves canonical LONG and DECIMAL editor strings in local-edit payloads', async () => {
    const { runtime, request } = createRuntime();
    runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);
    runtime.localEditDraft.value!.title = '9007199254740993';
    runtime.localEditDraft.value!.amount = '9999999999999999.99';

    await runtime.submitLocalEdit();

    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({
        body: expect.objectContaining({
          record: expect.objectContaining({
            values: { title: '9007199254740993', amount: '9999999999999999.99' },
          }),
        }),
      }),
    );
  });

  it('keeps failed action effects inside the runtime boundary', async () => {
    const { runtime, request, presentError } = createRuntime();
    request.mockRejectedValueOnce(new Error('拒绝'));
    runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);

    await runtime.submitLocalEdit();

    expect(presentError).toHaveBeenCalledWith(expect.any(Error), 'module-local-edit');
    expect(runtime.localEditSaving.value).toBe(false);
  });

  it('preserves a successful local edit when its requested detail refresh fails', async () => {
    const { runtime, view, presentSuccess, presentError, reportRefreshFailure } = createRuntime(
      undefined,
      true,
    );
    const refreshFailure = new Error('详情暂时不可读');
    view.mockRejectedValueOnce(refreshFailure);
    runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);

    await runtime.submitLocalEdit();

    expect(presentSuccess).toHaveBeenCalledWith({ message: '已提交' }, '改名成功', 'module-local-edit');
    expect(reportRefreshFailure).toHaveBeenCalledWith(refreshFailure, 'module-local-edit');
    expect(presentError).not.toHaveBeenCalled();
    expect(runtime.localEditOpen.value).toBe(false);
  });

  it('does not issue local-edit HTTP while the mounted form reports invalid, then submits after correction', async () => {
    const localEditValid = ref(false);
    const { runtime, request } = createRuntime(localEditValid);
    runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);

    await runtime.submitLocalEdit();
    expect(request).not.toHaveBeenCalled();

    localEditValid.value = true;
    await runtime.submitLocalEdit();
    expect(request).toHaveBeenCalledOnce();
  });
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

it.each([400, 409, 422])(
  'retains a %s rejection in the draft and clears only edited field feedback',
  async (status) => {
    const { runtime, request, presentError, presentSuccess } = createRuntime();
    runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);
    request.mockRejectedValueOnce(
      new AppError('字段被拒绝', {
        status,
        targets: [
          { kind: 'field', fieldName: 'title' },
          { kind: 'field', fieldName: 'amount' },
        ],
      }),
    );
    await runtime.submitLocalEdit();
    expect(runtime.localEditOpen.value).toBe(true);
    expect(runtime.localEditDraft.value!.title).toBe('旧标题');
    expect(runtime.localEditFieldErrors.value).toEqual({ title: '字段被拒绝', amount: '字段被拒绝' });
    expect(runtime.localEditErrorMessage.value).toBeUndefined();
    expect(presentError).not.toHaveBeenCalled();
    expect(presentSuccess).not.toHaveBeenCalled();
    runtime.localEditDraft.value!.title = '新名称';
    expect(runtime.localEditFieldErrors.value).toEqual({ amount: '字段被拒绝' });
    runtime.localEditDraft.value!.title = '旧标题';
    expect(runtime.localEditFieldErrors.value).toEqual({ amount: '字段被拒绝' });
  },
);

it.each(
  [
    [],
    [{ kind: 'field', fieldName: 'missing' }],
    [{ kind: 'field', fieldName: 'title', relationAlias: 'children', rowIndex: 0 }],
  ].map((targets) => ({ targets })),
)('falls back to one form message for an unlocatable rejection: %j', async ({ targets }) => {
  const { runtime, request, presentError } = createRuntime();
  runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);
  request.mockRejectedValueOnce(new AppError('整体拒绝', { status: 422, targets }));
  await runtime.submitLocalEdit();
  expect(runtime.localEditFieldErrors.value).toEqual({});
  expect(runtime.localEditErrorMessage.value).toBe('整体拒绝');
  expect(presentError).not.toHaveBeenCalled();
  runtime.localEditDraft.value!.title = '新名称';
  expect(runtime.localEditErrorMessage.value).toBeUndefined();
});

it.each(['switch', 'close', 'dispose'] as const)(
  'ignores a late rejection after %s, including a reopened form',
  async (change) => {
    const scope = effectScope();
    const { runtime, request, selectedRecord, presentError } = scope.run(() => createRuntime())!;
    const gate = deferred<unknown>();
    request.mockReturnValueOnce(gate.promise);
    runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);
    const saving = runtime.submitLocalEdit();
    if (change === 'switch') selectedRecord.value = { ...selectedRecord.value, id: 'record-2' };
    else if (change === 'close') runtime.localEditOpen.value = false;
    else scope.stop();
    expect(runtime.localEditOpen.value).toBe(false);
    if (change !== 'dispose') runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);
    gate.reject(new AppError('旧拒绝', { status: 409, targets: [{ kind: 'field', fieldName: 'title' }] }));
    await saving;
    expect(runtime.localEditFieldErrors.value).toEqual({});
    expect(runtime.localEditErrorMessage.value).toBeUndefined();
    expect(presentError).not.toHaveBeenCalled();
    scope.stop();
  },
);

it.each(['write', 'read'] as const)('ignores a late successful %s after record switching', async (phase) => {
  const {
    runtime,
    request,
    view,
    selectedRecord,
    resolveLoad,
    presentSuccess,
    refreshList,
    reportRefreshFailure,
  } = createRuntime(undefined, true);
  const gate = deferred<unknown>();
  if (phase === 'write') request.mockReturnValueOnce(gate.promise);
  else view.mockReturnValueOnce(gate.promise);
  runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);
  const saving = runtime.submitLocalEdit();
  await Promise.resolve();
  selectedRecord.value = { ...selectedRecord.value, id: 'record-2' };
  gate.resolve({ id: 'record-1', version: 2 });
  await saving;
  expect(resolveLoad).not.toHaveBeenCalled();
  expect(presentSuccess).not.toHaveBeenCalled();
  expect(refreshList).not.toHaveBeenCalled();
  expect(reportRefreshFailure).not.toHaveBeenCalled();
});

it('prevents duplicate submissions and ignores a rejection for a modified in-flight draft', async () => {
  const { runtime, request, presentError } = createRuntime();
  const gate = deferred<unknown>();
  request.mockReturnValueOnce(gate.promise);
  runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);
  const saving = runtime.submitLocalEdit();
  await runtime.submitLocalEdit();
  expect(request).toHaveBeenCalledOnce();
  runtime.localEditDraft.value!.title = 'new draft';
  gate.reject(new AppError('旧拒绝', { status: 400 }));
  await saving;
  expect(runtime.localEditErrorMessage.value).toBeUndefined();
  expect(presentError).not.toHaveBeenCalled();
});

it.each(['switch', 'close'] as const)(
  'ignores a late refresh failure after %s without reporting a failed save',
  async (change) => {
    const { runtime, view, selectedRecord, resolveLoad, presentSuccess, presentError, reportRefreshFailure } =
      createRuntime(undefined, true);
    const gate = deferred<unknown>();
    view.mockReturnValueOnce(gate.promise);
    runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);
    const saving = runtime.submitLocalEdit();
    await Promise.resolve();
    expect(view).toHaveBeenCalledOnce();
    if (change === 'switch') selectedRecord.value = { ...selectedRecord.value, id: 'record-2' };
    else runtime.localEditOpen.value = false;
    runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);
    gate.reject(new AppError('旧详情不可读', { status: 422 }));
    await saving;
    expect(runtime.localEditOpen.value).toBe(true);
    expect(runtime.localEditSaving.value).toBe(false);
    expect(runtime.localEditFieldErrors.value).toEqual({});
    expect(runtime.localEditErrorMessage.value).toBeUndefined();
    expect(resolveLoad).not.toHaveBeenCalled();
    expect(presentSuccess).not.toHaveBeenCalled();
    expect(presentError).not.toHaveBeenCalled();
    expect(reportRefreshFailure).not.toHaveBeenCalled();
  },
);

it('does not unlock a new submission when an older session finishes', async () => {
  const { runtime, request, presentError, presentSuccess } = createRuntime();
  const oldRequest = deferred<unknown>();
  const newRequest = deferred<unknown>();
  request.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise);
  runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);
  const oldSaving = runtime.submitLocalEdit();
  runtime.localEditOpen.value = false;
  runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);
  runtime.localEditDraft.value!.title = '新会话草稿';
  const newSaving = runtime.submitLocalEdit();
  oldRequest.reject(new AppError('旧拒绝', { status: 409 }));
  await oldSaving;
  expect(runtime.localEditSaving.value).toBe(true);
  expect(runtime.localEditDraft.value!.title).toBe('新会话草稿');
  expect(runtime.localEditErrorMessage.value).toBeUndefined();
  expect(presentError).not.toHaveBeenCalled();
  newRequest.resolve({ message: '新提交成功' });
  await newSaving;
  expect(runtime.localEditSaving.value).toBe(false);
  expect(runtime.localEditOpen.value).toBe(false);
  expect(presentSuccess).toHaveBeenCalledOnce();
});

it('shows a form message when the rejected field is read-only and cannot render inline feedback', async () => {
  const { runtime, request, presentError } = createRuntime(undefined, false, true);
  runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);
  request.mockRejectedValueOnce(
    new AppError('只读名称已变更', { status: 409, targets: [{ kind: 'field', fieldName: 'title' }] }),
  );
  await runtime.submitLocalEdit();
  expect(runtime.localEditFieldErrors.value).toEqual({});
  expect(runtime.localEditErrorMessage.value).toBe('只读名称已变更');
  expect(runtime.localEditOpen.value).toBe(true);
  expect(presentError).not.toHaveBeenCalled();
  runtime.localEditDraft.value!.amount = '2';
  expect(runtime.localEditErrorMessage.value).toBeUndefined();
});
