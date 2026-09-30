import { createConstructionPlanSession } from '@/platform-workbench/constructionPlanSession';
import type { ConstructionPlanSnapshot } from '@muyun/web-contracts';
import type { AssistantCapability, ConstructionPlanClient } from '@muyun/web-core';
import { afterEach, expect, it, vi } from 'vitest';
import { createAssistantSurfaceRegistry, type HttpClient, type HttpRequestOptions } from '@/web-core';
import { createMetadataWorkspace, type MetadataWorkspace } from '@/views/metadataWorkspace';
import { presentPlatformMessage, handlePlatformActionSuccess } from '@muyun/platform-components';

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
  const request = vi.fn(async ({ path }: HttpRequestOptions) => {
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
        warnings: [],
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
  await f.select();
  await f.add();
  expect(f.openEditor).not.toHaveBeenCalled();
  const shared = f.workspace.session('demo.order');
  shared.view.fieldDraft.value.title = '人工修改';
  await f.invoke('configuration.update-metadata-field-draft', { fieldName: 'note', required: true });
  expect(shared.view.fieldDraft.value.title).toBe('人工修改');
  await f.invoke('configuration.open-metadata-editor');
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

it('requires explicit relation selection, refuses dirty target changes, and discards only unsaved candidates', async () => {
  const f = fixture();
  await f.select();
  await f.add();
  await expect(
    f.invoke('configuration.select-metadata-module', { moduleAlias: 'demo.order', relationId: 'child' }),
  ).rejects.toThrow();
  const confirmation = await f.workspace.session('demo.order').adapter.prepareConfirmation!(
    new AbortController().signal,
  );
  await f.invoke('configuration.discard-metadata-draft');
  expect(confirmation.isCurrent()).toBe(false);
  await f.invoke('configuration.select-metadata-module', { moduleAlias: 'demo.order', relationId: 'child' });
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
  await f.invoke('configuration.prepare-metadata-child-draft', { alias: 'lines', title: '商品明细' });
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
  await f.invoke('configuration.prepare-metadata-child-draft', { alias: 'lines', title: '成交明细' });
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

it('invalidates child confirmations on focus change and does not replace another unsaved field candidate', async () => {
  const f = fixture();
  await f.select();
  await f.add();
  await expect(
    f.invoke('configuration.prepare-metadata-child-draft', { alias: 'lines', title: '明细' }),
  ).rejects.toThrow();
  expect(f.workspace.session('demo.order').view.fieldDraft.value.title).toBe('备注');
  await f.invoke('configuration.discard-metadata-draft');
  await f.invoke('configuration.prepare-metadata-child-draft', { alias: 'lines', title: '明细' });
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
  await f.invoke('configuration.prepare-metadata-child-draft', { alias: 'lines', title: '明细' });
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
  await f.invoke('configuration.prepare-metadata-child-draft', { alias: 'lines', title: '明细' });
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
    await f.invoke('configuration.prepare-metadata-child-draft', { alias: 'lines', title: '明细' });
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
    await f.invoke('configuration.prepare-metadata-child-draft', { alias: 'lines', title: '明细' });
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
  await f.invoke('configuration.prepare-metadata-child-draft', { alias: 'lines', title: '明细' });
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
  const f = fixture(construction.capabilities, construction.continueConfiguration);
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
  expect(current.takeContinuation()).toContain('标准应用、模块管理页面');
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
  expect(unrelated.takeContinuation()).toBeUndefined();
});
