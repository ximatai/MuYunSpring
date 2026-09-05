import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import { useTreeLoader } from '@/vue-ui-antdv/useTreeLoader';
import type { UiTreeNode, UiTreeLoadRequest, UiTreeLoadResult } from '@/vue-ui-antdv/types';
const child = (key: string) => ({ key, title: key });
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => (resolve = done));
  return { promise, resolve };
}
const result = (key = 'child'): UiTreeLoadResult => ({
  mode: 'replace',
  nodes: [child(key)],
  hasMore: false,
});
function harness(
  loader: (node: UiTreeNode, request: UiTreeLoadRequest) => Promise<UiTreeLoadResult>,
  minimumDuration = 0,
) {
  const roots = ref<UiTreeNode[]>([
    { key: 'root', title: 'Root', isLeaf: false },
    { key: 'other', title: 'Other', isLeaf: false },
  ]);
  const version = ref(0);
  let model!: ReturnType<typeof useTreeLoader>;
  const wrapper = mount(
    defineComponent({
      setup() {
        model = useTreeLoader({
          nodes: () => roots.value,
          controlled: () => false,
          states: () => ({}),
          loader: () => loader,
          version: () => version.value,
          minimumDuration: () => minimumDuration,
          emit: () => {},
        });
        return () => null;
      },
    }),
  );
  return { model, roots, version, wrapper };
}
it('resets loaded markers and cached children together for a new authoritative version', async () => {
  const { model, roots, version } = harness(async () => result());
  await model.request('root', 'expand');
  expect(model.stateOf('root').status).toBe('loaded');
  roots.value = [{ key: 'root', title: 'New root', isLeaf: false }];
  version.value++;
  await flushPromises();
  expect(model.nodes.value[0].children).toBeUndefined();
  expect(model.stateOf('root').status).toBe('idle');
  await model.request('root', 'expand');
  expect(model.nodes.value[0].children).toEqual([child('child')]);
});
it('preserves independent branch requests and loaded children during an unrelated title update', async () => {
  const first = deferred<UiTreeLoadResult>();
  const second = deferred<UiTreeLoadResult>();
  const { model, roots } = harness(async (node) => (node.key === 'root' ? first.promise : second.promise));
  const a = model.request('root', 'expand');
  const b = model.request('other', 'expand');
  roots.value = roots.value.map((node) => ({ ...node, title: node.title + '!' }));
  await flushPromises();
  first.resolve(result('a'));
  second.resolve(result('b'));
  await Promise.all([a, b]);
  expect(model.nodes.value.map((node) => node.children?.[0].key)).toEqual(['a', 'b']);
});
it('coalesces concurrent identical requests', async () => {
  const load = deferred<UiTreeLoadResult>();
  const loader = vi.fn(async () => load.promise);
  const { model } = harness(loader);
  const a = model.request('root', 'refresh');
  const b = model.request('root', 'refresh');
  await flushPromises();
  expect(loader).toHaveBeenCalledTimes(1);
  load.resolve(result());
  await Promise.all([a, b]);
});
it.each(['reset', 'remove', 'unmount'] as const)(
  'cancels requests on %s and ignores a loader that resolves after cancellation',
  async (reason) => {
    const load = deferred<UiTreeLoadResult>();
    let signal!: AbortSignal;
    const { model, roots, version, wrapper } = harness(async (_node, request) => {
      signal = request.signal;
      return load.promise;
    });
    const request = model.request('root', 'expand');
    await flushPromises();
    if (reason === 'reset') version.value++;
    if (reason === 'remove') roots.value = [];
    if (reason === 'unmount') wrapper.unmount();
    await flushPromises();
    expect(signal.aborted).toBe(true);
    load.resolve(result('stale'));
    await request;
    expect(model.nodes.value.some((node) => node.children?.some((item) => item.key === 'stale'))).toBe(false);
  },
);
it('refresh supersedes pagination and retains usable content until success', async () => {
  const page = deferred<UiTreeLoadResult>();
  let pageSignal!: AbortSignal;
  let calls = 0;
  const { model } = harness(async (_node, request) => {
    if (request.reason === 'load-more') {
      pageSignal = request.signal;
      return page.promise;
    }
    calls++;
    return calls === 1 ? { ...result('first'), hasMore: true, nextCursor: 'next' } : result('refreshed');
  });
  await model.request('root', 'refresh');
  const pending = model.request('root', 'load-more');
  await flushPromises();
  expect(model.nodes.value[0].children).toEqual([child('first')]);
  await model.request('root', 'refresh');
  expect(pageSignal.aborted).toBe(true);
  page.resolve({ mode: 'append', nodes: [child('stale')], hasMore: false });
  await pending;
  expect(model.nodes.value[0].children).toEqual([child('refreshed')]);
});
it('retains failed page cursor, retries append, and closes terminal pagination', async () => {
  let fail = true;
  const requests: UiTreeLoadRequest[] = [];
  const { model } = harness(async (_node, request) => {
    requests.push(request);
    if (request.reason !== 'load-more') return { ...result('first'), hasMore: true, nextCursor: 'page2' };
    if (fail) throw new Error('offline');
    return { mode: 'append', nodes: [child('first'), child('last')], hasMore: false };
  });
  await model.request('root', 'refresh');
  await model.request('root', 'load-more');
  expect(model.stateOf('root')).toMatchObject({ status: 'error', cursor: 'page2', error: 'offline' });
  expect(model.nodes.value[0].children).toHaveLength(1);
  fail = false;
  await model.retry('root');
  await model.request('root', 'load-more');
  expect(requests).toHaveLength(3);
  expect(requests[2]).toMatchObject({ reason: 'load-more', cursor: 'page2' });
  expect(model.nodes.value[0].children).toEqual([child('first'), child('last')]);
  expect(model.stateOf('root')).toEqual({ status: 'loaded', hasMore: false, cursor: undefined });
});
it.each(['duplicate', 'cycle', 'cursor'] as const)(
  'reports invalid %s data without corrupting the current branch',
  async (kind) => {
    const { model } = harness(async () =>
      kind === 'duplicate'
        ? { mode: 'replace', nodes: [child('x'), child('x')], hasMore: false }
        : kind === 'cycle'
          ? result('root')
          : { ...result(), hasMore: true },
    );
    await model.request('root', 'refresh');
    expect(model.stateOf('root').status).toBe('error');
    expect(model.nodes.value[0].children).toBeUndefined();
  },
);
it('explicit branch release clears descendants and allows re-expansion', async () => {
  const { model } = harness(async () => result());
  await model.request('root', 'expand');
  model.release('root');
  expect(model.stateOf('root').status).toBe('idle');
  expect(model.nodes.value[0].children).toBeUndefined();
  await model.request('root', 'expand');
  expect(model.nodes.value[0].children).toHaveLength(1);
});
it('replaces cached descendants and cancels their requests when a parent refresh succeeds', async () => {
  let refreshing = false;
  let signal: AbortSignal | undefined;
  const pending = deferred<UiTreeLoadResult>();
  const { model } = harness(async (node, request) => {
    if (node.key === 'child') {
      signal = request.signal;
      return pending.promise;
    }
    return refreshing
      ? { mode: 'replace', nodes: [{ ...child('child'), children: [child('new')] }], hasMore: false }
      : result('child');
  });
  await model.request('root', 'refresh');
  const childLoad = model.request('child', 'refresh');
  await flushPromises();
  refreshing = true;
  await model.request('root', 'refresh');
  expect(signal?.aborted).toBe(true);
  pending.resolve(result('old'));
  await childLoad;
  expect(model.nodes.value[0].children?.[0].children).toEqual([child('new')]);
});
it('retries a failed first expansion and rejects a pagination cursor that does not advance', async () => {
  let fails = true;
  const { model } = harness(async (_node, request) => {
    if (fails) throw new Error('offline');
    return {
      mode: request.reason === 'load-more' ? 'append' : 'replace',
      nodes: [],
      hasMore: true,
      nextCursor: 'same',
    };
  });
  await model.request('root', 'expand');
  fails = false;
  await model.request('root', 'expand', true);
  expect(model.stateOf('root').status).toBe('loaded');
  await model.request('root', 'load-more');
  expect(model.stateOf('root')).toMatchObject({ status: 'error', cursor: 'same' });
});

it('honors the minimum busy duration for failures and releases the delay on cancellation', async () => {
  vi.useFakeTimers();
  try {
    const { model, version } = harness(async () => {
      throw new Error('offline');
    }, 300);
    const first = model.request('root', 'expand');
    await flushPromises();
    expect(model.stateOf('root').status).toBe('loading');
    await vi.advanceTimersByTimeAsync(300);
    await first;
    expect(model.stateOf('root').status).toBe('error');
    const retry = model.retry('root');
    await flushPromises();
    version.value++;
    await flushPromises();
    await retry;
    expect(model.stateOf('root').status).toBe('idle');
  } finally {
    vi.useRealTimers();
  }
});
