import { expect, it, vi } from 'vitest';
import { createRelationDraftRegistry } from '@/dynamic-page-runtime/relationDraftController';
import { createRelationDraftAssistantCapabilities } from '@/dynamic-page-runtime/relationDraftAssistantCapabilities';
import type { RecordFormDraftAccess } from '@/dynamic-page-runtime/recordFormDraftAccess';

it('keeps every row reachable across pagination and oversized details', async () => {
  const registry = createRelationDraftRegistry();
  const update = vi.fn();
  const form: RecordFormDraftAccess = {
    editorMode: 'edit',
    editingRecord: {},
    referencePickerConfigs: {},
    formFields: new Map(
      Array.from({ length: 100 }, (_, index) => [
        `field${index}`,
        { fieldRef: { fieldName: `field${index}` }, label: '名称'.repeat(80), uiType: 'text' },
      ]),
    ),
    contextRevision: () => '1',
    updateDraftFields: update,
    updateDraftReference: vi.fn(),
  };
  const keys = Array.from({ length: 25 }, (_, index) => `row-${index}`);
  registry.register({
    code: 'lines',
    title: '明细',
    revision: () => '1',
    settle: async () => {},
    rowKeys: () => keys,
    form: (key) => (keys.includes(key) ? form : undefined),
    add: () => {
      throw new Error('not used');
    },
    remove: vi.fn(),
  });
  const capabilities = createRelationDraftAssistantCapabilities(registry, registry.revision);
  const context = {
    signal: new AbortController().signal,
    isCurrent: () => true,
    commitInternalState: <T>(commit: () => T) => commit(),
    applyEffect: <T>(commit: () => T) => commit(),
  };
  const invoke = async (code: string, input: unknown) => {
    const capability = capabilities().find((item) => item.descriptor.code === code)!;
    return capability.execute(capability.parseInput(input), context);
  };
  const first = (await invoke('relation.describe', {})) as {
    relations: Array<{ nextOffset: number; rows: Array<{ rowKey: string; detailsOmitted?: boolean }> }>;
  };
  expect(first.relations[0]!.rows).toHaveLength(20);
  expect(first.relations[0]!.rows[0]!.detailsOmitted).toBe(true);
  expect(first.relations[0]!.nextOffset).toBe(20);
  const last = (await invoke('relation.describe', { relationCode: 'lines', offset: 20 })) as typeof first;
  expect(last.relations[0]!.rows.map((row) => row.rowKey)).toEqual(keys.slice(20));
  expect(last.relations[0]!.nextOffset).toBeNull();
  await invoke('relation.select-row', { relationCode: 'lines', rowKey: 'row-24' });
  const selected = (await invoke('relation.form.describe', {})) as { fields: unknown[] };
  expect(selected.fields).toHaveLength(100);
  expect(update).not.toHaveBeenCalled();
  await expect(invoke('relation.describe', { offset: -1 })).rejects.toThrow('无效明细分页参数');
  await expect(invoke('relation.describe', { relationCode: 'missing' })).rejects.toThrow('明细已不可用');
});
