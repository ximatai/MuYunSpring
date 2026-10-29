import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, KeepAlive, provide, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import {
  createAssistantSurfaceRegistry,
  createLanguageContext,
  provideLanguageContext,
  ModuleContextProvider,
  provideAssistantSurfaceHost,
  type HttpClient,
  type HttpRequestOptions,
  type ModuleContext,
} from '@muyun/web-core';
import type { CurrentUser, WorkflowDesign } from '@muyun/web-contracts';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext';
import { assistantBusinessScopeKey } from '@/dynamic-page-runtime/assistantBusinessScope';
import WorkflowConfigurationView from '@/views/WorkflowConfigurationView.vue';
import { createWorkflowDesign } from '@/platform-components/workflowDesignEditing';
import { copyWorkflowFragment } from '@/platform-components/workflowDesignClipboard';

interface Options {
  languageTag?: string;
  write?: boolean;
  moduleAuthorized?: boolean;
  editCode?: string;
  status?: 'draft' | 'published';
  waitDesign?: Promise<void>;
  waitSave?: Promise<void>;
  waitUpgrade?: Promise<void>;
  publishedReadFails?: boolean;
  waitAccess?: Promise<void>;
  waitFormula?: Promise<void>;
  protectedFields?: boolean;
  saveFails?: boolean;
  publishFails?: boolean;
  olderDraft?: boolean;
}

it('rejects a clipboard completion from a previous activation even after the same draft is reactivated', async () => {
  const f = await fixture();
  let finish!: (value: string) => void;
  vi.stubGlobal('navigator', {
    clipboard: {
      readText: () =>
        new Promise<string>((resolve) => {
          finish = resolve;
        }),
    },
  });
  try {
    f.graph().vm.$emit('select', 'approval');
    await flushPromises();
    f.graph().vm.$emit('paste-shortcut');
    await flushPromises();
    f.visible.value = false;
    await flushPromises();
    f.visible.value = true;
    await flushPromises();
    finish(copyWorkflowFragment(f.stored(), ['approval']));
    await flushPromises();
    expect(f.graph().props('nodes')).toHaveLength(3);
    expect(f.registry.snapshot()?.context.facts.unsaved).toBe(false);
  } finally {
    f.wrapper.unmount();
    vi.unstubAllGlobals();
  }
});
async function fixture(options: Options = {}) {
  const user = ref<CurrentUser>({ userId: 'user-a', tenantId: 'tenant-a', system: false });
  const scope = ref('tenant-a');
  const definitionId = ref('a');
  const visible = ref(true);
  const mounted = ref(true);
  const mountGeneration = ref(0);
  const moduleAlias = ref('demo.purchase');
  const registry = createAssistantSurfaceRegistry(() => JSON.stringify([user.value.userId, scope.value]));
  registry.activate('workflow-page');
  let stored = createWorkflowDesign(false);
  stored.layoutJson = '{"positions":{"approval":{"x":120,"y":200}}}';
  stored.nodes.find((node) => node.nodeKey === 'approval')!.nodeConfigText = '{"unknownSetting":true}';
  if (options.protectedFields) {
    stored.nodes[1]!.participantPolicyText = '{"rules":[{"type":"FIELD","fieldName":"secretOwner"}]}';
    stored.links[0]!.conditionExpression = '{secretOwner} != null';
    stored.nodes[1]!.nodeConfigText = JSON.stringify({
      checks: [{ checkKind: 'FORMULA', expression: '{passwordOwner} != null' }],
    });
  }
  let storedVersion = 4;
  let published = false;
  const request = vi.fn(async (input: HttpRequestOptions) => {
    if (input.path.endsWith('/view-context') || input.path.endsWith('/context')) {
      return {
        moduleAlias: input.path.includes('platform.workflow.definition')
          ? 'platform.workflow.definition'
          : moduleAlias.value,
        capabilities: [],
        uiDescriptor: {
          moduleAlias: moduleAlias.value,
          defaultEditor: {
            viewKind: 'FORM',
            fields: [
              ...(options.protectedFields
                ? [
                    {
                      fieldRef: { fieldName: 'secretOwner' },
                      label: 'Secret owner',
                      valueType: 'REFERENCE',
                      assistantPolicy: 'HIDDEN',
                      reference: { targetModuleAlias: 'iam.user', cardinality: 'ONE' },
                    },
                    {
                      fieldRef: { fieldName: 'passwordOwner' },
                      label: 'Password owner',
                      valueType: 'REFERENCE',
                      fieldControl: { alias: 'password' },
                      reference: { targetModuleAlias: 'iam.user', cardinality: 'ONE' },
                    },
                  ]
                : []),
              {
                fieldRef: { fieldName: user.value.userId === 'user-a' ? 'ownerA' : 'ownerB' },
                label: user.value.userId === 'user-a' ? 'Original owner' : 'Current owner',
                valueType: 'REFERENCE',
                reference: { targetModuleAlias: 'iam.user', cardinality: 'ONE' },
              },
            ],
          },
        },
        actions: [
          { actionCode: 'viewWorkflowDesign', authorized: true, actionLevel: 'RECORD' },
          {
            actionCode: options.editCode ?? 'editWorkflowDesign',
            authorized: options.moduleAuthorized !== false,
            actionLevel: 'RECORD',
          },
        ],
      };
    }
    if (input.path.includes('/actions/')) {
      const recordId = input.path.split('/').at(-1);
      const canWrite = options.write !== false;
      await options.waitAccess;
      return {
        recordId,
        actions: [
          { actionCode: 'viewWorkflowDesign', available: true },
          { actionCode: options.editCode ?? 'editWorkflowDesign', available: canWrite },
        ],
      };
    }
    if (/\/view\/[ab]$/.test(input.path)) {
      if (published && options.publishedReadFails) throw new Error('Definition refresh failed');
      return {
        id: input.path.at(-1),
        version: 2,
        title: `Flow ${input.path.at(-1)}`,
        approvalEnabled: false,
      };
    }
    if (input.path.endsWith('/upgrade')) {
      await options.waitUpgrade;
      return { id: 'upgraded', version: ++storedVersion, versionNo: 3, publishStatus: 'draft' };
    }
    if (input.path.endsWith('/configuration-catalog'))
      return { tasks: [], queries: [], generations: [], associations: [] };
    if (input.path.endsWith('/validate-condition')) {
      await options.waitFormula;
      return { valid: true };
    }
    if (input.path.endsWith('/versions/query'))
      return {
        records: [
          { id: 'draft', version: storedVersion, versionNo: 2, publishStatus: options.status ?? 'draft' },
          { id: 'published', version: 3, versionNo: 1, publishStatus: 'published' },
          ...(options.olderDraft
            ? [{ id: 'older-draft', version: storedVersion, versionNo: 0, publishStatus: 'draft' }]
            : []),
        ],
      };
    if (input.path.endsWith('/design') && input.method === 'POST') {
      const body = input.body as { version: number; design: WorkflowDesign };
      expect(body.version).toBe(storedVersion);
      await options.waitSave;
      if (options.saveFails) throw new Error('Save rejected');
      stored = JSON.parse(JSON.stringify(body.design));
      return {
        id: input.path.includes('/older-draft/') ? 'older-draft' : 'draft',
        version: ++storedVersion,
        versionNo: 2,
        publishStatus: 'draft',
      };
    }
    if (input.path.endsWith('/publish')) {
      if (options.publishFails) throw new Error('Publish rejected');
      published = true;
      return { id: 'draft', version: ++storedVersion, versionNo: 2, publishStatus: 'published' };
    }
    if (input.path.endsWith('/design')) {
      const value = structuredClone(stored);
      await options.waitDesign;
      return value;
    }
    throw new Error(`Unexpected workflow request: ${input.path}`);
  });
  const http = { request } as unknown as HttpClient;
  const context = { moduleAlias: 'platform.workflow.definition', http } as ModuleContext<unknown>;
  const Harness = defineComponent({
    setup() {
      provideLanguageContext(createLanguageContext({ languageTag: options.languageTag ?? 'zh-CN' }));
      provideCurrentUserContext(user);
      provide(
        assistantBusinessScopeKey,
        computed(() => scope.value),
      );
      provideAssistantSurfaceHost({ registry, activePageInstanceKey: () => 'workflow-page' });
      return () =>
        h(ModuleContextProvider, { context }, () =>
          h(KeepAlive, null, () =>
            mounted.value && visible.value
              ? h(WorkflowConfigurationView, {
                  key: `workflow-${mountGeneration.value}`,
                  moduleAlias: moduleAlias.value,
                  definitionId: definitionId.value,
                })
              : h('div'),
          ),
        );
    },
  });
  const wrapper = mount(Harness, {
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
  });
  await flushPromises();
  const graph = () => wrapper.findComponent({ name: 'WorkflowDiagram' });
  const invoke = (code: string, input: unknown = {}) => {
    const snapshot = registry.snapshot();
    expect(snapshot).toBeDefined();
    return registry.invoke({ id: 'call', code, input }, snapshot!.token);
  };
  const codes = () => registry.snapshot()?.capabilities.map((capability) => capability.code) ?? [];
  return {
    wrapper,
    registry,
    graph,
    invoke,
    codes,
    request,
    user,
    scope,
    definitionId,
    visible,
    mounted,
    moduleAlias,
    stored: () => stored,
    reopen: () => {
      mountGeneration.value++;
    },
  };
}

it('shares assistant and human edits, one-step undo/redo and the complete versioned manual save/readback', async () => {
  const f = await fixture();
  try {
    expect(f.codes()).toContain('workflow.revise-draft');
    const result = await f.invoke('workflow.revise-draft', {
      operations: [
        {
          op: 'update-node',
          node: 'approval',
          patch: { title: 'Assistant approval', participants: [{ type: 'INITIATOR_SELF' }] },
        },
        {
          op: 'insert-node',
          as: 'notice',
          route: 'route_2',
          node: { kind: 'notice', title: 'Inform submitter', participants: [{ type: 'INITIATOR_SELF' }] },
        },
      ],
    });
    await flushPromises();
    expect(result.value).toMatchObject({ unsaved: true, saved: false, published: false });
    expect(result.presentation?.lines.join(' ')).toContain('未保存');
    expect(f.graph().props('nodes')).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ title: 'Assistant approval' }),
        expect.objectContaining({ title: 'Inform submitter' }),
      ]),
    );
    expect(
      f.request.mock.calls.filter(([input]) => input.method === 'POST' && input.path.endsWith('/design')),
    ).toHaveLength(0);
    f.graph().vm.$emit('select', 'approval');
    await flushPromises();
    const properties = f.wrapper.findComponent({ name: 'WorkflowDesignProperties' });
    expect(
      properties.props('design').nodes.find((node: { nodeKey: string }) => node.nodeKey === 'approval').title,
    ).toBe('Assistant approval');
    properties.vm.$emit('update-node', { title: 'Human approval' });
    await flushPromises();
    expect(JSON.stringify((await f.invoke('workflow.describe-draft')).value)).toContain('Human approval');
    f.graph().vm.$emit('undo');
    await flushPromises();
    expect(
      f
        .graph()
        .props('nodes')
        .find((node: { nodeKey: string }) => node.nodeKey === 'approval').title,
    ).toBe('Assistant approval');
    f.graph().vm.$emit('undo');
    await flushPromises();
    expect(f.graph().props('nodes')).toHaveLength(3);
    expect(
      f
        .graph()
        .props('nodes')
        .find((node: { nodeKey: string }) => node.nodeKey === 'approval').title,
    ).toBe('审批');
    f.graph().vm.$emit('redo');
    await flushPromises();
    expect(f.graph().props('nodes')).toHaveLength(4);
    f.wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'save' });
    await flushPromises();
    const writes = f.request.mock.calls.filter(
      ([input]) => input.method === 'POST' && input.path.endsWith('/design'),
    );
    expect(writes).toHaveLength(1);
    expect(writes[0]![0]).toMatchObject({
      path: '/platform.module/demo.purchase/workflow-definitions/a/versions/draft/design',
      body: { version: 4, design: { layoutJson: f.stored().layoutJson } },
    });
    expect(f.stored().nodes.find((node) => node.nodeKey === 'approval')!.nodeConfigText).toBe(
      '{"unknownSetting":true}',
    );
    expect(f.stored().nodes).toHaveLength(4);
    expect(f.registry.snapshot()?.context.facts.unsaved).toBe(false);
    f.reopen();
    await flushPromises();
    expect(f.graph().props('nodes')).toEqual(f.stored().nodes);
    const reread = await f.invoke('workflow.describe-draft');
    expect(JSON.stringify(reread.value)).toContain('Assistant approval');
  } finally {
    f.wrapper.unmount();
  }
});

it.each([
  { write: false },
  { editCode: 'updateWorkflowDesign' },
  { moduleAuthorized: false },
  { status: 'published' as const },
])('filters writes using actual action facts and frozen status: %j', async (options) => {
  const f = await fixture(options);
  try {
    expect(f.codes()).toContain('workflow.describe-draft');
    expect(f.codes()).not.toContain('workflow.revise-draft');
    await expect(
      f.invoke('workflow.revise-draft', {
        operations: [{ op: 'update-node', node: 'approval', patch: { title: 'Forbidden' } }],
      }),
    ).rejects.toThrow();
    expect(
      f
        .graph()
        .props('nodes')
        .find((node: { nodeKey: string }) => node.nodeKey === 'approval').title,
    ).toBe('审批');
  } finally {
    f.wrapper.unmount();
  }
});

it('rejects an invalid complete batch and stale calls after human selection or version changes', async () => {
  const f = await fixture();
  try {
    const original = JSON.parse(JSON.stringify(f.graph().props('nodes')));
    await expect(
      f.invoke('workflow.revise-draft', {
        operations: [
          { op: 'update-node', node: 'approval', patch: { title: 'Must not apply' } },
          { op: 'delete-node', node: 'missing' },
        ],
      }),
    ).rejects.toThrow();
    expect(f.graph().props('nodes')).toEqual(original);
    const token = f.registry.snapshot()!.token;
    f.graph().vm.$emit('select', 'approval');
    await flushPromises();
    await expect(
      f.registry.invoke(
        {
          id: 'old',
          code: 'workflow.revise-draft',
          input: { operations: [{ op: 'update-node', node: 'approval', patch: { title: 'Stale' } }] },
        },
        token,
      ),
    ).rejects.toThrow();
    f.wrapper
      .findAllComponents({ name: 'UiSelect' })
      .find((select) => select.props('value') === 'draft')!
      .vm.$emit('update:value', 'published');
    await flushPromises();
    expect(f.codes()).not.toContain('workflow.revise-draft');
    expect(f.codes()).toContain('workflow.describe-draft');
  } finally {
    f.wrapper.unmount();
  }
});

it('removes the surface while inactive, invalidates old tokens on activation and removes it on unmount', async () => {
  const f = await fixture();
  const token = f.registry.snapshot()!.token;
  f.visible.value = false;
  await flushPromises();
  expect(f.registry.snapshot()).toBeUndefined();
  f.visible.value = true;
  await flushPromises();
  expect(f.codes()).toContain('workflow.revise-draft');
  await expect(
    f.registry.invoke({ id: 'old', code: 'workflow.describe-draft', input: {} }, token),
  ).rejects.toThrow();
  f.wrapper.unmount();
  expect(f.registry.snapshot()).toBeUndefined();
});

it('does not expose unreadied or busy drafts and rejects late loads after unmount', async () => {
  let finishLoad!: () => void;
  const loading = new Promise<void>((resolve) => {
    finishLoad = resolve;
  });
  const f = await fixture({ waitDesign: loading });
  expect(f.codes()).not.toContain('workflow.describe-draft');
  f.wrapper.unmount();
  finishLoad();
  await flushPromises();
  expect(f.registry.snapshot()).toBeUndefined();

  let finishSave!: () => void;
  const saving = new Promise<void>((resolve) => {
    finishSave = resolve;
  });
  const g = await fixture({ waitSave: saving });
  try {
    await g.invoke('workflow.revise-draft', {
      operations: [{ op: 'update-node', node: 'approval', patch: { title: 'Pending save' } }],
    });
    g.wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'save' });
    await flushPromises();
    expect(g.codes()).not.toContain('workflow.revise-draft');
    expect(g.codes()).not.toContain('workflow.describe-draft');
    finishSave();
    await flushPromises();
    expect(g.codes()).toContain('workflow.revise-draft');
  } finally {
    g.wrapper.unmount();
  }
});

it('drops old authorization and unsaved facts on identity and execution-scope changes', async () => {
  const f = await fixture();
  try {
    expect(JSON.stringify((await f.invoke('workflow.describe-draft')).value)).toContain('ownerA');
    await f.invoke('workflow.revise-draft', {
      operations: [{ op: 'update-node', node: 'approval', patch: { title: 'Old identity draft' } }],
    });
    const old = f.registry.snapshot()!.token;
    f.user.value = { userId: 'user-b', tenantId: 'tenant-b', system: false };
    f.scope.value = 'tenant-b';
    await flushPromises();
    expect(f.codes()).toContain('workflow.revise-draft');
    expect(JSON.stringify((await f.invoke('workflow.describe-draft')).value)).not.toContain(
      'Old identity draft',
    );
    const currentFacts = JSON.stringify((await f.invoke('workflow.describe-draft')).value);
    expect(currentFacts).toContain('ownerB');
    expect(currentFacts).not.toContain('ownerA');
    await expect(
      f.registry.invoke({ id: 'old', code: 'workflow.describe-draft', input: {} }, old),
    ).rejects.toThrow();
    expect(
      f.request.mock.calls.filter(([input]) => input.path === '/platform.workflow.definition/actions/a')
        .length,
    ).toBeGreaterThan(1);
    f.moduleAlias.value = 'demo.other';
    f.definitionId.value = 'b';
    await flushPromises();
    expect(JSON.stringify((await f.invoke('workflow.describe-draft')).value)).toContain('demo.other');
    expect(
      f.request.mock.calls.some(
        ([input]) => input.path === '/platform.module/demo.other/workflow-definitions/view/b',
      ),
    ).toBe(true);
  } finally {
    f.wrapper.unmount();
  }
});

it('ignores late authorization from the previous identity and does not re-enable writing', async () => {
  let finishAccess!: () => void;
  const options: Options = {
    waitAccess: new Promise<void>((resolve) => {
      finishAccess = resolve;
    }),
  };
  const f = await fixture(options);
  try {
    expect(f.codes()).not.toContain('workflow.revise-draft');
    options.waitAccess = undefined;
    options.write = false;
    f.user.value = { userId: 'new-reader', tenantId: 'tenant-a', system: false };
    await flushPromises();
    expect(f.codes()).toContain('workflow.describe-draft');
    expect(f.codes()).not.toContain('workflow.revise-draft');
    finishAccess();
    await flushPromises();
    expect(f.codes()).toContain('workflow.describe-draft');
    expect(f.codes()).not.toContain('workflow.revise-draft');
  } finally {
    f.wrapper.unmount();
  }
});

it('discards late definition loads after switching the definition', async () => {
  let finishLoad!: () => void;
  const options: Options = {
    waitDesign: new Promise<void>((resolve) => {
      finishLoad = resolve;
    }),
  };
  const f = await fixture(options);
  try {
    expect(f.codes()).not.toContain('workflow.describe-draft');
    options.waitDesign = undefined;
    f.definitionId.value = 'b';
    await flushPromises();
    expect(JSON.stringify((await f.invoke('workflow.describe-draft')).value)).toContain('Flow b');
    finishLoad();
    await flushPromises();
    const output = JSON.stringify((await f.invoke('workflow.describe-draft')).value);
    expect(output).toContain('Flow b');
    expect(output).not.toContain('Flow a');
  } finally {
    f.wrapper.unmount();
  }
});

it('uses read-only formula checking and discards its late result after a human edit', async () => {
  let finishFormula!: () => void;
  const f = await fixture({
    waitFormula: new Promise<void>((resolve) => {
      finishFormula = resolve;
    }),
  });
  try {
    await f.invoke('workflow.revise-draft', {
      operations: [
        { op: 'insert-branch', as: 'branch', route: 'route_1' },
        {
          op: 'update-route',
          route: { created: 'branch', part: 'exit1' },
          patch: { conditionExpression: 'true' },
        },
      ],
    });
    const diagnosis = f.invoke('workflow.diagnose-draft');
    const rejected = expect(diagnosis).rejects.toThrow();
    await flushPromises();
    expect(f.request.mock.calls.some(([input]) => input.path.endsWith('/validate-condition'))).toBe(true);
    f.graph().vm.$emit('select', 'approval');
    await flushPromises();
    f.wrapper
      .findComponent({ name: 'WorkflowDesignProperties' })
      .vm.$emit('update-node', { title: 'Human edit while checking' });
    await flushPromises();
    finishFormula();
    await rejected;
    expect(
      f
        .graph()
        .props('nodes')
        .find((node: { nodeKey: string }) => node.nodeKey === 'approval').title,
    ).toBe('Human edit while checking');
    expect(
      f.request.mock.calls.some(
        ([input]) =>
          input.path.endsWith('/validate') ||
          input.path.endsWith('/publish') ||
          (input.method === 'POST' && input.path.endsWith('/design')),
      ),
    ).toBe(false);
  } finally {
    f.wrapper.unmount();
  }
});

it('keeps the complete manual field directory while excluding protected fields and retained rules from assistant outputs', async () => {
  const f = await fixture({ protectedFields: true });
  try {
    f.graph().vm.$emit('select', 'approval');
    await flushPromises();
    const properties = f.wrapper.findComponent({ name: 'WorkflowDesignProperties' });
    expect(properties.props('fields').map((field: { name: string }) => field.name)).toEqual([
      'secretOwner',
      'passwordOwner',
      'ownerA',
    ]);
    const originalPolicy = f.graph().props('nodes')[1].participantPolicyText;
    for (const code of ['workflow.describe-draft', 'workflow.diagnose-draft']) {
      const result = await f.invoke(code);
      const output = JSON.stringify(result);
      expect(output).not.toContain('secretOwner');
      expect(output).not.toContain('passwordOwner');
      expect(output).not.toContain('Secret owner');
      expect(output).not.toContain('参与人账号字段不可用');
    }
    expect(f.request.mock.calls.some(([input]) => input.path.endsWith('/validate-condition'))).toBe(false);
    for (const fieldName of ['secretOwner', 'passwordOwner']) {
      await expect(
        f.invoke('workflow.revise-draft', {
          operations: [
            { op: 'update-node', node: 'approval', patch: { participants: [{ type: 'FIELD', fieldName }] } },
          ],
        }),
      ).rejects.toThrow();
    }
    const changed = await f.invoke('workflow.revise-draft', {
      operations: [{ op: 'update-node', node: 'approval', patch: { title: 'Visible change' } }],
    });
    expect(JSON.stringify(changed)).not.toMatch(/secretOwner|passwordOwner/);
    expect(f.graph().props('nodes')[1].participantPolicyText).toBe(originalPolicy);
    await f.invoke('workflow.revise-draft', {
      operations: [
        {
          op: 'update-node',
          node: 'approval',
          patch: { participants: [{ type: 'FIELD', fieldName: 'ownerA' }] },
        },
      ],
    });
    expect(f.graph().props('nodes')[1].participantPolicyText).toContain('ownerA');
    // The manual property path can still maintain the protected rule.
    properties.vm.$emit('update-node', { participantPolicyText: originalPolicy });
    await flushPromises();
    expect(f.graph().props('nodes')[1].participantPolicyText).toBe(originalPolicy);
  } finally {
    f.wrapper.unmount();
  }
});

it('preserves the dirty draft and current version after manual save rejection and KeepAlive', async () => {
  const options: Options = { saveFails: true };
  const f = await fixture(options);
  try {
    await f.invoke('workflow.revise-draft', {
      operations: [{ op: 'update-node', node: 'approval', patch: { title: 'Retained draft' } }],
    });
    f.wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'save' });
    await flushPromises();
    f.visible.value = false;
    await flushPromises();
    f.visible.value = true;
    await flushPromises();
    expect((await f.invoke('workflow.describe-draft')).value).toMatchObject({
      unsaved: true,
      version: { version: 4 },
    });
    expect(f.graph().props('nodes')[1].title).toBe('Retained draft');
    options.saveFails = false;
    f.wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'save' });
    await flushPromises();
    expect((await f.invoke('workflow.describe-draft')).value).toMatchObject({
      unsaved: false,
      version: { version: 5 },
    });
  } finally {
    f.wrapper.unmount();
  }
});

it('records an acknowledged manual save across deactivation without losing its version or resaving', async () => {
  let finish!: () => void;
  const f = await fixture({
    waitSave: new Promise<void>((resolve) => {
      finish = resolve;
    }),
  });
  try {
    await f.invoke('workflow.revise-draft', {
      operations: [{ op: 'update-node', node: 'approval', patch: { title: 'Saved while inactive' } }],
    });
    f.wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'save' });
    await flushPromises();
    f.visible.value = false;
    await flushPromises();
    finish();
    await flushPromises();
    f.visible.value = true;
    await flushPromises();
    expect((await f.invoke('workflow.describe-draft')).value).toMatchObject({
      unsaved: false,
      version: { version: 5 },
    });
    expect(f.graph().props('nodes')[1].title).toBe('Saved while inactive');
    expect(
      f.request.mock.calls.filter(([input]) => input.method === 'POST' && input.path.endsWith('/design')),
    ).toHaveLength(1);
  } finally {
    f.wrapper.unmount();
  }
});

it('retains the selected version and saved draft when manual publication fails', async () => {
  const f = await fixture({ publishFails: true, olderDraft: true });
  try {
    f.wrapper
      .findAllComponents({ name: 'UiSelect' })
      .find((select) => select.props('value') === 'draft')!
      .vm.$emit('update:value', 'older-draft');
    await flushPromises();
    await f.invoke('workflow.revise-draft', {
      operations: [
        {
          op: 'update-node',
          node: 'approval',
          patch: { title: 'Draft pending publication', participants: [{ type: 'INITIATOR_SELF' }] },
        },
      ],
    });
    f.wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'publish' });
    await flushPromises();
    const drawer = f.wrapper
      .findAllComponents({ name: 'RecordDetailDrawer' })
      .find((component) => component.props('open'))!;
    expect(drawer.exists()).toBe(true);
    // Invoke the formal drawer footer action, just as the human publish button does.
    const page = f.wrapper.findComponent(WorkflowConfigurationView);
    await (page.vm as unknown as { publish(): Promise<void> }).publish();
    await flushPromises();
    expect(f.request.mock.calls.some(([input]) => input.path.endsWith('/publish'))).toBe(true);
    expect((await f.invoke('workflow.describe-draft')).value).toMatchObject({
      unsaved: false,
      version: { id: 'older-draft', version: 5, publishStatus: 'draft' },
    });
    expect(f.graph().props('nodes')[1].title).toBe('Draft pending publication');
  } finally {
    f.wrapper.unmount();
  }
});

it.each([
  ['zh-CN', '流程草稿已调整（未保存）', '参与人未配置或无效', '参与人字段必须'],
  [
    'en',
    'Workflow draft updated (unsaved)',
    'Participants are missing or invalid',
    'Participant fields must',
  ],
])(
  'uses the formal page LanguageContext for real results and errors in %s',
  async (languageTag, title, diagnostic, error) => {
    const f = await fixture({ languageTag });
    try {
      const result = await f.invoke('workflow.revise-draft', {
        operations: [{ op: 'update-node', node: 'approval', patch: { title: '人工节点原文' } }],
      });
      expect(result.presentation?.title).toBe(title);
      expect(result.presentation?.lines?.join(' ')).toContain('人工节点原文');
      expect(result.presentation?.lines?.join(' ')).toContain(diagnostic);
      await expect(
        f.invoke('workflow.revise-draft', {
          operations: [
            {
              op: 'update-node',
              node: 'approval',
              patch: { participants: [{ type: 'FIELD', fieldName: 'missing' }] },
            },
          ],
        }),
      ).rejects.toThrow(error);
      expect(JSON.stringify((await f.invoke('workflow.describe-draft')).value)).toContain('Original owner');
    } finally {
      f.wrapper.unmount();
    }
  },
);

it('retains an acknowledged new version and its design across tab deactivation', async () => {
  let finish!: () => void;
  const f = await fixture({
    status: 'published',
    waitUpgrade: new Promise<void>((resolve) => {
      finish = resolve;
    }),
  });
  try {
    f.wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'upgrade' });
    await flushPromises();
    f.visible.value = false;
    await flushPromises();
    finish();
    await flushPromises();
    f.visible.value = true;
    await flushPromises();
    expect((await f.invoke('workflow.describe-draft')).value).toMatchObject({
      version: { id: 'upgraded', version: 5, publishStatus: 'draft' },
      editable: true,
      unsaved: false,
    });
    expect(f.request.mock.calls.filter(([input]) => input.path.endsWith('/upgrade'))).toHaveLength(1);
    expect(f.request.mock.calls.some(([input]) => input.path.endsWith('/upgraded/design'))).toBe(true);
  } finally {
    f.wrapper.unmount();
  }
});

it('closes the publication review on acknowledged success even when definition readback fails', async () => {
  const f = await fixture({ publishedReadFails: true });
  try {
    await f.invoke('workflow.revise-draft', {
      operations: [
        { op: 'update-node', node: 'approval', patch: { participants: [{ type: 'INITIATOR_SELF' }] } },
      ],
    });
    f.wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'publish' });
    await flushPromises();
    const page = f.wrapper.findComponent(WorkflowConfigurationView);
    await (page.vm as unknown as { publish(): Promise<void> }).publish();
    await flushPromises();
    expect((await f.invoke('workflow.describe-draft')).value).toMatchObject({
      version: { publishStatus: 'published' },
      editable: false,
      unsaved: false,
    });
    expect(
      f.wrapper.findAllComponents({ name: 'RecordDetailDrawer' }).some((drawer) => drawer.props('open')),
    ).toBe(false);
    expect(f.request.mock.calls.filter(([input]) => input.path.endsWith('/publish'))).toHaveLength(1);
  } finally {
    f.wrapper.unmount();
  }
});
