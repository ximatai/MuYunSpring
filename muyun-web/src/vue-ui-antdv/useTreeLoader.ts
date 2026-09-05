import { computed, onBeforeUnmount, shallowRef, watch } from 'vue';
import { indexTree, mergeTreePage } from './treeStructure';
import type {
  UiTreeNode,
  UiTreeLoadRequest,
  UiTreeLoadResult,
  UiTreeBranchState,
  UiTreeLoadIntent,
  UiTreeLoadReason,
} from './types';

interface Options {
  nodes: () => UiTreeNode[];
  controlled: () => boolean;
  states: () => Readonly<Record<string, UiTreeBranchState>>;
  loader: () => ((node: UiTreeNode, request: UiTreeLoadRequest) => Promise<UiTreeLoadResult>) | undefined;
  version: () => unknown;
  minimumDuration: () => number;
  emit: (request: UiTreeLoadIntent) => void;
}

/** Managed requests and controlled intents never share cancellation or loaded bookkeeping. */
export function useTreeLoader(input: Pick<Options, 'nodes'> & Partial<Omit<Options, 'nodes'>>) {
  const options: Options = {
    controlled: () => false,
    states: () => ({}),
    loader: () => undefined,
    version: () => undefined,
    minimumDuration: () => 300,
    emit: () => {},
    ...input,
  };
  const cache = shallowRef(new Map<string, UiTreeNode[]>());
  const localStates = shallowRef<Record<string, UiTreeBranchState>>({});
  const pending = new Map<
    string,
    { controller: AbortController; reason: UiTreeLoadReason; promise: Promise<void> }
  >();
  let sequence = 0;
  const states = computed(() => (options.controlled() ? options.states() : localStates.value));
  const nodes = computed(() => {
    if (options.controlled()) {
      indexTree(options.nodes());
      return options.nodes();
    }
    function merge(items: UiTreeNode[]): UiTreeNode[] {
      return items.map((node) => {
        const children = cache.value.get(node.key) ?? node.children;
        return children === undefined
          ? node
          : {
              ...node,
              children: merge(children),
              ...(cache.value.has(node.key)
                ? { isLeaf: children.length === 0 && localStates.value[node.key]?.hasMore !== true }
                : {}),
            };
      });
    }
    indexTree(options.nodes());
    const result = merge(options.nodes());
    indexTree(result);
    return result;
  });
  const stateOf = (key: string): UiTreeBranchState => states.value[key] ?? { status: 'idle' };
  function setState(key: string, state: UiTreeBranchState) {
    localStates.value = { ...localStates.value, [key]: state };
  }
  function abort(key: string) {
    pending.get(key)?.controller.abort();
    pending.delete(key);
  }
  function reset() {
    [...pending.keys()].forEach(abort);
    cache.value = new Map();
    localStates.value = {};
  }
  watch([options.version, options.controlled], reset);
  watch(nodes, (items) => {
    const keys = indexTree(items);
    const retained = { ...localStates.value };
    let pruned = false;
    for (const key of Object.keys(retained)) {
      if (!keys.has(key)) {
        abort(key);
        delete retained[key];
        pruned = true;
      }
    }
    if (pruned) localStates.value = retained;
    const next = new Map([...cache.value].filter(([key]) => keys.has(key)));
    if (next.size !== cache.value.size) cache.value = next;
  });
  onBeforeUnmount(reset);

  function release(key: string) {
    const entries = indexTree(nodes.value);
    const descendants = new Set([key]);
    entries.forEach((entry, id) => {
      if (entry.parent && descendants.has(entry.parent)) descendants.add(id);
    });
    const next = new Map(cache.value);
    const nextStates = { ...localStates.value };
    descendants.forEach((id) => {
      abort(id);
      next.delete(id);
      delete nextStates[id];
    });
    cache.value = next;
    localStates.value = nextStates;
  }

  async function waitMinimum(started: number, signal: AbortSignal) {
    const remaining = options.minimumDuration() - (Date.now() - started);
    if (remaining <= 0 || signal.aborted) return;
    await new Promise<void>((resolve) => {
      const timer = setTimeout(done, remaining);
      function done() {
        clearTimeout(timer);
        signal.removeEventListener('abort', done);
        resolve();
      }
      signal.addEventListener('abort', done, { once: true });
    });
  }

  function request(key: string, reason: UiTreeLoadReason, retry = false): Promise<void> {
    const node = indexTree(nodes.value).get(key)?.node;
    if (!node) return Promise.resolve();
    const state = stateOf(key);
    if (!retry && reason === 'expand' && (state.status === 'loaded' || state.status === 'error'))
      return Promise.resolve();
    if (reason === 'load-more' && state.hasMore !== true && !retry) return Promise.resolve();
    const intent: UiTreeLoadIntent =
      retry && state.failedRequest
        ? { ...state.failedRequest, node }
        : { node, reason, ...(reason === 'load-more' ? { cursor: state.cursor } : {}) };
    if (options.controlled()) {
      if (state.status !== 'loading') options.emit(intent);
      return Promise.resolve();
    }
    const loader = options.loader();
    if (!loader) return Promise.resolve();
    const current = pending.get(key);
    if (current && (intent.reason !== 'refresh' || current.reason === 'refresh')) return current.promise;
    abort(key);
    const controller = new AbortController();
    const loadRequest = { ...intent, requestId: `tree-load-${++sequence}`, signal: controller.signal };
    setState(key, { ...state, status: 'loading', error: undefined });
    const promise = Promise.resolve().then(async () => {
      const started = Date.now();
      try {
        if (controller.signal.aborted) return;
        const result = await loader(node, loadRequest);
        if (controller.signal.aborted) return;
        if (!result || typeof result.hasMore !== 'boolean' || !Array.isArray(result.nodes))
          throw new Error('树加载结果不完整');
        if (result.hasMore && !result.nextCursor?.trim()) throw new Error('树分页缺少下一页游标');
        if (result.hasMore && intent.cursor === result.nextCursor) throw new Error('树分页游标未前进');
        if (result.mode !== (intent.reason === 'load-more' ? 'append' : 'replace'))
          throw new Error('树加载合并模式与请求不一致');
        await waitMinimum(started, controller.signal);
        if (controller.signal.aborted || !indexTree(nodes.value).has(key)) return;
        const children =
          result.mode === 'append' ? mergeTreePage(node.children ?? [], result.nodes) : result.nodes;
        indexTree(children);
        const replaced = new Set<string>();
        if (result.mode === 'replace') {
          const currentEntries = indexTree(nodes.value);
          currentEntries.forEach((entry, id) => {
            if (entry.parent === key || (entry.parent && replaced.has(entry.parent))) replaced.add(id);
          });
        }
        const next = new Map([...cache.value].filter(([id]) => !replaced.has(id))).set(key, children);
        const visited = new Set<string>();
        function validate(items: UiTreeNode[]): UiTreeNode[] {
          return items.map((item) => {
            if (visited.has(item.key)) throw new Error(`树节点身份重复或成环：${item.key}`);
            visited.add(item.key);
            const nested = next.get(item.key) ?? item.children;
            return nested ? { ...item, children: validate(nested) } : item;
          });
        }
        indexTree(validate(options.nodes()));
        const nextStates = { ...localStates.value };
        replaced.forEach((id) => {
          abort(id);
          delete nextStates[id];
        });
        localStates.value = nextStates;
        cache.value = next;
        setState(key, {
          status: 'loaded',
          hasMore: result.hasMore,
          cursor: result.hasMore ? result.nextCursor : undefined,
        });
      } catch (error) {
        await waitMinimum(started, controller.signal);
        if (!controller.signal.aborted)
          setState(key, {
            ...state,
            status: 'error',
            error: error instanceof Error ? error.message : String(error),
            failedRequest: intent,
          });
      } finally {
        if (pending.get(key)?.controller === controller) pending.delete(key);
      }
    });
    pending.set(key, { controller, reason: intent.reason, promise });
    return promise;
  }
  return {
    nodes,
    states,
    stateOf,
    request,
    release,
    reset,
    retry: (key: string) => request(key, 'refresh', true),
  };
}
