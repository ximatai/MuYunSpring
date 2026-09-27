import { afterEach, expect, it, vi } from 'vitest';
import { createAssistantSurfaceRegistry, type HttpClient, type HttpRequestOptions } from '@/web-core';
import { createMetadataWorkspace, type MetadataWorkspace } from '@/views/metadataWorkspace';

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

function fixture() {
  let identity = 'a';
  const rows = (records: unknown[]) => ({ records, pages: 1, totalKnown: true });
  const request = vi.fn(async ({ path }: HttpRequestOptions) => {
    if (path.endsWith('/metadata-relations/query'))
      return rows([
        { id: 'main', metadataId: 'meta', relationRole: 'main' },
        { id: 'child', metadataId: 'child-meta', relationRole: 'child', parentMetadataId: 'meta' },
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
    throw new Error(`Unexpected ${path}`);
  });
  const openEditor = vi.fn((alias: string) => workspace.showEditor(workspace.session(alias)));
  const workspace = createMetadataWorkspace(
    { request } as HttpClient,
    () => identity,
    () => true,
    openEditor,
  );
  workspaces.push(workspace);
  const registry = createAssistantSurfaceRegistry(() => identity, workspace.current);
  registry.register({
    pageInstanceKey: 'shell',
    contextRevision: () => '',
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      capabilities: () => workspace.capabilities(async () => {}),
      requestTurn: vi.fn(),
    },
  });
  registry.activate('shell');
  const invoke = (code: string, input: unknown = {}) =>
    registry.invoke({ id: code, code, input }, registry.snapshot()!.token);
  const select = (moduleAlias = 'demo.order') =>
    invoke('configuration.select-metadata-module', { moduleAlias });
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
    invoke,
    select,
    add,
    identity(value: string) {
      identity = value;
      workspace.current();
    },
  };
}

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
