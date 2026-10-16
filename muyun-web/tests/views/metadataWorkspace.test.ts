import { createConfigurationCollaboration } from '@/platform-workbench/configurationCollaboration';
import { createConstructionPlanSession } from '@/platform-workbench/constructionPlanSession';
import type { ConstructionPlanSnapshot } from '@muyun/web-contracts';
import type { AssistantCapability, ConstructionPlanClient } from '@muyun/web-core';
import { afterEach, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import {
  AppError,
  createAssistantSurfaceRegistry,
  type HttpClient,
  type HttpRequestOptions,
} from '@/web-core';
import { createMetadataWorkspace, type MetadataWorkspace } from '@/views/metadataWorkspace';
import { presentPlatformMessage, handlePlatformActionSuccess } from '@muyun/platform-components';
import { confirmAction } from '@muyun/vue-ui-antdv';

vi.mock('@muyun/platform-components', async () => ({
  ...(await import('@/platform-components/selectionRefresh')),
  handlePlatformActionSuccess: vi.fn(),
  presentPlatformError: vi.fn(),
  presentPlatformMessage: vi.fn(),
}));
vi.mock('@muyun/vue-ui-antdv', () => ({ confirmAction: vi.fn() }));
const workspaces: MetadataWorkspace[] = [];
afterEach(() => {
  workspaces.splice(0).forEach((workspace) => workspace.dispose());
});

function fixture(
  contributions: () => AssistantCapability[] = () => [],
  continueConfiguration: (
    capabilities: AssistantCapability[],
    moduleAlias?: string,
  ) => AssistantCapability[] = (capabilities) => capabilities,
) {
  let identity = 'a';
  const rows = (records: unknown[]) => ({ records, pages: 1, totalKnown: true });
  let createdChild = false;
  const request = vi.fn(async ({ path }: HttpRequestOptions): Promise<unknown> => {
    if (path.endsWith('/metadata-relations/query'))
      return rows([
        { id: 'main', metadataId: 'meta', relationRole: 'main' },
        { id: 'child', metadataId: 'child-meta', relationRole: 'child', parentMetadataId: 'meta' },
        ...(createdChild
          ? [
              {
                id: 'lines',
                metadataId: 'lines-meta',
                relationRole: 'child',
                parentMetadataId: 'meta',
                relationAlias: 'lines',
              },
            ]
          : []),
      ]);
    if (path.startsWith('/platform.metadata/view/'))
      return { id: path.split('/').at(-1), alias: 'order', title: '订单', version: 3 };
    if (path.endsWith('/fields/query')) return rows([]);
    if (path.endsWith('/field-properties')) return [];
    if (path.endsWith('/capabilities')) return { capabilities: [] };
    if (path.endsWith('/record-count')) return { recordCount: 0 };
    if (path === '/platform.field_spec/query')
      return rows([{ alias: 'string', title: '文本', enabled: true }]);
    if (path.endsWith('change-set-preview'))
      return {
        errors: [],
        warnings: [] as { message: string }[],
        fieldImpacts: [],
        schemaImpacts: [],
        orderImpacts: [],
        proposalFingerprint: 'checked',
      };
    if (path.endsWith('/runtime/activation'))
      return { status: 'ACTIVE', desiredRevision: 1, installedRevision: 1 };
    if (path.endsWith('change-set-apply')) return {};
    if (path.includes('/child-metadata-creations/'))
      return createdChild ? { metadataId: 'lines-meta', relationId: 'lines' } : undefined;
    if (path.endsWith('/create-child-metadata')) {
      createdChild = true;
      return { metadata: { id: 'lines-meta' }, relation: { id: 'lines' } };
    }
    throw new Error(`Unexpected ${path}`);
  });
  const openEditor = vi.fn((alias: string) => workspace.showEditor(workspace.session(alias)));
  const openPageEditor = vi.fn();
  const workspace = createMetadataWorkspace(
    { request } as HttpClient,
    () => identity,
    () => true,
    openEditor,
    undefined,
    openPageEditor,
  );
  workspaces.push(workspace);
  const registry = createAssistantSurfaceRegistry(() => identity, workspace.current);
  registry.register({
    pageInstanceKey: 'shell',
    contextRevision: () => '',
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      capabilities: () => [
        ...contributions(),
        ...continueConfiguration(
          workspace.capabilities(async () => {}),
          workspace.editor()?.moduleAlias,
        ),
      ],
      requestTurn: vi.fn(),
    },
  });
  registry.activate('shell');
  const invoke = (code: string, input: unknown = {}) =>
    registry.invoke({ id: code, code, input }, registry.snapshot()!.token);
  const select = (moduleAlias = 'demo.order', refresh = false) =>
    invoke('configuration.select-metadata-module', { moduleAlias, refresh });
  const add = () =>
    invoke('configuration.add-metadata-field-draft', {
      title: '备注',
      fieldName: 'note',
      fieldSpecAlias: 'string',
    });
  return {
    workspace,
    request,
    openEditor,
    openPageEditor,
    invoke,
    select,
    add,
    identity(value: string) {
      identity = value;
      workspace.current();
    },
  };
}

it.each(['single', 'batch'])(
  'prepares a standard record-name field without technical naming input (%s)',
  async (mode) => {
    const f = fixture();
    await f.select();
    const before = await f.invoke('configuration.describe-metadata-model');
    expect(before.value).toMatchObject({
      selectedRelation: { fieldsSource: 'SAVED_CONFIGURATION', fieldCount: 0 },
    });
    const field = {
      title: '客户名称',
      fieldSpecAlias: 'string',
      titleField: true,
      required: true,
      unique: false,
    };
    if (mode === 'single') await f.invoke('configuration.add-metadata-field-draft', field);
    else
      await f.invoke('configuration.prepare-metadata-field-plan', { fields: [{ kind: 'BASIC', ...field }] });
    const candidate = await f.invoke('configuration.describe-metadata-model');
    expect(candidate.value).toMatchObject({
      selectedRelation: {
        fieldsSource: 'UNSAVED_CANDIDATE',
        fields: [
          {
            fieldName: 'title',
            columnName: 'title',
            title: '客户名称',
            titleField: true,
            required: true,
            uniqueField: false,
          },
        ],
      },
    });
    await f.invoke('configuration.preview-metadata-draft');
    const preview = f.request.mock.calls.find(([request]) => request.path.endsWith('change-set-preview'));
    expect(preview?.[0].body).toMatchObject({
      relationDrafts: [
        {
          fieldDrafts: [
            {
              field: {
                fieldName: 'title',
                columnName: 'title',
                titleField: true,
                fieldSpecAlias: 'string',
              },
            },
          ],
        },
      ],
    });
    expect(f.request.mock.calls.some(([request]) => request.path.endsWith('change-set-apply'))).toBe(false);
  },
);

it('updates generated names when a human selects record-name semantics and preserves explicit names', async () => {
  const f = fixture();
  await f.select();
  const view = f.workspace.session('demo.order').view;
  view.startCreateChildNode('BASIC');
  view.fieldDraft.value.title = '客户名称';
  await nextTick();
  expect(view.fieldDraft.value.fieldName).toBe('keHuMingCheng');
  view.fieldDraft.value.titleField = true;
  await nextTick();
  expect(view.fieldDraft.value).toMatchObject({ fieldName: 'title', columnName: 'title' });
  view.fieldDraft.value.title = '客户简称';
  await nextTick();
  expect(view.fieldDraft.value).toMatchObject({ fieldName: 'title', columnName: 'title' });
  view.fieldDraft.value.titleField = false;
  await nextTick();
  expect(view.fieldDraft.value).toMatchObject({ fieldName: 'keHuJianCheng', columnName: 'ke_hu_jian_cheng' });
  view.updateFieldName('customName');
  view.updateColumnName('custom_column');
  view.fieldDraft.value.titleField = true;
  await nextTick();
  expect(view.fieldDraft.value).toMatchObject({ fieldName: 'customName', columnName: 'custom_column' });
});

it('opens page composition without selecting or loading metadata and preserves existing metadata drafts', async () => {
  const f = fixture();
  await f.invoke('configuration.open-page-editor', { moduleAlias: 'demo.order' });
  expect(f.openPageEditor).toHaveBeenCalledExactlyOnceWith('demo.order', undefined);
  expect(f.request).not.toHaveBeenCalled();
  expect(f.workspace.editor()).toBeUndefined();
  await f.select();
  await f.add();
  const selected = f.workspace.session('demo.order');
  f.request.mockClear();
  await f.invoke('configuration.open-page-editor', { moduleAlias: 'demo.customer' });
  expect(f.openPageEditor).toHaveBeenLastCalledWith('demo.customer', undefined);
  expect(f.workspace.editor()?.moduleAlias).toBe('demo.order');
  expect(selected.view.fieldDraft.value.title).toBe('备注');
  expect(f.request).not.toHaveBeenCalled();
});

it.each([{}, { moduleAlias: '../outside' }, { moduleAlias: 'demo.order', url: '/outside' }])(
  'rejects invalid page editor destinations before navigation: %j',
  async (input) => {
    const f = fixture();
    await expect(f.invoke('configuration.open-page-editor', input)).rejects.toThrow();
    expect(f.openPageEditor).not.toHaveBeenCalled();
    expect(f.request).not.toHaveBeenCalled();
  },
);

it('prepares, revises and confirms without mounting a page, and opens exactly the same candidate on request', async () => {
  const f = fixture();
  const selection = await f.select();
  expect(selection.presentation).toMatchObject({
    title: '已选中配置对象：订单',
    lines: expect.arrayContaining(['当前仅在工作区选中对象，尚未打开对应编辑页。']),
  });
  await f.add();
  expect(f.openEditor).not.toHaveBeenCalled();
  const shared = f.workspace.session('demo.order');
  shared.view.fieldDraft.value.title = '人工修改';
  await f.invoke('configuration.update-metadata-field-draft', { fieldName: 'note', required: true });
  expect(shared.view.fieldDraft.value.title).toBe('人工修改');
  const opened = await f.invoke('configuration.open-metadata-editor');
  expect(opened.presentation?.title).toBe('已打开配置编辑页：订单');
  expect(f.openEditor).toHaveBeenCalledExactlyOnceWith('demo.order', '订单');
  await shared.ensureLoaded();
  expect(shared.view.fieldDraft.value.title).toBe('人工修改');
  const confirmation = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
  expect(confirmation.presentation.lines.join('\n')).toContain('人工修改');
  await confirmation.confirm();
  expect(confirmation.state).toBe('succeeded');
  expect(f.request.mock.calls.filter(([request]) => request.path.endsWith('change-set-apply'))).toHaveLength(
    1,
  );
  expect(shared.dirty.value).toBe(false);
});

it('manual metadata confirmation presents business changes and warnings while keeping technical impacts in details', async () => {
  const f = fixture();
  const original = f.request.getMockImplementation()!;
  f.request.mockImplementation(async (request) =>
    request.path.endsWith('change-set-preview')
      ? {
          errors: [],
          warnings: [{ message: '需要刷新生效' }],
          fieldImpacts: [],
          schemaImpacts: [],
          orderImpacts: [],
          proposalFingerprint: 'checked',
        }
      : original(request),
  );
  await f.select();
  await f.add();
  vi.mocked(confirmAction).mockResolvedValueOnce(false);
  await f.workspace.session('demo.order').view.previewAndApply();
  const options = vi.mocked(confirmAction).mock.calls.at(-1)![0];
  expect(options.content).toContain('备注');
  expect(options.content).toContain('文本');
  expect(options.content).toContain('需要刷新生效');
  expect(options.details?.lines.join('\n')).toContain('note');
  expect(f.request.mock.calls.some(([request]) => request.path.endsWith('change-set-apply'))).toBe(false);
});

it('stages managed capabilities with an existing field batch and expires confirmation after manual changes', async () => {
  const f = fixture();
  const original = f.request.getMockImplementation()!;
  f.request.mockImplementation(async (request) =>
    request.path.endsWith('/capabilities')
      ? {
          capabilities: [
            {
              capability: 'ENABLE',
              enabled: false,
              configurable: true,
              changeSetConfigurable: true,
              reason: '',
              fieldContributions: ['enabled'],
              defaultKind: 'STATIC',
              defaultDescription: '默认启用',
            },
          ],
        }
      : original(request),
  );
  await f.select();
  await f.invoke('configuration.prepare-metadata-field-plan', {
    fields: [
      { kind: 'BASIC', title: '姓名', fieldName: 'name', fieldSpecAlias: 'string' },
      { kind: 'BASIC', title: '电话', fieldName: 'phone', fieldSpecAlias: 'string' },
    ],
  });
  await expect(
    f.invoke('configuration.add-metadata-field-draft', {
      title: '启用状态',
      fieldName: 'enabled',
      fieldSpecAlias: 'string',
    }),
  ).rejects.toThrow('基础能力');
  await f.invoke('configuration.prepare-metadata-capability-draft', { capability: 'ENABLE', selected: true });
  const old = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
  f.workspace.session('demo.order').view.selectCapability('ENABLE', false);
  await old.confirm();
  expect(old.state).toBe('expired');
  expect(f.request.mock.calls.filter(([request]) => request.path.endsWith('change-set-apply'))).toHaveLength(
    0,
  );
  f.workspace.session('demo.order').view.selectCapability('ENABLE', true);
  const confirmation = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
  await confirmation.confirm();
  expect(confirmation.state).toBe('succeeded');
  const applies = f.request.mock.calls.filter(([request]) => request.path.endsWith('change-set-apply'));
  expect(applies).toHaveLength(1);
  expect(applies[0][0].body).toMatchObject({
    proposal: {
      relationDrafts: [
        {
          capabilitySelections: { ENABLE: true },
          fieldDrafts: [{ field: { fieldName: 'name' } }, { field: { fieldName: 'phone' } }],
        },
      ],
    },
  });
});

it.each([
  { capability: 'TREE', fieldContributions: ['parentId', 'sortOrder'] },
  {
    capability: 'APPROVAL',
    fieldContributions: [
      'approvalInstanceId',
      'approvalStatus',
      'approvalSubmittedBy',
      'approvalSubmittedAt',
      'approvalCompletedAt',
    ],
  },
])(
  'publishes a $capability-only candidate through the standard human confirmation',
  async ({ capability, fieldContributions }) => {
    const f = fixture();
    const original = f.request.getMockImplementation()!;
    f.request.mockImplementation(async (request) =>
      request.path.endsWith('/capabilities')
        ? {
            capabilities: [
              {
                capability,
                enabled: false,
                configurable: true,
                changeSetConfigurable: true,
                reason: '',
                fieldContributions,
                defaultKind: 'RUNTIME',
                defaultDescription: '根节点',
              },
            ],
          }
        : original(request),
    );
    await f.select();
    const view = f.workspace.session('demo.order').view;
    view.selectCapability(capability, true);
    expect(f.workspace.session('demo.order').dirty.value).toBe(true);
    vi.mocked(confirmAction).mockResolvedValueOnce(true);
    await view.previewAndApply('基础能力');
    const applies = f.request.mock.calls.filter(([request]) => request.path.endsWith('change-set-apply'));
    expect(applies).toHaveLength(1);
    expect(applies[0][0].body).toMatchObject({
      proposal: {
        relationDrafts: [
          {
            capabilitySelections: { [capability]: true },
            fieldDrafts: [],
          },
        ],
      },
    });
  },
);

it('reads existing business constraints and staged field changes without writing configuration', async () => {
  const f = fixture();
  const originalRequest = f.request.getMockImplementation()!;
  f.request.mockImplementation(async (request) =>
    request.path.endsWith('/fields/query')
      ? {
          records: [
            {
              id: 'name',
              fieldName: 'name',
              title: '姓名',
              fieldSpecAlias: 'string',
              required: true,
              uniqueField: true,
              indexed: true,
              sortableField: true,
              titleField: true,
              enabled: false,
            },
            { id: 'remarks', fieldName: 'remarks', title: '备注', fieldSpecAlias: 'string' },
            { id: 'system', fieldName: 'version', systemManaged: true, required: true },
          ],
          pages: 1,
          totalKnown: true,
        }
      : originalRequest(request),
  );
  await f.select();
  const readFields = async () => {
    const result = await f.invoke('configuration.describe-metadata-model');
    return (
      result.value as import('@/views/metadataGovernanceAssistantSurface').MetadataGovernanceAssistantModelSummary
    ).selectedRelation!.fields;
  };
  expect(await readFields()).toEqual([
    expect.objectContaining({
      fieldName: 'name',
      required: true,
      uniqueField: true,
      indexed: true,
      sortableField: true,
      titleField: true,
      enabled: false,
    }),
    expect.objectContaining({
      fieldName: 'remarks',
      required: false,
      uniqueField: false,
      indexed: false,
      sortableField: false,
      titleField: false,
      enabled: true,
    }),
  ]);
  await f.invoke('configuration.prepare-metadata-field-plan', {
    fields: [{ kind: 'BASIC', title: '联系电话', fieldName: 'phone', fieldSpecAlias: 'string' }],
  });
  expect(await readFields()).toContainEqual(expect.objectContaining({ fieldName: 'phone', required: false }));
  await f.invoke('configuration.update-metadata-field-draft', { fieldName: 'phone', required: true });
  expect(await readFields()).toContainEqual(expect.objectContaining({ fieldName: 'phone', required: true }));
  expect(f.request.mock.calls.some(([request]) => request.path.endsWith('change-set-apply'))).toBe(false);
});

it('preserves separate module candidates but never revives their old confirmations after focus or identity switches', async () => {
  const f = fixture();
  await f.select();
  await f.add();
  const original = f.workspace.session('demo.order');
  const first = await original.adapter.prepareConfirmation!(new AbortController().signal);
  await f.select('demo.customer');
  await f.select();
  expect(first.isCurrent()).toBe(false);
  expect(original.view.fieldDraft.value.title).toBe('备注');
  const second = await original.adapter.prepareConfirmation!(new AbortController().signal);
  f.identity('b');
  f.identity('a');
  expect(second.isCurrent()).toBe(false);
  expect(f.workspace.session('demo.order')).not.toBe(original);
  await expect(first.execute()).rejects.toThrow();
  expect(f.request.mock.calls.some(([request]) => request.path.endsWith('change-set-apply'))).toBe(false);
});

it.each(['', null])(
  'explains how to select a module without an invalid optional relation %s',
  async (relationId) => {
    const f = fixture();
    await expect(
      f.invoke('configuration.select-metadata-module', { moduleAlias: 'demo.order', relationId }),
    ).rejects.toThrow('请省略 relationId');
    expect(f.request).not.toHaveBeenCalled();
    await f.select();
    expect(f.workspace.session('demo.order').adapter.summary().selectedRelation?.relationId).toBe('main');
  },
);

it('requires explicit relation selection, refuses dirty target changes, and discards only unsaved candidates', async () => {
  const f = fixture();
  await f.select();
  await f.add();
  await expect(f.invoke('configuration.select-metadata-relation', { relationId: 'child' })).rejects.toThrow();
  const confirmation = await f.workspace.session('demo.order').adapter.prepareConfirmation!(
    new AbortController().signal,
  );
  await f.invoke('configuration.discard-metadata-draft');
  expect(confirmation.isCurrent()).toBe(false);
  await f.invoke('configuration.select-metadata-relation', { relationId: 'child' });
  await f.add();
  const proposal = f.workspace.session('demo.order').adapter.proposal();
  expect(proposal?.relationDrafts[0].relationId).toBe('child');
  expect(f.request.mock.calls.some(([request]) => request.path.endsWith('change-set-apply'))).toBe(false);
});

it('tracks actual editor visibility and ignores stale return actions after identity changes', async () => {
  const f = fixture();
  await f.select();
  await f.add();
  const session = f.workspace.session('demo.order');
  expect(f.workspace.editor()).toMatchObject({ title: '订单', visible: false, hasUnsavedChanges: true });
  f.workspace.showEditor(session);
  expect(f.workspace.editor()?.visible).toBe(true);
  f.workspace.hideEditor(session);
  const previous = f.workspace.editor()!;
  expect(previous.visible).toBe(false);
  previous.open!();
  expect(f.openEditor).toHaveBeenCalledOnce();
  f.identity('another-user');
  previous.open!();
  expect(f.openEditor).toHaveBeenCalledOnce();
  expect(f.workspace.editor()).toBeUndefined();
});

it('announces a committed change even if activation refresh fails, but never announces a rejected save', async () => {
  const f = fixture();
  await f.select();
  await f.add();
  const request = f.request.getMockImplementation()!;
  f.request.mockImplementation(async (options) => {
    if (options.path.endsWith('/runtime/activation')) throw new Error('activation unavailable');
    return request(options);
  });
  expect(f.workspace.committedRevision('demo.order')).toBe(0);
  await (await f.invoke('configuration.prepare-metadata-apply')).confirmation!.confirm();
  expect(f.workspace.committedRevision('demo.order')).toBe(1);
  expect(f.workspace.committedRevision('demo.customer')).toBe(0);
  f.identity('other-user');
  expect(f.workspace.committedRevision('demo.order')).toBe(0);
  await f.select();
  await f.add();
  f.request.mockImplementation(async (options) => {
    if (options.path.endsWith('change-set-apply')) throw new Error('save rejected');
    return request(options);
  });
  await (await f.invoke('configuration.prepare-metadata-apply')).confirmation!.confirm();
  expect(f.workspace.committedRevision('demo.order')).toBe(0);
});

it('reselects a clean business from current governance but preserves an unsaved candidate', async () => {
  const f = fixture();
  await f.select();
  const request = f.request.getMockImplementation()!;
  f.request.mockImplementation(async (options) => {
    if (options.path.endsWith('/fields/query'))
      return {
        records: [
          {
            id: 'external',
            fieldName: 'discount',
            title: '人工新增折扣',
            fieldSpecAlias: 'string',
            metadataId: 'meta',
            fieldOwnership: 'BUSINESS',
            fieldForm: 'PHYSICAL',
          },
        ],
        pages: 1,
        totalKnown: true,
      };
    return request(options);
  });
  await f.select('demo.order', true);
  const shared = f.workspace.session('demo.order');
  expect(shared.view.state.fields.value.some((field) => field.fieldName === 'discount')).toBe(true);
  expect(
    f.request.mock.calls.filter(([options]) => options.path.endsWith('/fields/query')).length,
  ).toBeGreaterThan(2);
  await f.add();
  shared.view.fieldDraft.value.title = '尚未保存的修改';
  const reads = f.request.mock.calls.length;
  await f.select();
  expect(shared.view.fieldDraft.value.title).toBe('尚未保存的修改');
  expect(f.request.mock.calls.length).toBe(reads);
});

it('does not replace a human candidate with a late governance refresh', async () => {
  const f = fixture();
  await f.select();
  const original = f.request.getMockImplementation()!;
  let release!: () => void;
  const waiting = new Promise<void>((resolve) => {
    release = resolve;
  });
  f.request.mockImplementation(async (options) => {
    if (options.path.endsWith('/metadata-relations/query')) await waiting;
    return original(options);
  });
  const refresh = f.select('demo.order', true);
  await f.add();
  f.workspace.session('demo.order').view.fieldDraft.value.title = '人工新需求';
  release();
  await expect(refresh).rejects.toThrow();
  expect(f.workspace.session('demo.order').view.fieldDraft.value.title).toBe('人工新需求');
});

it('shares a child candidate with the page, confirms once and continues field configuration on the created child', async () => {
  const f = fixture();
  await f.select();
  await f.invoke('configuration.prepare-metadata-child-draft', {
    parentRelationId: 'main',
    alias: 'lines',
    title: '商品明细',
  });
  const shared = f.workspace.session('demo.order');
  expect(shared.view.creatingChildMetadata.value).toBe(true);
  expect(
    f.request.mock.calls.some(([r]) => r.method === 'POST' && r.path.endsWith('/create-child-metadata')),
  ).toBe(false);
  await f.invoke('configuration.open-metadata-editor');
  shared.view.childMetadataDraft.value.title = '人工调整的明细';
  const first = await shared.adapter.prepareConfirmation!(new AbortController().signal);
  shared.view.childMetadataDraft.value.alias = 'items';
  expect(first.isCurrent()).toBe(false);
  await expect(first.execute()).rejects.toThrow();
  await f.invoke('configuration.prepare-metadata-child-draft', {
    parentRelationId: 'main',
    alias: 'lines',
    title: '成交明细',
  });
  const confirmation = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
  expect(confirmation.presentation.title).toContain('成交明细');
  await confirmation.confirm();
  expect(confirmation.state).toBe('succeeded');
  expect(f.request.mock.calls.filter(([r]) => r.path.endsWith('/create-child-metadata'))).toHaveLength(1);
  expect(f.request.mock.calls.find(([r]) => r.path.endsWith('/create-child-metadata'))![0].body).toEqual({
    alias: 'lines',
    title: '成交明细',
    requestId: expect.any(String),
  });
  expect(f.workspace.committedRevision('demo.order')).toBe(1);
  expect(shared.adapter.summary().selectedRelation?.relationId).toBe('lines');
  expect(shared.dirty.value).toBe(false);
  await f.add();
  expect(shared.adapter.proposal()?.relationDrafts[0].relationId).toBe('lines');
});

it('rejects a child under a different intended parent before changing the shared candidate', async () => {
  const f = fixture();
  await f.select();
  const shared = f.workspace.session('demo.order');
  await expect(
    f.invoke('configuration.prepare-metadata-child-draft', {
      parentRelationId: 'another-parent',
      alias: 'expenses',
      title: '费用明细',
    }),
  ).rejects.toThrow('目标父级');
  expect(shared.view.creatingChildMetadata.value).toBe(false);
  const prepared = await f.invoke('configuration.prepare-metadata-child-draft', {
    parentRelationId: 'main',
    alias: 'expenses',
    title: '费用明细',
  });
  expect(prepared.value).toMatchObject({ parentRelationId: 'main', saved: false });
  expect(prepared.presentation!.lines.join('；')).toContain('所属父级：');
  await expect(
    f.invoke('configuration.prepare-metadata-child-draft', {
      parentRelationId: 'another-parent',
      alias: 'expenses',
      title: '错误修订',
    }),
  ).rejects.toThrow('目标父级');
  expect(shared.view.childMetadataDraft.value.title).toBe('费用明细');
  expect(f.request.mock.calls.some(([request]) => request.path.endsWith('/create-child-metadata'))).toBe(
    false,
  );
});

it('invalidates child confirmations on focus change and does not replace another unsaved field candidate', async () => {
  const f = fixture();
  await f.select();
  await f.add();
  await expect(
    f.invoke('configuration.prepare-metadata-child-draft', {
      parentRelationId: 'main',
      alias: 'lines',
      title: '明细',
    }),
  ).rejects.toThrow();
  expect(f.workspace.session('demo.order').view.fieldDraft.value.title).toBe('备注');
  await f.invoke('configuration.discard-metadata-draft');
  await f.invoke('configuration.prepare-metadata-child-draft', {
    parentRelationId: 'main',
    alias: 'lines',
    title: '明细',
  });
  const shared = f.workspace.session('demo.order');
  const proposal = await shared.adapter.prepareConfirmation!(new AbortController().signal);
  await f.select('demo.customer');
  await f.select();
  expect(proposal.isCurrent()).toBe(false);
  await expect(proposal.execute()).rejects.toThrow();
  await f.invoke('configuration.discard-metadata-draft');
  await f.add();
  expect(shared.adapter.proposal()?.relationDrafts[0].relationId).toBe('main');
});

it('leaves an uncertain child creation unresolved instead of guessing success or retrying', async () => {
  const f = fixture();
  await f.select();
  await f.invoke('configuration.prepare-metadata-child-draft', {
    parentRelationId: 'main',
    alias: 'lines',
    title: '明细',
  });
  const shared = f.workspace.session('demo.order');
  const proposal = await shared.adapter.prepareConfirmation!(new AbortController().signal);
  f.request.mockRejectedValueOnce(new Error('Connection lost after write'));
  await expect(proposal.execute()).rejects.toThrow('Connection lost');
  expect(await proposal.lookup()).toBeUndefined();
  expect(shared.view.childMetadataDraft.value.title).toBe('明细');
  expect(f.request.mock.calls.filter(([r]) => r.path.endsWith('/create-child-metadata'))).toHaveLength(1);
});

it('recovers a committed child creation by request receipt without replaying the write', async () => {
  const f = fixture();
  await f.select();
  await f.invoke('configuration.prepare-metadata-child-draft', {
    parentRelationId: 'main',
    alias: 'lines',
    title: '明细',
  });
  const shared = f.workspace.session('demo.order');
  const confirmation = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
  const original = f.request.getMockImplementation()!;
  f.request.mockImplementation(async (request) => {
    const result = await original(request);
    if (request.path.endsWith('/create-child-metadata')) throw new Error('Connection lost after commit');
    return result;
  });
  await confirmation.confirm();
  expect(confirmation.state).toBe('unknown');
  await confirmation.check();
  expect(confirmation.state).toBe('succeeded');
  expect(confirmation.result?.title).toBe('明细已建立');
  expect(shared.adapter.summary().selectedRelation?.relationId).toBe('lines');
  expect(shared.dirty.value).toBe(false);
  const writes = f.request.mock.calls.filter(([r]) => r.path.endsWith('/create-child-metadata'));
  expect(writes).toHaveLength(1);
  const id = (writes[0]![0].body as { requestId: string }).requestId;
  expect(
    f.request.mock.calls.some(
      ([r]) => r.method === 'GET' && r.path.endsWith(`/child-metadata-creations/${id}`),
    ),
  ).toBe(true);
});

it.each([false, true])(
  'queries the same uncertain manual child request without a second write (committed: %s)',
  async (committed) => {
    const f = fixture();
    await f.select();
    await f.invoke('configuration.prepare-metadata-child-draft', {
      parentRelationId: 'main',
      alias: 'lines',
      title: '明细',
    });
    const shared = f.workspace.session('demo.order');
    const original = f.request.getMockImplementation()!;
    f.request.mockImplementation(async (request) => {
      if (request.path.endsWith('/create-child-metadata')) {
        if (committed) await original(request);
        throw new Error('Connection lost');
      }
      return original(request);
    });
    vi.mocked(presentPlatformMessage).mockClear();
    vi.mocked(handlePlatformActionSuccess).mockClear();
    shared.view.stageFieldDraft();
    await vi.waitFor(() =>
      expect(presentPlatformMessage).toHaveBeenCalledWith(
        expect.stringContaining('只查询本次结果'),
        expect.anything(),
      ),
    );
    shared.view.stageFieldDraft();
    await vi.waitFor(() =>
      expect(f.request.mock.calls.some(([r]) => r.path.includes('/child-metadata-creations/'))).toBe(true),
    );
    await vi.waitFor(() => expect(shared.saving.value).toBe(false));
    const writes = f.request.mock.calls.filter(([r]) => r.path.endsWith('/create-child-metadata'));
    expect(writes).toHaveLength(1);
    const requestId = (writes[0]![0].body as { requestId: string }).requestId;
    expect(
      f.request.mock.calls.some(([r]) => r.path.endsWith('/child-metadata-creations/' + requestId)),
    ).toBe(true);
    if (committed) {
      await vi.waitFor(() => expect(handlePlatformActionSuccess).toHaveBeenCalled());
      expect(shared.adapter.summary().selectedRelation?.relationId).toBe('lines');
      expect(shared.dirty.value).toBe(false);
    } else {
      expect(shared.view.childMetadataDraft.value.title).toBe('明细');
      shared.view.stageFieldDraft();
      await vi.waitFor(() =>
        expect(
          f.request.mock.calls.filter(([r]) => r.path.includes('/child-metadata-creations/')),
        ).toHaveLength(2),
      );
      expect(f.request.mock.calls.filter(([r]) => r.path.endsWith('/create-child-metadata'))).toHaveLength(1);
    }
  },
);

it.each(['candidate', 'focus', 'identity'])(
  'does not overwrite editor state when %s changes while a child receipt is pending',
  async (change) => {
    const f = fixture();
    await f.select();
    await f.invoke('configuration.prepare-metadata-child-draft', {
      parentRelationId: 'main',
      alias: 'lines',
      title: '明细',
    });
    const shared = f.workspace.session('demo.order');
    const proposal = await shared.adapter.prepareConfirmation!(new AbortController().signal);
    f.request.mockRejectedValueOnce(new Error('Connection lost'));
    await expect(proposal.execute()).rejects.toThrow();
    let release!: (receipt: { metadataId: string; relationId: string }) => void;
    const pending = new Promise<{ metadataId: string; relationId: string }>((resolve) => {
      release = resolve;
    });
    const original = f.request.getMockImplementation()!;
    f.request.mockImplementation((request) =>
      request.path.includes('/child-metadata-creations/') ? pending : original(request),
    );
    const checking = proposal.lookup();
    expect(shared.saving.value).toBe(true);
    if (change === 'candidate') shared.view.childMetadataDraft.value.title = '新的人工候选';
    if (change === 'focus') await f.select('demo.customer');
    if (change === 'identity') f.identity('b');
    const title = shared.view.childMetadataDraft.value.title;
    const selectedKey = shared.view.selectedTreeKey.value;
    release({ metadataId: 'lines-meta', relationId: 'lines' });
    if (change === 'identity') await expect(checking).rejects.toThrow('身份或编辑会话已变化');
    else expect((await checking)?.title).toBe('明细已建立，状态待核实');
    expect(shared.view.childMetadataDraft.value.title).toBe(title);
    expect(shared.view.selectedTreeKey.value).toBe(selectedKey);
    expect(shared.saving.value).toBe(false);
    if (change === 'identity') {
      const count = f.request.mock.calls.length;
      await expect(proposal.lookup()).rejects.toThrow();
      expect(f.request.mock.calls).toHaveLength(count);
    }
  },
);

it('reports committed child creation even when the subsequent workspace reload fails', async () => {
  const f = fixture();
  await f.select();
  await f.invoke('configuration.prepare-metadata-child-draft', {
    parentRelationId: 'main',
    alias: 'lines',
    title: '明细',
  });
  const proposal = await f.workspace.session('demo.order').adapter.prepareConfirmation!(
    new AbortController().signal,
  );
  f.request
    .mockResolvedValueOnce({ metadata: { id: 'lines-meta' }, relation: { id: 'lines' } })
    .mockRejectedValueOnce(new Error('reload failed'));
  const result = await proposal.execute();
  expect(result.title).toBe('明细已建立，状态待核实');
  expect(f.workspace.committedRevision('demo.order')).toBe(1);
  expect(proposal.isCurrent()).toBe(false);
  expect(f.request.mock.calls.filter(([r]) => r.path.endsWith('/create-child-metadata'))).toHaveLength(1);
});

it('does not count repeated target selection as progress or reload its catalog', async () => {
  const f = fixture();
  await f.select();
  const requests = f.request.mock.calls.length;
  const revision = f.workspace.current().revision;
  expect((await f.select()).contextChanged).toBe(false);
  expect(f.workspace.current().revision).toBe(revision);
  expect(f.request.mock.calls.length).toBe(requests);
  await f.add();
  expect((await f.select()).contextChanged).toBe(false);
  expect(f.workspace.session('demo.order').dirty.value).toBe(true);
});

it('continues initialized construction in the shared metadata candidate and honors manual revisions', async () => {
  const snapshot: ConstructionPlanSnapshot = {
    planId: 'plan',
    revision: 1,
    confirmedAt: '',
    constructionStatus: 'INITIALIZED',
    fieldChanges: [],
    deliveries: [],
    deliveredObjectKeys: [],
    initializations: [
      {
        objectKey: 'order',
        planRevision: 1,
        moduleAlias: 'demo.order',
        metadataId: 'meta',
        relationId: 'main',
        requestId: 'init',
      },
    ],
    content: {
      title: '订单登记',
      goal: '登记订单',
      inScope: [],
      outOfScope: [],
      objects: [{ key: 'order', name: '订单', purpose: '登记' }],
      relationships: [],
      rules: [],
      questions: [],
      assumptions: [],
      decisions: [],
      acceptanceExamples: [],
    },
  };
  const client = { read: vi.fn(async () => snapshot) } as unknown as ConstructionPlanClient;
  const construction = createConstructionPlanSession(
    client,
    () => 'a',
    () => true,
  );
  await construction.restore('plan');
  const collaboration = createConfigurationCollaboration();
  collaboration.restore({ goal: '继续完善订单', mode: 'visual' });
  const f = fixture(construction.capabilities, (capabilities) => collaboration.filter(capabilities, true));
  const binding = construction.current().saved!.initializations[0]!;
  await f.select(binding.moduleAlias);
  await f.add();
  const shared = f.workspace.session(binding.moduleAlias);
  const first = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
  await f.invoke('configuration.open-metadata-editor');
  shared.view.fieldDraft.value.title = '人工补充的备注';
  await first.confirm();
  expect(first.state).toBe('expired');
  expect(f.request.mock.calls.some(([request]) => request.path.endsWith('change-set-apply'))).toBe(false);
  const current = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
  expect(current.presentation.lines.join(' ')).toContain('人工补充的备注');
  await current.confirm();
  expect(current.state).toBe('succeeded');
  expect(current.takeContinuation()).toContain('现行配置是事实来源');
  expect(current.takeContinuation()).toBeUndefined();
  expect(f.request.mock.calls.filter(([request]) => request.path.endsWith('change-set-apply'))).toHaveLength(
    1,
  );
  expect(construction.current().saved!.fieldChanges).toEqual([]);
  expect(shared.dirty.value).toBe(false);
  await f.select('demo.other');
  await f.add();
  const unrelated = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
  await unrelated.confirm();
  expect(unrelated.state).toBe('succeeded');
  expect(unrelated.takeContinuation()).toContain('用户已明确目标中的剩余事项');
});

it('revises one field in a batch candidate while preserving manual changes and invalidating old confirmation', async () => {
  const f = fixture();
  await f.select();
  await f.invoke('configuration.prepare-metadata-field-plan', {
    fields: [
      { kind: 'BASIC', title: '姓名', fieldName: 'name', fieldSpecAlias: 'string' },
      { kind: 'BASIC', title: '备注', fieldName: 'note', fieldSpecAlias: 'string' },
    ],
  });
  const shared = f.workspace.session('demo.order');
  shared.view.editPlanField(shared.view.fieldPlanEntries.value[1]!);
  shared.view.fieldDraft.value.title = '人工修改的备注';
  await shared.view.stageFieldDraft();
  const originalNote = { ...shared.view.fieldPlanEntries.value[1]! };
  const confirmation = await shared.adapter.prepareConfirmation!(new AbortController().signal);
  await f.invoke('configuration.update-metadata-field-draft', { fieldName: 'name', required: true });
  expect(shared.view.fieldPlanEntries.value).toHaveLength(2);
  expect(shared.view.fieldPlanEntries.value[0]).toMatchObject({ fieldName: 'name', required: true });
  expect(shared.view.fieldPlanEntries.value[1]).toEqual(originalNote);
  expect(shared.view.state.fieldEditorOpen.value).toBe(false);
  expect(confirmation.isCurrent()).toBe(false);
  expect(f.request.mock.calls.some(([r]) => r.path.endsWith('change-set-apply'))).toBe(false);
});

it.each(['BASIC', 'MODULE_REFERENCE'] as const)(
  'appends a %s field to a batch without losing manual edits',
  async (kind) => {
    const f = fixture();
    const request = f.request.getMockImplementation()!;
    f.request.mockImplementation(async (r) => {
      if (r.path.endsWith('/reference-target-modules')) return [{ alias: 'iam.user', title: '用户' }];
      if (r.path.includes('/reference-target-field-catalog?'))
        return {
          keyFields: [{ fieldName: 'id', defaultField: true, selectable: true }],
          labelFields: [{ fieldName: 'title', defaultField: true, selectable: true }],
        };
      return request(r);
    });
    await f.select();
    await f.invoke('configuration.prepare-metadata-field-plan', {
      fields: [{ kind: 'BASIC', title: '备注', fieldName: 'note', fieldSpecAlias: 'string' }],
    });
    const shared = f.workspace.session('demo.order');
    shared.view.editPlanField(shared.view.fieldPlanEntries.value[0]!);
    shared.view.fieldDraft.value.title = '人工补充的备注';
    await shared.view.stageFieldDraft();
    const original = { ...shared.view.fieldPlanEntries.value[0]! };
    const confirmation = await shared.adapter.prepareConfirmation!(new AbortController().signal);
    expect(f.workspace.capabilities(async () => {}).map((c) => c.descriptor.code)).not.toContain(
      'configuration.prepare-metadata-field-plan',
    );
    await f.invoke(
      kind === 'BASIC'
        ? 'configuration.add-metadata-field-draft'
        : 'configuration.add-metadata-property-field-draft',
      kind === 'BASIC'
        ? { title: '附加说明', fieldName: 'extra', fieldSpecAlias: 'string' }
        : { kind, title: '经办人', fieldName: 'extra', target: 'iam.user' },
    );
    await shared.view.stageFieldDraft();
    expect(shared.view.fieldPlanEntries.value).toHaveLength(2);
    expect(shared.view.fieldPlanEntries.value[0]).toEqual(original);
    expect(shared.view.fieldPlanEntries.value[1]).toMatchObject({ fieldName: 'extra' });
    expect(confirmation.isCurrent()).toBe(false);
    expect(f.request.mock.calls.some(([r]) => r.path.endsWith('change-set-apply'))).toBe(false);
  },
);

it('rejects an invented relation before changing the active workspace or accepting a loaded baseline', async () => {
  const f = fixture();
  await f.select();
  const original = f.workspace.editor();
  const capabilities = f.workspace.capabilities(async () => {});
  expect(
    capabilities.find((item) => item.descriptor.code === 'configuration.select-metadata-module')!.descriptor
      .inputSchema.properties,
  ).not.toHaveProperty('relationId');
  expect(
    capabilities.find((item) => item.descriptor.code === 'configuration.select-metadata-relation')!.descriptor
      .inputSchema.properties,
  ).toMatchObject({ relationId: { enum: ['main', 'child'] } });
  await expect(
    f.invoke('configuration.select-metadata-relation', { relationId: 'invented' }),
  ).rejects.toThrow('真实元数据节点');
  const existingRequest = f.request.getMockImplementation()!;
  f.request.mockImplementation(async (request) =>
    request.path === '/platform.module/demo.product/metadata-relations/query'
      ? { records: [], pages: 1, totalKnown: true }
      : existingRequest(request),
  );
  await expect(
    f.invoke('configuration.select-metadata-module', { moduleAlias: 'demo.product', relationId: 'main' }),
  ).rejects.toThrow('请省略 relationId');
  expect(f.workspace.editor()?.moduleAlias).toBe(original?.moduleAlias);
  expect(f.workspace.session('demo.product').workspaceReady.value).toBe(false);
  await f.select('demo.product');
  expect(f.workspace.session('demo.product').adapter.summary().relationCount).toBe(0);
  expect(
    f.workspace
      .capabilities(async () => {})
      .some((item) => item.descriptor.code === 'configuration.select-metadata-relation'),
  ).toBe(false);
});

it('prepares a missing main entity in the shared editor without creating storage or replacing manual settings', async () => {
  const f = fixture();
  const existingRequest = f.request.getMockImplementation()!;
  let createdMain = false;
  f.request.mockImplementation(async (request) => {
    if (request.path.endsWith('/metadata-relations/query') && !createdMain)
      return { records: [], pages: 1, totalKnown: true };
    if (request.path.endsWith('/create-main-metadata')) {
      createdMain = true;
      return { metadata: { id: 'meta-main' }, relation: { id: 'main' } };
    }
    return existingRequest(request);
  });
  await f.select();
  await f.invoke('configuration.prepare-metadata-main-draft', { title: '客户' });
  const shared = f.workspace.session('demo.order');
  expect(shared.adapter.summary()).toMatchObject({
    relationCount: 0,
    mainCandidate: {
      alias: 'order',
      title: '客户',
      saved: false,
      storageDefaultsOnSave: ['schemaName', 'tableName'],
      nextStep: 'REVIEW_AND_SAVE_STRUCTURE_BEFORE_FIELDS',
    },
    draft: { active: true, dirty: true, editorOpen: true },
  });
  expect(
    f.request.mock.calls.some(([r]) => r.method === 'POST' && r.path.endsWith('/create-main-metadata')),
  ).toBe(false);
  await f.invoke('configuration.open-metadata-editor');
  shared.view.mainMetadataDraft.value.tableName = 'manual_customer';
  expect(shared.adapter.summary().mainCandidate?.storageDefaultsOnSave).toEqual(['schemaName']);
  const beforeEdit = shared.contextRevision.value;
  shared.view.mainMetadataDraft.value.schemaName = 'public';
  expect(shared.contextRevision.value).toBeGreaterThan(beforeEdit);
  await f.invoke('configuration.prepare-metadata-main-draft', { title: '客户资料' });
  expect(shared.adapter.summary().mainCandidate).toMatchObject({
    title: '客户资料',
    tableName: 'manual_customer',
    schemaName: 'public',
    storageDefaultsOnSave: [],
  });
  shared.view.mainMetadataDraft.value.schemaName = '   ';
  expect(shared.adapter.summary().mainCandidate?.storageDefaultsOnSave).toEqual(['schemaName']);
  expect(shared.view.mainMetadataDraft.value.tableName).toBe('manual_customer');
  await shared.view.createMainMetadata();
  expect(f.request.mock.calls.filter(([r]) => r.path.endsWith('/create-main-metadata'))).toHaveLength(1);
  expect(shared.adapter.summary().mainCandidate).toBeUndefined();
  expect(shared.adapter.summary().selectedRelation?.relationId).toBe('main');
  await expect(
    f.invoke('configuration.prepare-metadata-main-draft', { title: '重复创建' }),
  ).rejects.toThrow();
});

it('reads relation identities on demand instead of repeating them in every workspace snapshot', async () => {
  const f = fixture();
  await f.select();
  expect(f.workspace.current().facts.metadataConfiguration).not.toHaveProperty('relations');
  const result = await f.invoke('configuration.list-metadata-relations', {});
  expect(result.contextChanged).toBe(false);
  expect(result.value).toMatchObject({ moduleAlias: 'demo.order', total: 2, nextOffset: null });
  expect((result.value as { relations: unknown[] }).relations).toHaveLength(2);
  await expect(f.invoke('configuration.list-metadata-relations', { offset: -1 })).rejects.toThrow();
  await expect(f.invoke('configuration.list-metadata-relations', { payload: {} })).rejects.toThrow();
});

it.each(['rejected', 'unknown'] as const)(
  'keeps atomic metadata %s outcomes distinct in the assistant confirmation',
  async (outcome) => {
    const f = fixture();
    const original = f.request.getMockImplementation()!;
    let rejectedOnce = false;
    f.request.mockImplementation(async (request) => {
      if (request.path.endsWith('change-set-apply') && !rejectedOnce) {
        rejectedOnce = true;
        throw outcome === 'rejected'
          ? new AppError('能力配置不符合要求', { code: 'INVALID_CONFIGURATION', status: 400 })
          : new Error('connection lost');
      }
      return original(request);
    });
    await f.select();
    await f.add();
    const confirmation = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
    await confirmation.confirm();
    expect(confirmation.state).toBe(outcome);
    const applies = () =>
      f.request.mock.calls.filter(([request]) => request.path.endsWith('change-set-apply'));
    expect(applies()).toHaveLength(1);
    if (outcome === 'rejected') {
      expect(confirmation.result?.title).toBe('操作未提交');
      await confirmation.confirm();
      expect(confirmation.state).toBe('succeeded');
      expect(applies()).toHaveLength(2);
    } else {
      await confirmation.check();
      await confirmation.confirm();
      expect(confirmation.state).toBe('unknown');
      expect(applies()).toHaveLength(1);
    }
  },
);

it('stages fixed defaults through the shared draft, preserves human changes, and expires an earlier confirmation', async () => {
  const f = fixture();
  await f.select();
  await f.invoke('configuration.add-metadata-field-draft', {
    title: '优惠系数',
    fieldName: 'rate',
    fieldSpecAlias: 'string',
    defaultValue: '1',
  });
  const shared = f.workspace.session('demo.order');
  expect(shared.view.fieldPropertyDraft.value.fixedDefault).toEqual({ value: '1' });
  expect(f.request.mock.calls.some(([options]) => options.path.endsWith('change-set-apply'))).toBe(false);
  shared.view.fieldDraft.value.title = '人工调整的优惠系数';
  const old = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
  await f.invoke('configuration.update-metadata-field-draft', { fieldName: 'rate', defaultValue: '0.9' });
  expect(shared.view.fieldDraft.value.title).toBe('人工调整的优惠系数');
  await old.confirm();
  expect(old.state).toBe('expired');
  const fresh = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
  expect(fresh.presentation.lines.join('\n')).toContain('固定默认值：0.9');
  await fresh.confirm();
  expect(fresh.state).toBe('succeeded');
  const applies = f.request.mock.calls.filter(([options]) => options.path.endsWith('change-set-apply'));
  expect(applies).toHaveLength(1);
  expect(applies[0][0].body).toMatchObject({
    proposal: {
      relationDrafts: [
        {
          fieldDrafts: [
            { field: { title: '人工调整的优惠系数' }, property: { fixedDefault: { value: '0.9' } } },
          ],
        },
      ],
    },
  });
});

it.each([1, false, {}, 'x'.repeat(513)])(
  'rejects an invalid fixed default before changing a shared candidate: %j',
  async (defaultValue) => {
    const f = fixture();
    await f.select();
    await expect(
      f.invoke('configuration.add-metadata-field-draft', {
        title: '优惠系数',
        fieldName: 'rate',
        fieldSpecAlias: 'string',
        defaultValue,
      }),
    ).rejects.toThrow();
    expect(f.workspace.session('demo.order').dirty.value).toBe(false);
  },
);

it.each(['manual', 'assistant'] as const)(
  'protects an unknown %s metadata submission across both entries until a guarded current read',
  async (entry) => {
    const f = fixture();
    await f.select();
    await f.add();
    const session = f.workspace.session('demo.order');
    const prepared = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
    const original = f.request.getMockImplementation()!;
    let refreshed = false;
    f.request.mockImplementation(async (request) => {
      if (request.path.endsWith('change-set-apply')) throw new Error('response lost');
      if (refreshed && request.path.startsWith('/platform.metadata/view/'))
        return { id: request.path.split('/').at(-1), title: '订单', version: 9 };
      return original(request);
    });
    vi.mocked(confirmAction).mockResolvedValue(true);
    if (entry === 'manual') await session.view.stageFieldDraft();
    else await prepared.confirm();
    await nextTick();
    // stageFieldDraft starts the same manual async submit; finish its HTTP work.
    for (let index = 0; index < 10 && session.saving.value; index++) await nextTick();
    expect(session.submissionStatus.value).toBe('unknown');
    const retained = session.adapter.proposal();
    expect(retained?.relationDrafts[0]?.fieldDrafts[0]?.field?.fieldName).toBe('note');
    expect(() => session.adapter.discardCandidate!()).toThrow('结果未知');
    await expect(session.adapter.prepareConfirmation!(new AbortController().signal)).rejects.toThrow(
      '结果未知',
    );
    await expect(session.view.loadWorkspace()).rejects.toThrow('结果未知');
    await session.view.previewAndApply();
    await f.select('demo.order', true);
    expect(session.submissionStatus.value).toBe('unknown');
    expect(session.adapter.proposal()).toEqual(retained);
    expect(
      f.request.mock.calls.filter(([request]) => request.path.endsWith('change-set-apply')),
    ).toHaveLength(1);
    expect(() => session.view.cancelFieldPlan()).toThrow('结果未知');
    refreshed = true;
    const read = await f.invoke('configuration.read-current-metadata');
    expect(read.value).toMatchObject({ originalSubmission: 'unknown', currentConfigurationRead: true });
    expect(session.submissionStatus.value).toBe('current-read');
    expect(session.adapter.proposal()?.relationDrafts[0]).toMatchObject({
      expectedMetadataVersion: 9,
      fieldDrafts: [{ operation: 'ADD', field: { fieldName: 'note' } }],
    });
    if (entry === 'assistant') expect(prepared.state).toBe('unknown');
    expect(
      f.request.mock.calls.filter(([request]) => request.path.endsWith('change-set-apply')),
    ).toHaveLength(1);
    const fresh = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
    expect(fresh.state).toBe('pending');
  },
);

it.each(['failure', 'identity', 'cancel', 'scope', 'candidate'] as const)(
  'retains metadata unknown state and candidate when current read encounters %s',
  async (failure) => {
    const f = fixture();
    await f.select();
    await f.add();
    const session = f.workspace.session('demo.order');
    const original = f.request.getMockImplementation()!;
    f.request.mockImplementation(async (request) => {
      if (request.path.endsWith('change-set-apply')) throw new Error('response lost');
      return original(request);
    });
    await (await f.invoke('configuration.prepare-metadata-apply')).confirmation!.confirm();
    const retained = session.adapter.proposal();
    let finish!: (value: unknown) => void;
    f.request.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const controller = new AbortController();
    const reading = session.readCurrent(controller.signal);
    if (failure === 'identity') f.identity('other');
    if (failure === 'cancel') controller.abort();
    if (failure === 'scope') f.workspace.focus(f.workspace.session('demo.other'));
    if (failure === 'candidate') session.view.state.fieldDraft.value.title = 'changed during read';
    finish(
      failure === 'failure'
        ? undefined
        : await original({ path: '/platform.module/demo.order/metadata-relations/query' }),
    );
    await expect(reading).rejects.toThrow();
    expect(session.submissionStatus.value).toBe('unknown');
    if (failure !== 'candidate') expect(session.adapter.proposal()).toEqual(retained);
    else expect(session.view.state.fieldDraft.value.title).toBe('changed during read');
  },
);

it('keeps a proven metadata save distinct from a failed readback and allows reopening its recovery editor', async () => {
  const f = fixture();
  await f.select();
  await f.add();
  const session = f.workspace.session('demo.order');
  const original = f.request.getMockImplementation()!;
  let committed = false;
  let failedReadback = false;
  f.request.mockImplementation(async (request) => {
    if (request.path.endsWith('change-set-apply')) {
      committed = true;
      return {};
    }
    if (committed && !failedReadback && request.path.endsWith('/metadata-relations/query')) {
      failedReadback = true;
      throw new Error('readback failed');
    }
    return original(request);
  });
  const confirmation = (await f.invoke('configuration.prepare-metadata-apply')).confirmation!;
  await confirmation.confirm();
  expect(confirmation.state).toBe('succeeded');
  expect(session.committedNeedsReload.value).toBe(true);
  expect(session.submissionStatus.value).toBe('idle');
  expect(session.workspaceReady.value).toBe(false);
  await f.select('demo.other');
  await f.select('demo.order', true);
  await f.invoke('configuration.open-metadata-editor');
  expect(f.openEditor).toHaveBeenLastCalledWith('demo.order', expect.anything());
  await expect(session.adapter.prepareConfirmation!(new AbortController().signal)).rejects.toThrow('已保存');
  const current = await f.invoke('configuration.read-current-metadata');
  expect(current.value).toMatchObject({ originalSubmission: 'committed', currentConfigurationRead: true });
  expect(session.committedNeedsReload.value).toBe(false);
  expect(f.request.mock.calls.filter(([request]) => request.path.endsWith('change-set-apply'))).toHaveLength(
    1,
  );
});
