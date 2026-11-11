import { expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import { platformLanguageContext } from '@muyun/web-core';
import { createRecordReferencePickerProvider } from '@/platform-components/recordReferencePickerProvider';
import { parentRecordConstraints } from '@/platform-components/recordPickerConstraints';

const pageRequest = { keyword: '', pageNum: 1, pageSize: 50, scope: { selections: [] } };

it('remembers a reused in-flight search when it becomes the latest search again', async () => {
  let finish!: (page: { records: { id: string; title: string }[]; total: number }) => void;
  const pending = new Promise<{ records: { id: string; title: string }[]; total: number }>((resolve) => {
    finish = resolve;
  });
  const loadPage = vi
    .fn()
    .mockImplementation(({ keyword }) =>
      keyword === 'first' ? pending : Promise.resolve({ records: [], total: 0 }),
    );
  const provider = await createRecordReferencePickerProvider(
    { mode: 'list', context: { moduleAlias: 'demo.record' } as never, loadPage },
    platformLanguageContext,
  );
  const first = provider.searchPage({ ...pageRequest, keyword: 'first' });
  await provider.searchPage({ ...pageRequest, keyword: 'other' });
  const reused = provider.searchPage({ ...pageRequest, keyword: 'first' });
  finish({ records: [{ id: 'selected', title: 'Selected title' }], total: 1 });
  await Promise.all([first, reused]);
  expect(loadPage).toHaveBeenCalledTimes(2);
  expect(await provider.resolve(['selected'])).toMatchObject([{ id: 'selected', title: 'Selected title' }]);
});

it('does not let a late search overwrite titles used by subsequent historical resolution', async () => {
  let finish!: (page: { records: { id: string; title: string }[]; total: number }) => void;
  const late = new Promise<{ records: { id: string; title: string }[]; total: number }>((resolve) => {
    finish = resolve;
  });
  const query = vi
    .fn()
    .mockImplementation(({ quickSearch }) =>
      quickSearch === 'old'
        ? late
        : Promise.resolve({ records: [{ id: 'same', title: 'Current' }], total: 1 }),
    );
  const provider = await createRecordReferencePickerProvider(
    { mode: 'list', context: { crud: { query } } as never },
    platformLanguageContext,
  );
  const old = provider.searchPage({ ...pageRequest, keyword: 'old' });
  await provider.searchPage({ ...pageRequest, keyword: 'new' });
  finish({ records: [{ id: 'same', title: 'Previous' }], total: 1 });
  await old;
  expect(await provider.resolve(['same'])).toMatchObject([{ id: 'same', title: 'Current' }]);
});

it('keeps paged custom candidates and historical resolution inside the declared source', async () => {
  const query = vi.fn();
  const view = vi.fn();
  const loadPage = vi.fn().mockResolvedValue({ records: [{ id: 'other', title: 'Other' }], total: 160 });
  const resolveOptions = vi.fn().mockResolvedValue([{ id: 'saved', title: 'Saved', enabled: false }]);
  const provider = await createRecordReferencePickerProvider(
    {
      mode: 'list',
      context: { moduleAlias: 'demo.target', crud: { query, view } } as never,
      loadPage,
      resolveOptions,
    },
    platformLanguageContext,
  );
  const page = await provider.searchPage({ ...pageRequest, pageNum: 3 });
  expect(loadPage).toHaveBeenCalledWith({ ...pageRequest, pageNum: 3 });
  expect(page.total).toBe(160);
  expect(await provider.resolve(['saved'])).toMatchObject([{ id: 'saved', title: 'Saved', disabled: true }]);
  expect(resolveOptions).toHaveBeenCalledWith(['saved']);
  expect(query).not.toHaveBeenCalled();
  expect(view).not.toHaveBeenCalled();
});

it('does not infer unavailability or read target details from absence in a partial candidate page', async () => {
  const view = vi.fn();
  const provider = await createRecordReferencePickerProvider(
    {
      mode: 'list',
      context: { moduleAlias: 'demo.target', crud: { view } } as never,
      loadOptions: async () => [{ id: 'first', title: 'First' }],
    },
    platformLanguageContext,
  );
  expect(await provider.resolve(['outside-page'])).toEqual([
    { id: 'outside-page', title: 'outside-page', identifierFallback: true, disabled: true },
  ]);
  expect(view).not.toHaveBeenCalled();
});

it('preserves tree constraints and source-owned search without issuing target list queries', async () => {
  const query = vi.fn();
  const loadTree = vi.fn().mockResolvedValue([
    {
      record: { id: 'parent', title: 'Parent' },
      children: [{ record: { id: 'child', parentId: 'parent', title: 'Child' }, children: [] }],
    },
    { record: { id: 'other', title: 'Other' }, children: [] },
  ]);
  const state = reactive({ excluded: 'parent' });
  const provider = await createRecordReferencePickerProvider(
    {
      context: {
        moduleAlias: 'demo.tree',
        runtime: { ready: Promise.resolve() },
        abilities: { tryTree: () => ({ tree: vi.fn() }) },
        crud: { query },
      } as never,
      loadTree,
      constraints: [
        {
          code: 'parent',
          message: 'Cannot select parent or children',
          test: (record, context) =>
            parentRecordConstraints(state.excluded).every((constraint) => constraint.test(record, context)),
        },
      ],
    },
    platformLanguageContext,
  );
  const tree = await provider.loadTree!({ scope: { selections: [] } });
  expect(tree[0]!.record.disabled).toBe(true);
  expect(tree[0]!.children![0]!.record.disabled).toBe(true);
  state.excluded = 'other';
  expect(tree[0]!.record.disabled).toBe(false);
  expect(tree[1]!.record.disabled).toBe(true);
  expect(
    (await provider.searchPage({ ...pageRequest, keyword: 'Child' })).records.map((record) => record.id),
  ).toEqual(['parent', 'child']);
  expect(query).not.toHaveBeenCalled();
});
