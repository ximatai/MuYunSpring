import { ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { useModulePageDetailActionRuntime } from '@/dynamic-page-runtime/composables/useModulePageDetailActionRuntime.ts';

function createRuntime() {
  const request = vi.fn().mockResolvedValue({ message: '已提交' });
  const view = vi.fn().mockResolvedValue({ id: 'record-1', version: 2, title: '已刷新' });
  const resolveLoad = vi.fn();
  const refreshList = vi.fn();
  const presentSuccess = vi.fn();
  const presentError = vi.fn();
  const selectedRecord = ref({ id: 'record-1', version: 1, title: '旧标题', hidden: '不提交' });
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
            refreshStrategy: { detail: false, list: true },
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
                { fieldName: 'title', fieldTitle: '名称', fieldUiControlAlias: 'date' },
                { fieldName: 'hidden', visible: false },
              ],
            },
          },
        ],
      },
    } as never),
    selectedRecord: selectedRecord as never,
    editorMode: ref<'create' | 'edit' | 'view'>('view'),
    detail: { resolveLoad },
    refreshList,
    presentSuccess,
    presentError,
  });
  return { runtime, request, view, resolveLoad, refreshList, presentSuccess, presentError };
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
        record: { id: 'record-1', version: 1, values: { title: '新名称' } },
        fieldNames: ['title'],
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
    });
  });

  it('keeps failed action effects inside the runtime boundary', async () => {
    const { runtime, request, presentError } = createRuntime();
    request.mockRejectedValueOnce(new Error('拒绝'));
    runtime.handleConfiguredAction(runtime.detailPageActions.value[0]);

    await runtime.submitLocalEdit();

    expect(presentError).toHaveBeenCalledWith(expect.any(Error), 'module-local-edit');
    expect(runtime.localEditSaving.value).toBe(false);
  });
});
