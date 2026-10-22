import { effectScope, nextTick, ref, type Ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useRecordReadSession } from '@/web-core/useRecordReadSession';
import type { RecordReadQuery, RecordReadView } from '@/web-core/recordReadView';
import type { ResolvedModuleUiDescriptor, WebPageResponse, WebQueryRequest } from '@/web-contracts';

type Row = { id: string; origin: string; title: string };
const scopes: ReturnType<typeof effectScope>[] = [];
afterEach(() => scopes.splice(0).forEach((scope) => scope.stop()));
function page(records: Row[], pageNum = 1): WebPageResponse<Row> {
  return { records, total: 41, totalKnown: true, pageNum, pageSize: 20, pages: 3 };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
function view(overrides: Partial<RecordReadView<Row>> = {}): RecordReadView<Row> {
  return {
    sourceId: 'business.receipts',
    viewCode: 'current',
    scopeKey: 'tenant-a:user-a',
    descriptor: { page: { list: { fields: { fields: [] } } } } as unknown as ResolvedModuleUiDescriptor,
    recordKey: (row) => `${row.origin}:${row.id}`,
    query: vi.fn(async () => page([{ id: '1', origin: 'loan', title: 'First' }])),
    detail: { title: (row) => row.title, load: vi.fn(async (row) => ({ title: row.title })) },
    ...overrides,
  };
}
function mount(source: Ref<RecordReadView<Row>>, query: Ref<RecordReadQuery> = ref({})) {
  const scope = effectScope();
  scopes.push(scope);
  return scope.run(() => useRecordReadSession({ view: source, query }))!;
}
const settle = async () => {
  await nextTick();
  await Promise.resolve();
  await Promise.resolve();
};

describe('authorized read projection sessions', () => {
  it.each([
    { pageNum: 0 },
    { pageSize: NaN },
    { total: -1 },
    { pages: 0.5 },
    { totalKnown: undefined },
    { records: [null] },
    { records: null },
    { pageSize: 1, records: [{ id: '1' }, { id: '2' }] },
  ])('rejects malformed adapter pages without corrupting pagination: %j', async (invalid) => {
    const source = ref(view());
    const session = mount(source);
    await settle();
    source.value.query = async () => ({ ...page([]), ...invalid }) as WebPageResponse<Row>;
    await session.refresh();
    expect(session.result.value).toBeUndefined();
    expect(session.error.value).toBeInstanceOf(Error);
    expect(session.pageNum.value).toBe(1);
    expect(session.pageSize.value).toBe(20);
    source.value.query = async () => ({ ...page([]), total: 0, pages: 0 });
    await session.refresh();
    expect(session.result.value?.records).toEqual([]);
    expect(session.error.value).toBeUndefined();
  });

  it('accepts unknown totals without synthesizing an exact count', async () => {
    const response = { ...page([]), total: 40, totalKnown: false };
    const session = mount(ref(view({ query: async () => response })));
    await settle();
    expect(session.result.value).toEqual(response);
  });

  it('contains adapter key failures during refresh and permits recovery', async () => {
    const source = ref(view());
    const session = mount(source);
    await settle();
    await session.select(session.result.value!.records[0]!);
    source.value.recordKey = () => {
      throw new Error('Broken adapter');
    };
    await expect(session.refresh()).resolves.toBeUndefined();
    expect(session.loading.value).toBe(false);
    expect(session.result.value).toBeUndefined();
    expect(session.error.value).toBeInstanceOf(Error);
    source.value = view();
    await settle();
    expect(session.result.value?.records).toHaveLength(1);
  });

  it('does not expose malformed detail projections and supports retry', async () => {
    const source = ref(view());
    const session = mount(source);
    await settle();
    source.value.detail!.load = async () => null as unknown as Record<string, unknown>;
    await session.select(session.result.value!.records[0]!);
    expect(session.detail.value).toBeUndefined();
    expect(session.detailError.value).toBeInstanceOf(Error);
    source.value.detail!.load = async () => ({ title: 'Recovered' });
    await session.select(session.result.value!.records[0]!);
    expect(session.detail.value?.title).toBe('Recovered');
    expect(session.detailError.value).toBeUndefined();
  });

  it('does not reopen a detail explicitly closed while a refresh is pending', async () => {
    const source = ref(view());
    const session = mount(source);
    await settle();
    await session.select(session.result.value!.records[0]!);
    const pending = deferred<WebPageResponse<Row>>();
    source.value.query = () => pending.promise;
    const refreshing = session.refresh();
    session.closeDetail();
    pending.resolve(page([{ id: '1', origin: 'loan', title: 'Refreshed' }]));
    await refreshing;
    expect(session.result.value?.records[0]?.title).toBe('Refreshed');
    expect(session.selected.value).toBeUndefined();
  });

  it('keeps page-size preference while resetting position and filters across scopes', async () => {
    const query = vi.fn(async (request: WebQueryRequest) => ({
      ...page([]),
      pageNum: request.page!.pageNum,
      pageSize: request.page!.pageSize,
    }));
    const source = ref(view({ query }));
    const session = mount(source);
    await settle();
    await session.resizePage(50);
    await session.search('private text');
    await session.goPage(2);
    source.value = view({ scopeKey: 'tenant-b:user-b', query });
    await settle();
    expect(query).toHaveBeenLastCalledWith(
      { page: { pageNum: 1, pageSize: 50 }, quickSearch: undefined },
      expect.anything(),
    );
    expect(session.quickSearch.value).toBe('');
  });

  it('preserves query position when a view presentation is refreshed within the same identity', async () => {
    const source = ref(
      view({
        query: async (request) =>
          page([{ id: '1', origin: 'loan', title: 'Current' }], request.page!.pageNum),
      }),
    );
    const session = mount(source);
    await settle();
    await session.search('filter');
    await session.goPage(2);
    const query = vi.fn(async (request: WebQueryRequest) =>
      page([{ id: '1', origin: 'loan', title: 'Translated' }], request.page!.pageNum),
    );
    source.value = view({ query });
    await settle();
    expect(query).toHaveBeenLastCalledWith(
      expect.objectContaining({ quickSearch: 'filter', page: { pageNum: 2, pageSize: 20 } }),
      expect.anything(),
    );
    expect(session.quickSearch.value).toBe('filter');
    expect(session.result.value?.records[0]?.title).toBe('Translated');
  });

  it('fails closed for missing descriptors and rejects invalid pagination before querying', async () => {
    const source = ref(view({ descriptor: {} as ResolvedModuleUiDescriptor }));
    const session = mount(source);
    await settle();
    expect(source.value.query).not.toHaveBeenCalled();
    expect(session.error.value).toBeInstanceOf(Error);
    expect(() => session.goPage(NaN)).toThrow(RangeError);
    expect(() => session.resizePage(0)).toThrow(RangeError);
  });

  it('owns pagination, search, detail and refresh without requiring any mutation or module runtime', async () => {
    const source = ref(view());
    const session = mount(source);
    await settle();
    expect(source.value.query).toHaveBeenCalledTimes(1);
    expect(source.value.query).toHaveBeenLastCalledWith(
      { page: { pageNum: 1, pageSize: 20 }, quickSearch: undefined },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    await session.select(session.result.value!.records[0]!);
    expect(session.detail.value).toEqual({ title: 'First' });
    await session.refresh();
    await settle();
    expect(session.selected.value?.title).toBe('First');
    await session.search('needle');
    expect(session.selected.value).toBeUndefined();
    expect(source.value.query).toHaveBeenLastCalledWith(
      expect.objectContaining({ quickSearch: 'needle', page: { pageNum: 1, pageSize: 20 } }),
      expect.anything(),
    );
    await session.goPage(2);
    expect(source.value.query).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: { pageNum: 2, pageSize: 20 } }),
      expect.anything(),
    );
    await session.resizePage(50);
    expect(source.value.query).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: { pageNum: 1, pageSize: 50 } }),
      expect.anything(),
    );
  });

  it('isolates named views of one source and rejects delayed query and detail responses', async () => {
    const late = deferred<WebPageResponse<Row>>();
    const detail = deferred<Record<string, unknown>>();
    const source = ref(view({ detail: { title: (row) => row.title, load: () => detail.promise } }));
    const session = mount(source);
    await settle();
    const selection = session.select(session.result.value!.records[0]!);
    source.value.query = vi.fn(() => late.promise);
    const refreshing = session.refresh();
    await Promise.resolve();
    const signal = vi.mocked(source.value.query).mock.calls[0]![1].signal;
    source.value = view({
      viewCode: 'history',
      query: vi.fn(async () => page([{ id: '1', origin: 'handover', title: 'History' }])),
    });
    expect(signal.aborted).toBe(true);
    expect(session.selected.value).toBeUndefined();
    await settle();
    late.resolve(page([{ id: '1', origin: 'loan', title: 'Stale' }]));
    detail.resolve({ title: 'Stale detail' });
    await Promise.all([refreshing, selection]);
    expect(session.result.value?.records[0]?.title).toBe('History');
    expect(session.detail.value).toBeUndefined();
    expect(session.quickSearch.value).toBe('');
  });

  it('clears old facts synchronously on principal/range changes even if transports ignore abort', async () => {
    const source = ref(view());
    const session = mount(source);
    await settle();
    await session.select(session.result.value!.records[0]!);
    const late = deferred<WebPageResponse<Row>>();
    source.value.query = () => late.promise;
    source.value.scopeKey = 'tenant-b:user-b';
    expect(session.result.value).toBeUndefined();
    expect(session.selected.value).toBeUndefined();
    source.value = view({
      scopeKey: 'tenant-b:user-c',
      query: async () => {
        throw new Error('Forbidden');
      },
    });
    await settle();
    late.resolve(page([{ id: '1', origin: 'loan', title: 'Other tenant' }]));
    await settle();
    expect(session.result.value).toBeUndefined();
    expect(session.error.value).toBeInstanceOf(Error);
  });

  it('allows equal original IDs across origins but rejects ambiguous row identities', async () => {
    const records = [
      { id: '1', origin: 'a', title: 'A' },
      { id: '1', origin: 'b', title: 'B' },
    ];
    const source = ref(view({ query: async () => page(records) }));
    const session = mount(source);
    await settle();
    expect(session.result.value?.records).toHaveLength(2);
    source.value = view({ recordKey: (row) => row.id, query: async () => page(records) });
    await settle();
    expect(session.result.value).toBeUndefined();
    expect(String(session.error.value)).toContain('unique');
  });

  it('does not display old rows after query failures and ignores responses after disposal', async () => {
    const source = ref(view());
    const session = mount(source);
    await settle();
    source.value.query = async () => {
      throw new Error('Unavailable');
    };
    await session.refresh();
    expect(session.result.value).toBeUndefined();
    const late = deferred<WebPageResponse<Row>>();
    source.value.query = () => late.promise;
    const pending = session.refresh();
    scopes[0]!.stop();
    late.resolve(page([{ id: '2', origin: 'loan', title: 'Disposed' }]));
    await pending;
    expect(session.result.value).toBeUndefined();
  });

  it('resets pages and selection for changed filters without mutating caller-owned criteria', async () => {
    const source = ref(view());
    const query = ref({ conditions: [{ fieldName: 'state', operator: 'EQ' as const, values: ['current'] }] });
    const session = mount(source, query);
    await settle();
    await session.goPage(2);
    query.value.conditions[0]!.values = ['history'];
    await settle();
    expect(source.value.query).toHaveBeenLastCalledWith(
      expect.objectContaining({ conditions: query.value.conditions, page: { pageNum: 1, pageSize: 20 } }),
      expect.anything(),
    );
    expect(query.value.conditions[0]!.values).toEqual(['history']);
  });
});
