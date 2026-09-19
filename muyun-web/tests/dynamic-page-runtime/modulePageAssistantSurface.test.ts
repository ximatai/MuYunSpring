import { describe, expect, it, vi } from 'vitest';
import {
  createModulePageAssistantSurface,
  modulePageAssistantContextRevision,
} from '@muyun/dynamic-page-runtime';
import type { ModulePageSessionView } from '@/dynamic-page-runtime/useModulePageSession';

function viewFixture(): ModulePageSessionView {
  return {
    modulePageTitle: 'Daily report',
    context: { moduleAlias: 'work.daily_report' },
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
    expect(before).toBe('7');

    view.assistantContextRevision += 1;

    expect(modulePageAssistantContextRevision(view)).not.toBe(before);
  });

  it('does not advertise writable fields while the page is outside an edit session', async () => {
    const view = viewFixture();
    view.editingRecord = undefined;
    view.editorMode = 'view';
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
