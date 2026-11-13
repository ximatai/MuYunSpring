import { shallowMount, flushPromises } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';
import DirectoryPolicyDrawer from '@/platform-admin-runtime/federation/DirectoryPolicyDrawer.vue';
import JoinApplicationReviewDrawer from '@/platform-admin-runtime/federation/JoinApplicationReviewDrawer.vue';
import DirectorySyncSummary from '@/platform-admin-runtime/federation/DirectorySyncSummary.vue';
import { joinApplicationPageEnhancement } from '@/platform-admin-runtime/federationPageEnhancements';
import { presentPlatformError } from '@muyun/platform-components';

vi.mock('@muyun/platform-components', async (original) => ({
  ...(await original<typeof import('@muyun/platform-components')>()),
  presentPlatformError: vi.fn(),
}));

type ModulePageDrawerOperation = NonNullable<Parameters<ModulePageDrawerContext['setOperation']>[0]>;
function browserStub(openRecord = vi.fn()) {
  return defineComponent({
    setup(_, { expose }) {
      expose({ openRecord });
      return () => h('div');
    },
  });
}
function context(request = vi.fn(), allowed = true) {
  let operation: ModulePageDrawerOperation | undefined;
  let closeBlocked = false;
  const value = {
    module: {
      http: { request },
      recordActions: vi.fn().mockResolvedValue({ actions: [] }),
      can: () => allowed,
    },
    record: { id: 'policy/1', displayName: 'Pilot applicant' },
    refreshList: vi.fn(),
    refreshDetailExtensions: vi.fn(),
    reload: vi.fn(),
    close: vi.fn(() => {
      expect(closeBlocked).toBe(false);
    }),
    setBeforeClose: vi.fn(),
    setCloseBlocked: vi.fn((blocked: boolean) => {
      closeBlocked = blocked;
    }),
    setOperation: (next: ModulePageDrawerOperation | undefined) => {
      operation = next;
    },
  } as unknown as ModulePageDrawerContext;
  return { value, operation: () => operation };
}
const report = {
  scopeId: 'candidate',
  baseScopeId: 'active',
  runId: 'topic-run',
  counts: { departments: 2, people: 3, positions: 1, grants: 1 },
};
function policy(
  action: 'scopeImpact' | 'preview' | 'synchronize',
  allowed = true,
  response: object = report,
) {
  const request = vi.fn().mockResolvedValue({ data: response });
  const host = context(request, allowed);
  const openRecord = vi.fn();
  const wrapper = shallowMount(DirectoryPolicyDrawer, {
    props: { context: host.value, action },
    global: {
      renderStubDefaultSlot: true,
      stubs: { ModuleReferenceRecordDetailBrowser: browserStub(openRecord) },
    },
  });
  return { ...host, wrapper, request, openRecord };
}

describe('directory scope confirmation', () => {
  it('confirms the reviewed topic run and retains the job receipt without repeating the write', async () => {
    const host = policy('scopeImpact');
    await flushPromises();
    expect(host.request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/iam.tenant_identity_policy/policy%2F1/scopeImpact',
    });
    await host.wrapper.findComponent({ name: 'UiButton' }).vm.$emit('click');
    expect(host.openRecord).toHaveBeenCalledWith('iam.directory_sync_run', 'topic-run');
    host.request.mockResolvedValueOnce({ data: 'queued-job' });
    await host.operation()!.actions[0]!.run();
    await flushPromises();
    expect(host.request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/iam.tenant_identity_policy/policy%2F1/confirmScope',
      body: { scopeId: 'candidate', runId: 'topic-run' },
    });
    expect(host.operation()).toBeUndefined();
    const button = host.wrapper.findComponent(DirectoryPolicyDrawer).findComponent({ name: 'UiButton' });
    await button.vm.$emit('click');
    expect(host.openRecord).toHaveBeenCalledWith('platform.job_run', 'queued-job');
    expect(host.value.refreshList).toHaveBeenCalledOnce();
  });

  it('does not expose confirmation for an absent report or a read-only reviewer', async () => {
    const empty = policy('scopeImpact', true, {});
    const incomplete = policy('scopeImpact', true, { ...report, counts: { departments: 2 } });
    const readOnly = policy('scopeImpact', false);
    await flushPromises();
    expect(empty.operation()).toBeUndefined();
    expect(incomplete.operation()).toBeUndefined();
    expect(readOnly.operation()).toBeUndefined();
    expect(readOnly.request).toHaveBeenCalledOnce();
  });

  it('invalidates a rejected confirmation and requires a fresh report', async () => {
    const host = policy('scopeImpact');
    await flushPromises();
    host.request.mockRejectedValueOnce(new Error('stale report'));
    await host.operation()!.actions[0]!.run();
    await flushPromises();
    expect(host.operation()).toBeUndefined();
    expect(host.value.refreshList).not.toHaveBeenCalled();
    expect(presentPlatformError).toHaveBeenCalledWith(
      expect.any(Error),
      expect.objectContaining({ phase: 'action' }),
    );
  });

  it('keeps preview and formal synchronization explicit and blocks unauthorized writes', async () => {
    const host = policy('synchronize', false);
    await flushPromises();
    expect(host.operation()!.actions[0]!.disabled).toBe(true);
    await host.operation()!.actions[0]!.run();
    expect(host.request).not.toHaveBeenCalled();
    const preview = policy('preview');
    await flushPromises();
    preview.request.mockResolvedValueOnce('preview-job');
    await preview.operation()!.actions[0]!.run();
    expect(preview.request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/iam.tenant_identity_policy/policy%2F1/preview',
    });
  });
});

it('submits an optional review note, refreshes records and closes only after success', async () => {
  const request = vi.fn().mockResolvedValue(undefined);
  const host = context(request);
  const wrapper = shallowMount(JoinApplicationReviewDrawer, {
    props: { context: host.value, action: 'reject' },
  });
  await flushPromises();
  await wrapper.findComponent({ name: 'UiTextArea' }).vm.$emit('update:value', '  Outside pilot scope  ');
  await host.operation()!.actions[0]!.run();
  expect(request).toHaveBeenCalledWith({
    method: 'POST',
    path: '/iam.join_application/policy%2F1/reject',
    body: { note: 'Outside pilot scope' },
  });
  expect(host.value.reload).toHaveBeenCalledOnce();
  expect(host.value.close).toHaveBeenCalledOnce();
  expect(
    joinApplicationPageEnhancement.detail!.actions!.every(
      (action) => action.state!({ status: 'approved' })?.visible === false,
    ),
  ).toBe(true);
});

it('renders safe statistics and keeps job IDs separate from directory run IDs', async () => {
  const host = context();
  const openRecord = vi.fn();
  const wrapper = shallowMount(DirectorySyncSummary, {
    global: { stubs: { ModuleReferenceRecordDetailBrowser: browserStub(openRecord) } },
    props: {
      context: {
        ...host.value,
        refreshKey: 0,
        record: {
          id: 'topic-run',
          jobRunId: 'job-run',
          statistics: '{"people":0,"conflicts":-1}',
          failedPages: '["people:dept:0"]',
        },
      } as never,
    },
  });
  const rows = wrapper.findComponent({ name: 'UiDataTable' }).props('rows');
  expect(rows.find((row: { key: string }) => row.key === 'people').count).toBe(0);
  expect(rows.find((row: { key: string }) => row.key === 'conflicts').count).toBe('—');
  expect(wrapper.text()).toContain('people:dept:0');
  await wrapper.findComponent({ name: 'UiButton' }).vm.$emit('click');
  expect(openRecord).toHaveBeenCalledWith('platform.job_run', 'job-run');
  await wrapper.setProps({
    context: { ...host.value, refreshKey: 1, record: { statistics: 'invalid' } } as never,
  });
  expect(wrapper.text()).toContain('统计暂不可用');
});
