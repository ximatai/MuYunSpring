import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { ModuleContextProvider } from '@muyun/web-core';
import { confirmAction, showInfoMessage } from '@muyun/vue-ui-antdv';
import WorkflowConfigurationView from '@/views/WorkflowConfigurationView.vue';
import { createWorkflowDesign, insertWorkflowBranch } from '@/platform-components/workflowDesignEditing';
import { copyWorkflowFragment } from '@/platform-components/workflowDesignClipboard';
vi.mock('@muyun/vue-ui-antdv', async (original) => ({
  ...(await original<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(),
  showInfoMessage: vi.fn(),
}));
async function fixture() {
  vi.mocked(showInfoMessage).mockClear();
  const source = insertWorkflowBranch(createWorkflowDesign(false), 'route_1', 'branch');
  const context = {
    moduleAlias: 'platform.workflow.definition',
    http: {
      async request(options: { path: string }) {
        if (options.path.endsWith('/view/a')) return { id: 'a', title: 'review', approvalEnabled: false };
        if (options.path.endsWith('/versions/query'))
          return { records: [{ id: 'v', version: 1, versionNo: 1, publishStatus: 'draft' }] };
        if (options.path.endsWith('/design')) return source;
        if (options.path.endsWith('/configuration-catalog'))
          return { tasks: [], queries: [], generations: [], associations: [] };
        if (options.path.endsWith('/context') || options.path.endsWith('/view-context'))
          return { moduleAlias: 'demo.purchase', capabilities: [], actions: [] };
        return {};
      },
    },
  };
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(ModuleContextProvider, { context: context as never }, () =>
          h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase', definitionId: 'a' }),
        ),
    }),
    {
      global: {
        stubs: {
          WorkflowDiagram: true,
          WorkflowParticipantEditor: true,
          WorkflowBusinessTaskEditor: true,
          AdaptiveHeaderActionBar: true,
          RecordAdaptiveDetailPanel: {
            name: 'RecordAdaptiveDetailPanel',
            template: '<div><slot/><slot name="operation"/></div>',
          },
          RecordDetailDrawer: true,
        },
      },
    },
  );
  await flushPromises();
  return { wrapper, graph: wrapper.findComponent({ name: 'WorkflowDiagram' }), source };
}
it('clears removed route selection after undo and does not reopen stale selections on redo', async () => {
  const { wrapper, graph, source } = await fixture();
  try {
    graph.vm.$emit('select', 'approval');
    await flushPromises();
    const text = copyWorkflowFragment(source, ['approval']);
    graph.vm.$emit('paste', { clipboardData: { getData: () => text }, preventDefault() {} });
    await flushPromises();
    const createdRoute = graph.props('routes').at(-1).routeKey;
    graph.vm.$emit('select-route', createdRoute);
    await flushPromises();
    graph.vm.$emit('undo');
    await flushPromises();
    expect(graph.props('routes').some((route: { routeKey: string }) => route.routeKey === createdRoute)).toBe(
      false,
    );
    expect(graph.props('selectedRouteKey')).toBe('');
    expect(wrapper.findComponent({ name: 'RecordAdaptiveDetailPanel' }).attributes('open')).toBe('false');
    graph.vm.$emit('redo');
    await flushPromises();
    expect(graph.props('routes').some((route: { routeKey: string }) => route.routeKey === createdRoute)).toBe(
      true,
    );
    expect(graph.props('selectedRouteKey')).toBe('');
    graph.vm.$emit('undo');
    await flushPromises();
    graph.vm.$emit('select-route', createdRoute);
    await flushPromises();
    expect(graph.props('selectedRouteKey')).toBe('');
    graph.vm.$emit('select', 'missing');
    await flushPromises();
    expect(graph.props('selectedNodeKey')).toBe('');
  } finally {
    wrapper.unmount();
  }
});
it('keeps a completed clipboard edit and cancels the stale branch deletion after confirmation', async () => {
  const { wrapper, graph, source } = await fixture();
  const text = copyWorkflowFragment(source, ['approval']);
  let finishClipboard!: (text: string) => void;
  let finishConfirm!: (allowed: boolean) => void;
  vi.stubGlobal('navigator', {
    clipboard: { readText: () => new Promise<string>((resolve) => (finishClipboard = resolve)) },
  });
  vi.mocked(confirmAction).mockImplementation(
    () => new Promise<boolean>((resolve) => (finishConfirm = resolve)),
  );
  try {
    graph.vm.$emit('select', 'approval');
    await flushPromises();
    graph.vm.$emit('paste-shortcut');
    await flushPromises();
    graph.vm.$emit('select', 'branch');
    await flushPromises();
    graph.vm.$emit('delete-selection');
    await flushPromises();
    expect(finishConfirm).toBeTypeOf('function');
    finishClipboard(text);
    await flushPromises();
    expect(
      graph.props('nodes').filter((node: { nodeKey: string }) => node.nodeKey.startsWith('copy_')),
    ).toHaveLength(1);
    finishConfirm(true);
    await flushPromises();
    expect(
      graph.props('nodes').filter((node: { nodeKey: string }) => node.nodeKey.startsWith('copy_')),
    ).toHaveLength(1);
    expect(graph.props('nodes').some((node: { nodeKey: string }) => node.nodeKey === 'branch')).toBe(true);
    expect(showInfoMessage).toHaveBeenCalledOnce();
    graph.vm.$emit('undo');
    await flushPromises();
    expect(graph.props('nodes')).toEqual(source.nodes);
  } finally {
    wrapper.unmount();
    vi.unstubAllGlobals();
  }
});

it('keeps the branch deletion and rejects a late clipboard write against the previous revision', async () => {
  const { wrapper, graph, source } = await fixture();
  let finishClipboard!: (text: string) => void;
  let finishConfirm!: (allowed: boolean) => void;
  vi.stubGlobal('navigator', {
    clipboard: { readText: () => new Promise<string>((resolve) => (finishClipboard = resolve)) },
  });
  vi.mocked(confirmAction).mockImplementation(
    () => new Promise<boolean>((resolve) => (finishConfirm = resolve)),
  );
  try {
    graph.vm.$emit('select', 'approval');
    await flushPromises();
    graph.vm.$emit('paste-shortcut');
    await flushPromises();
    graph.vm.$emit('select', 'branch');
    await flushPromises();
    graph.vm.$emit('delete-selection');
    await flushPromises();
    finishConfirm(true);
    await flushPromises();
    expect(graph.props('nodes').some((node: { nodeKey: string }) => node.nodeKey === 'branch')).toBe(false);
    finishClipboard(copyWorkflowFragment(source, ['approval']));
    await flushPromises();
    expect(graph.props('nodes').some((node: { nodeKey: string }) => node.nodeKey.startsWith('copy_'))).toBe(
      false,
    );
    expect(showInfoMessage).toHaveBeenCalledOnce();
    graph.vm.$emit('undo');
    await flushPromises();
    expect(graph.props('nodes')).toEqual(source.nodes);
  } finally {
    wrapper.unmount();
    vi.unstubAllGlobals();
  }
});

it('allows only the first clipboard completion against a revision and ignores completions after unmount', async () => {
  const { wrapper, graph, source } = await fixture();
  const completions: Array<(text: string) => void> = [];
  vi.stubGlobal('navigator', {
    clipboard: { readText: () => new Promise<string>((resolve) => completions.push(resolve)) },
  });
  try {
    graph.vm.$emit('select', 'approval');
    await flushPromises();
    graph.vm.$emit('paste-shortcut');
    graph.vm.$emit('paste-shortcut');
    await flushPromises();
    const text = copyWorkflowFragment(source, ['approval']);
    completions[1]!(text);
    await flushPromises();
    completions[0]!(text);
    await flushPromises();
    expect(
      graph.props('nodes').filter((node: { nodeKey: string }) => node.nodeKey.startsWith('copy_')),
    ).toHaveLength(1);
    expect(showInfoMessage).toHaveBeenCalledOnce();
    graph.vm.$emit('paste-shortcut');
    await flushPromises();
    wrapper.unmount();
    vi.mocked(showInfoMessage).mockClear();
    completions[2]!(text);
    await flushPromises();
    expect(showInfoMessage).not.toHaveBeenCalled();
  } finally {
    wrapper.unmount();
    vi.unstubAllGlobals();
  }
});

it('retains existing path selection during unrelated history changes and filters invalid multi-selections', async () => {
  const { wrapper, graph } = await fixture();
  try {
    graph.vm.$emit('select-route', 'route_1');
    await flushPromises();
    graph.vm.$emit('layout-change', 'changed-layout');
    await flushPromises();
    graph.vm.$emit('undo');
    await flushPromises();
    expect(graph.props('selectedRouteKey')).toBe('route_1');
    expect(wrapper.findComponent({ name: 'RecordAdaptiveDetailPanel' }).attributes('open')).toBe('true');
    graph.vm.$emit('selection-change', ['approval', 'missing', 'approval']);
    await flushPromises();
    expect(graph.props('selectedNodeKeys')).toEqual(['approval']);
    expect(graph.props('selectedNodeKey')).toBe('approval');
    expect(graph.props('selectedRouteKey')).toBe('');
  } finally {
    wrapper.unmount();
  }
});

it('explains manual selector reconfiguration and opens the pasted branch properties', async () => {
  const { wrapper, graph } = await fixture();
  try {
    const source = insertWorkflowBranch(createWorkflowDesign(false), 'route_2', 'manual');
    Object.assign(source.nodes.find((node) => node.nodeKey === 'manual')!, {
      routeMode: 'manual',
      selectorNodeKey: 'approval',
    });
    graph.vm.$emit('select', 'approval');
    await flushPromises();
    graph.vm.$emit('paste', {
      clipboardData: { getData: () => copyWorkflowFragment(source, ['manual']) },
      preventDefault() {},
    });
    await flushPromises();
    const branch = graph
      .props('nodes')
      .find(
        (node: { nodeKey: string; nodeType: string }) =>
          node.nodeKey.startsWith('copy_') && node.nodeType === 'branch',
      );
    expect(showInfoMessage).toHaveBeenCalledWith(expect.stringContaining('需要重新配置选择来源'));
    expect(graph.props('selectedNodeKey')).toBe(branch.nodeKey);
    expect(wrapper.findComponent({ name: 'RecordAdaptiveDetailPanel' }).attributes('open')).toBe('true');
  } finally {
    wrapper.unmount();
  }
});
