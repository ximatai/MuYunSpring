import { describe, expect, it, vi } from 'vitest';
import {
  createModulePageAssistantSurface,
  modulePageAssistantContextRevision,
} from '@muyun/dynamic-page-runtime';
import { createAssistantSurfaceRegistry } from '@muyun/web-core';
import type { ModulePageSessionView } from '@/dynamic-page-runtime/useModulePageSession';

function viewFixture(): ModulePageSessionView {
  return {
    modulePageTitle: 'Daily report',
    context: { moduleAlias: 'work.daily_report', can: vi.fn(() => false) },
    editorMode: 'edit',
    selectedRecord: { id: 'record-1', version: 2 },
    editingRecord: { id: 'record-1', version: 2, summary: 'before', computed: 'old' },
    detailDirty: false,
    formSessionKey: 3,
    assistantContextRevision: 7,
    formFields: new Map([
      [
        'summary',
        {
          fieldName: 'summary',
          label: 'Summary',
          required: true,
          readOnly: false,
          visible: true,
          controlType: 'text',
          columnSpan: 1,
          hasOption: false,
        },
      ],
      [
        'computed',
        {
          fieldName: 'computed',
          label: 'Computed',
          required: false,
          readOnly: { constant: true },
          visible: true,
          controlType: 'text',
          columnSpan: 1,
          hasOption: false,
        },
      ],
    ]),
    updateDraftField: vi.fn(),
  } as unknown as ModulePageSessionView;
}

describe('module page assistant surface', () => {
  it('projects a narrow page context and patches through the standard field entry', async () => {
    const view = viewFixture();
    const surface = createModulePageAssistantSurface(view, vi.fn());
    const patch = surface
      .capabilities()
      .find((capability) => capability.descriptor.code === 'form.patch-draft')!;

    const input = patch.parseInput({ fieldName: 'summary', value: 'after' });
    await patch.execute(input, executionContext());

    expect(view.updateDraftField).toHaveBeenCalledWith('summary', 'after');
    expect(surface.describe().facts).toEqual(
      expect.objectContaining({ moduleAlias: 'work.daily_report', editing: true }),
    );
  });

  it('rejects unknown and read-only fields before changing any draft value', async () => {
    const view = viewFixture();
    const patch = createModulePageAssistantSurface(view, vi.fn())
      .capabilities()
      .find((capability) => capability.descriptor.code === 'form.patch-draft')!;

    await expect(
      patch.execute(patch.parseInput({ fieldName: 'computed', value: 'override' }), executionContext()),
    ).rejects.toThrow('Form field is not editable by the assistant: computed');
    expect(view.updateDraftField).not.toHaveBeenCalled();
  });

  it('uses an opaque session revision instead of serializing draft values', () => {
    const view = viewFixture();
    const before = modulePageAssistantContextRevision(view);
    view.editingRecord = { ...view.editingRecord!, summary: 'secret manual edit' };

    expect(modulePageAssistantContextRevision(view)).toBe(before);
    expect(before).toBe('7:-');

    view.assistantContextRevision += 1;

    expect(modulePageAssistantContextRevision(view)).not.toBe(before);
  });

  it('adapts the mounted standard list query controller without owning query state', async () => {
    const view = viewFixture();
    const snapshot = {
      mode: 'normal' as const,
      status: 'ready' as const,
      quickSearchEnabled: true,
      quickSearchFields: [{ name: 'title', title: 'Title', valueType: 'STRING' as const }],
      pageNum: 1,
      pageSize: 20,
      total: 1,
      totalKnown: true,
      rows: [
        {
          id: 'record-1',
          cells: [{ fieldName: 'title', title: 'Title', value: 'Daily report' }],
        },
      ],
      truncated: false,
    };
    view.listQueryController = {
      revision: () => 4,
      snapshot: () => snapshot,
      applyQuickSearch: vi.fn(async () => ({ ...snapshot, appliedQuickSearch: 'daily' })),
    };
    const surface = createModulePageAssistantSurface(view, vi.fn());
    const describe = surface.capabilities().find(({ descriptor }) => descriptor.code === 'query.describe')!;
    const apply = surface
      .capabilities()
      .find(({ descriptor }) => descriptor.code === 'query.apply-quick-search')!;

    await expect(describe.execute(describe.parseInput({}), executionContext())).resolves.toEqual(snapshot);
    await expect(apply.execute(apply.parseInput({ keyword: 'daily' }), executionContext())).resolves.toEqual(
      expect.objectContaining({ appliedQuickSearch: 'daily' }),
    );
    expect(view.listQueryController.applyQuickSearch).toHaveBeenCalledWith('daily');
    expect(modulePageAssistantContextRevision(view)).toBe('7:4');
  });

  it('opens standard create and visible-record edit sessions without exposing save', async () => {
    const view = viewFixture();
    view.editingRecord = { id: 'record-1', version: 2 };
    view.editorMode = 'view';
    view.context.can = vi.fn(() => true);
    view.prepareAssistantCreate = vi.fn(async () => () => ({
      editorMode: 'create' as const,
      recordId: undefined,
      editable: true,
      dirty: false,
    }));
    view.prepareAssistantEdit = vi.fn(async (recordId: string) => () => ({
      editorMode: 'edit' as const,
      recordId,
      editable: true,
      dirty: false,
    }));
    const snapshot = {
      mode: 'normal' as const,
      status: 'ready' as const,
      quickSearchEnabled: false,
      quickSearchFields: [],
      pageNum: 1,
      pageSize: 20,
      total: 1,
      totalKnown: true,
      rows: [{ id: 'record-2', cells: [] }],
      truncated: false,
    };
    view.listQueryController = {
      revision: () => 0,
      snapshot: () => snapshot,
      applyQuickSearch: vi.fn(),
    };
    const capabilities = createModulePageAssistantSurface(view, vi.fn()).capabilities();
    const create = capabilities.find(({ descriptor }) => descriptor.code === 'record.start-create')!;
    const edit = capabilities.find(({ descriptor }) => descriptor.code === 'record.start-edit')!;

    await expect(create.execute(create.parseInput({}), executionContext())).resolves.toMatchObject({
      editorMode: 'create',
      editable: true,
    });
    await expect(
      edit.execute(edit.parseInput({ recordId: 'record-2' }), executionContext()),
    ).resolves.toMatchObject({ editorMode: 'edit', recordId: 'record-2', editable: true });
    expect(() => edit.parseInput({ recordId: 'record-outside-page' })).toThrow(
      'record.start-edit requires a recordId from the current page',
    );
    expect(capabilities.map(({ descriptor }) => descriptor.code)).not.toContain('record.save');
  });

  it('invalidates a pending editor transition when the user changes context after it starts', async () => {
    const view = viewFixture();
    view.editingRecord = { id: 'record-1', version: 2 };
    view.editorMode = 'view';
    view.context.can = vi.fn((action: string) => action === 'create');
    let resolveCreate!: () => void;
    const commit = vi.fn(() => ({
      editorMode: 'create' as const,
      recordId: undefined,
      editable: true,
      dirty: false,
    }));
    view.prepareAssistantCreate = vi.fn(
      () =>
        new Promise<typeof commit>((resolve) => {
          resolveCreate = () => resolve(commit);
        }),
    );
    const registry = createAssistantSurfaceRegistry();
    registry.register({
      pageInstanceKey: 'page-1',
      contextRevision: () => modulePageAssistantContextRevision(view),
      surface: createModulePageAssistantSurface(view, vi.fn()),
    });
    registry.activate('page-1');
    const invocation = registry.invoke(
      { id: 'create-1', code: 'record.start-create', input: {} },
      registry.snapshot()!.token,
    );
    await Promise.resolve();
    view.assistantContextRevision += 1;
    resolveCreate();

    await expect(invocation).rejects.toThrow('Assistant invocation no longer matches');
    expect(commit).not.toHaveBeenCalled();
  });

  it('settles an asynchronous query effect before binding its post-effect revision', async () => {
    const view = viewFixture();
    let revision = 0;
    const snapshot = {
      mode: 'normal' as const,
      status: 'ready' as const,
      quickSearchEnabled: true,
      quickSearchFields: [{ name: 'title', title: 'Title', valueType: 'STRING' as const }],
      pageNum: 1,
      pageSize: 20,
      total: 0,
      totalKnown: true,
      rows: [],
      truncated: false,
    };
    view.listQueryController = {
      revision: () => revision,
      snapshot: () => snapshot,
      applyQuickSearch: vi.fn(async () => {
        revision += 1;
        await Promise.resolve();
        return { ...snapshot, appliedQuickSearch: 'daily' };
      }),
    };
    const registry = createAssistantSurfaceRegistry();
    registry.register({
      pageInstanceKey: 'page-1',
      contextRevision: () => modulePageAssistantContextRevision(view),
      surface: createModulePageAssistantSurface(view, vi.fn()),
    });
    registry.activate('page-1');
    const token = registry.snapshot()!.token;

    await expect(
      registry.invoke(
        { id: 'query-1', code: 'query.apply-quick-search', input: { keyword: 'daily' } },
        token,
      ),
    ).resolves.toEqual({
      value: expect.objectContaining({ appliedQuickSearch: 'daily' }),
      contextChanged: true,
    });
  });

  it('rejects a query result when another page context change happens while it is pending', async () => {
    const view = viewFixture();
    let revision = 0;
    let resolveQuery!: () => void;
    const snapshot = {
      mode: 'normal' as const,
      status: 'ready' as const,
      quickSearchEnabled: true,
      quickSearchFields: [{ name: 'title', title: 'Title', valueType: 'STRING' as const }],
      pageNum: 1,
      pageSize: 20,
      total: 0,
      totalKnown: true,
      rows: [],
      truncated: false,
    };
    view.listQueryController = {
      revision: () => revision,
      snapshot: () => snapshot,
      applyQuickSearch: vi.fn(() => {
        revision += 1;
        return new Promise<typeof snapshot & { appliedQuickSearch: string }>((resolve) => {
          resolveQuery = () => resolve({ ...snapshot, appliedQuickSearch: 'daily' });
        });
      }),
    };
    const registry = createAssistantSurfaceRegistry();
    registry.register({
      pageInstanceKey: 'page-1',
      contextRevision: () => modulePageAssistantContextRevision(view),
      surface: createModulePageAssistantSurface(view, vi.fn()),
    });
    registry.activate('page-1');
    const invocation = registry.invoke(
      { id: 'query-1', code: 'query.apply-quick-search', input: { keyword: 'daily' } },
      registry.snapshot()!.token,
    );
    await Promise.resolve();
    view.assistantContextRevision += 1;
    resolveQuery();

    await expect(invocation).rejects.toThrow('Assistant invocation no longer matches');
  });

  it('does not expose quick-search mutation when the standard list disables it', () => {
    const view = viewFixture();
    view.listQueryController = {
      revision: () => 1,
      snapshot: () => ({
        mode: 'normal',
        status: 'ready',
        quickSearchEnabled: false,
        quickSearchFields: [],
        pageNum: 1,
        pageSize: 20,
        total: 0,
        totalKnown: true,
        rows: [],
        truncated: false,
      }),
      applyQuickSearch: vi.fn(),
    };

    const capabilityCodes = createModulePageAssistantSurface(view, vi.fn())
      .capabilities()
      .map(({ descriptor }) => descriptor.code);

    expect(capabilityCodes).toContain('query.describe');
    expect(capabilityCodes).not.toContain('query.apply-quick-search');
  });

  it('does not advertise writable fields while the page is outside an edit session', async () => {
    const view = viewFixture();
    view.editorMode = 'view';
    view.editingRecord = { id: 'record-1', version: 2, summary: 'read-only detail' };
    const surface = createModulePageAssistantSurface(view, vi.fn());
    const describe = surface.capabilities().find(({ descriptor }) => descriptor.code === 'form.describe')!;

    const description = (await describe.execute(describe.parseInput({}), executionContext())) as {
      editable: boolean;
      fields: Array<{ fieldName: string; assistantWritable: boolean }>;
    };

    expect(description.editable).toBe(false);
    expect(description.fields).toContainEqual(
      expect.objectContaining({ fieldName: 'summary', assistantWritable: false }),
    );
    expect(surface.capabilities()).not.toContainEqual(
      expect.objectContaining({ descriptor: expect.objectContaining({ code: 'form.patch-draft' }) }),
    );
  });

  it('does not expose record editing while the list is in recycle-bin mode', () => {
    const view = viewFixture();
    view.editorMode = 'view';
    view.editingRecord = { id: 'deleted-record' };
    view.context.can = vi.fn(() => true);
    view.listQueryController = {
      revision: () => 0,
      snapshot: () => ({
        mode: 'recycleBin',
        status: 'ready',
        quickSearchEnabled: false,
        quickSearchFields: [],
        pageNum: 1,
        pageSize: 20,
        total: 1,
        totalKnown: true,
        rows: [{ id: 'deleted-record', cells: [] }],
        truncated: false,
      }),
      applyQuickSearch: vi.fn(),
    };

    const capabilityCodes = createModulePageAssistantSurface(view, vi.fn())
      .capabilities()
      .map(({ descriptor }) => descriptor.code);
    expect(capabilityCodes).not.toContain('record.start-edit');
    expect(capabilityCodes).not.toContain('record.start-create');
  });

  it('does not expose editor transitions from a failed edit session', () => {
    const view = viewFixture();
    view.editorMode = 'edit';
    view.editingRecord = undefined;
    view.detailLoading = false;
    view.detailLoadFailed = true;
    view.context.can = vi.fn(() => true);

    const capabilityCodes = createModulePageAssistantSurface(view, vi.fn())
      .capabilities()
      .map(({ descriptor }) => descriptor.code);

    expect(capabilityCodes).not.toContain('record.start-create');
    expect(capabilityCodes).not.toContain('record.start-edit');
  });

  it('hides password fields and rejects direct reference identifiers', async () => {
    const view = viewFixture();
    view.formFields.set('apiKey', {
      fieldName: 'apiKey',
      label: 'API key',
      required: true,
      readOnly: false,
      visible: true,
      controlType: 'text',
      fieldControl: { alias: 'password' },
      columnSpan: 1,
      hasOption: false,
    } as never);
    view.formFields.set('ownerId', {
      fieldName: 'ownerId',
      label: 'Owner',
      required: false,
      readOnly: false,
      visible: true,
      controlType: 'recordPicker',
      reference: { cardinality: 'ONE', targetModuleAlias: 'platform.user' },
      columnSpan: 1,
      hasOption: false,
    } as never);
    const surface = createModulePageAssistantSurface(view, vi.fn());
    const describe = surface.capabilities().find(({ descriptor }) => descriptor.code === 'form.describe')!;
    const patch = surface.capabilities().find(({ descriptor }) => descriptor.code === 'form.patch-draft')!;

    const description = (await describe.execute(describe.parseInput({}), executionContext())) as {
      fields: Array<{ fieldName: string; assistantWritable: boolean }>;
    };
    expect(description.fields).not.toContainEqual(expect.objectContaining({ fieldName: 'apiKey' }));
    expect(description.fields).toContainEqual(
      expect.objectContaining({ fieldName: 'ownerId', assistantWritable: false }),
    );
    await expect(
      patch.execute(patch.parseInput({ fieldName: 'ownerId', value: 'guessed-id' }), executionContext()),
    ).rejects.toThrow('Form field is not editable by the assistant: ownerId');
  });

  it('validates values with the standard field type and declared option candidates', async () => {
    const view = viewFixture();
    view.formFields.set('workDate', {
      fieldName: 'workDate',
      label: 'Work date',
      required: true,
      readOnly: false,
      visible: true,
      controlType: 'dateInput',
      fieldControl: { alias: 'date', rendererType: 'DATE', valueShape: 'SCALAR' },
      valueType: 'DATE',
      columnSpan: 1,
      hasOption: false,
    } as never);
    view.formFields.set('status', {
      fieldName: 'status',
      label: 'Status',
      required: true,
      readOnly: false,
      visible: true,
      controlType: 'select',
      fieldControl: { alias: 'select', rendererType: 'SELECT', valueShape: 'SCALAR' },
      option: {
        selectionMode: 'SINGLE',
        binding: { sourceType: 'enum', source: 'Status' },
        inlineItems: [{ code: 'DONE', title: 'Done', enabled: true }],
      },
      columnSpan: 1,
      hasOption: true,
    } as never);
    view.formFields.set('submittedAt', {
      fieldName: 'submittedAt',
      label: 'Submitted at',
      required: true,
      readOnly: false,
      visible: true,
      controlType: 'dateTimeInput',
      fieldControl: { alias: 'datetime', rendererType: 'DATETIME', valueShape: 'SCALAR' },
      valueType: 'TIMESTAMP',
      columnSpan: 1,
      hasOption: false,
    } as never);
    const patch = createModulePageAssistantSurface(view, vi.fn())
      .capabilities()
      .find(({ descriptor }) => descriptor.code === 'form.patch-draft')!;

    await expect(
      patch.execute(patch.parseInput({ fieldName: 'workDate', value: '2026-02-30' }), executionContext()),
    ).rejects.toThrow('Invalid value for form field: workDate');
    await expect(
      patch.execute(patch.parseInput({ fieldName: 'status', value: 'INVENTED' }), executionContext()),
    ).rejects.toThrow('Invalid value for form field: status');
    await expect(
      patch.execute(
        patch.parseInput({ fieldName: 'submittedAt', value: '2026-02-30T12:00' }),
        executionContext(),
      ),
    ).rejects.toThrow('Invalid value for form field: submittedAt');
    await patch.execute(patch.parseInput({ fieldName: 'status', value: 'DONE' }), executionContext());
    expect(view.updateDraftField).toHaveBeenCalledWith('status', 'DONE');
  });
});

function executionContext() {
  return {
    signal: new AbortController().signal,
    isCurrent: () => true,
    applyEffect<T>(effect: () => T) {
      return effect();
    },
  };
}
