import { flushPromises, mount } from '@vue/test-utils';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ModulePageCardAssistantContext, ModulePageWorkspaceDraft } from '@muyun/dynamic-page-runtime';
import { AppError } from '@muyun/web-core';

const mocked = vi.hoisted(() => ({
  query: vi.fn(),
  view: vi.fn(),
  can: vi.fn(() => true),
  recordActions: vi.fn(async () => ({})),
}));
vi.mock('@muyun/web-core', async (original) => {
  const real = await original<typeof import('@muyun/web-core')>();
  return {
    ...real,
    withHttpHeaders: (http: unknown) => http,
    createModuleContext: () => ({
      can: mocked.can,
      recordActions: mocked.recordActions,
      runtime: { ready: Promise.resolve({}) },
      crud: { query: mocked.query, view: mocked.view },
    }),
  };
});
vi.mock('@/views/PasswordSecurityRules.vue', () => ({
  default: { name: 'PasswordSecurityRules', props: ['rows'], template: '<div />' },
}));
import PasswordSecurityCard from '@/views/PasswordSecurityCard.vue';
beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: () => ({
      matches: false,
      addListener() {},
      removeListener() {},
      addEventListener() {},
      removeEventListener() {},
    }),
  });
});
const localRule = {
  id: 'r1',
  version: 3,
  title: '数字',
  pattern: '.*[0-9].*',
  message: '需要数字',
  enabled: true,
  sortOrder: 10,
};
function snapshot(overrides: Record<string, unknown> = {}) {
  return {
    tenantId: 't1',
    policyId: 'p1',
    complexityMode: 'inherit',
    lockoutMode: 'inherit',
    complexitySource: 'PLATFORM',
    complexityRules: [{ pattern: '.{6,}', message: '至少六位' }],
    platformComplexityRules: [{ pattern: '.{6,}', message: '至少六位' }],
    lockoutSource: 'PLATFORM_DB',
    maxFailedAttempts: 5,
    lockDurationSeconds: 900,
    ...overrides,
  };
}
function context(
  request: ReturnType<typeof vi.fn>,
  register: (value: ModulePageWorkspaceDraft | undefined) => void,
  mode: 'view' | 'edit' | 'create',
  record?: Record<string, unknown>,
): ModulePageCardAssistantContext {
  return {
    module: {
      moduleAlias: 'iam.password_security_policy',
      http: { request },
    } as unknown as ModulePageCardAssistantContext['module'],
    mode,
    record,
    navigatorRecords: { tenant: { id: 't1' } },
    loadedRecords: [],
    formSessionKey: 1,
    saving: false,
    loading: false,
    loadFailed: false,
    registerWorkspaceDraft: register,
  };
}
describe('password security workspace draft', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.query.mockResolvedValue({ records: [localRule] });
    mocked.view.mockResolvedValue(localRule);
  });
  it('uses local draft for custom trial and submits one unified request', async () => {
    mocked.query.mockResolvedValue({
      records: [{ id: localRule.id, version: localRule.version, title: localRule.title }],
    });
    const request = vi
      .fn()
      .mockImplementation(({ path }) =>
        Promise.resolve(
          path.endsWith('/effective')
            ? snapshot()
            : { data: { record: { id: 'p1' } }, changeSetId: 'workspace-change', changes: [] },
        ),
      );
    let handler: ModulePageWorkspaceDraft | undefined;
    const register = (value: ModulePageWorkspaceDraft | undefined) => {
      handler = value;
    };
    const wrapper = mount(PasswordSecurityCard, {
      props: {
        context: context(request, register, 'edit', { id: 'p1', tenantId: 't1', complexityMode: 'custom' }),
      },
    });
    await flushPromises();
    expect(handler?.ready()).toBe(true);
    expect(mocked.view).toHaveBeenCalledWith('r1');
    expect(wrapper.findComponent({ name: 'PasswordSecurityRules' }).props('rows')).toEqual([localRule]);
    wrapper.findComponent({ name: 'PasswordSecurityRules' }).vm.$emit('update:editing', true);
    expect(handler?.ready()).toBe(false);
    wrapper.findComponent({ name: 'PasswordSecurityRules' }).vm.$emit('update:editing', false);
    expect(handler?.ready()).toBe(true);
    expect(mocked.query).toHaveBeenCalledWith(
      expect.objectContaining({
        unpaged: true,
        externalQueryValues: { tenantId: 't1' },
        conditions: [{ fieldName: 'scopeType', operator: 'EQ', values: ['tenant'] }],
      }),
    );
    const next = [{ ...localRule, pattern: '.*[A-Z].*', message: '需要大写' }];
    wrapper.findComponent({ name: 'PasswordSecurityRules' }).vm.$emit('update:rows', next);
    await wrapper.findComponent({ name: 'UiInput' }).vm.$emit('update:value', 'abc');
    expect(wrapper.text()).toContain('未保存的配置');
    expect(wrapper.text()).toContain('需要大写');
    expect(handler?.dirty()).toBe(true);
    const saved = await handler!.save({
      record: { id: 'p1', version: 1, complexityMode: 'custom' },
      mode: 'edit',
      recordDirty: true,
      requestId: 'workspace-request',
    });
    expect(saved.record.id).toBe('p1');
    expect(saved).toMatchObject({ changeSetId: 'workspace-change', changes: [] });
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({
        path: '/iam.password_security_policy/workspace-save',
        method: 'POST',
        headers: { 'X-Muyun-Save-Request': 'workspace-request' },
        body: expect.objectContaining({
          tenantId: 't1',
          policy: expect.objectContaining({ complexityMode: 'custom' }),
          originalRules: [{ id: 'r1', version: 3 }],
          rules: [expect.objectContaining({ pattern: '.*[A-Z].*' })],
        }),
      }),
    );
    wrapper.unmount();
    expect(handler).toBeUndefined();
  });
  it('uses platform rules while the draft inherits and resets changes on cancel', async () => {
    const request = vi.fn().mockResolvedValue(snapshot());
    let handler: ModulePageWorkspaceDraft | undefined;
    const register = (value: ModulePageWorkspaceDraft | undefined) => {
      handler = value;
    };
    const wrapper = mount(PasswordSecurityCard, {
      props: {
        context: context(request, register, 'edit', { id: 'p1', tenantId: 't1', complexityMode: 'custom' }),
      },
    });
    await flushPromises();
    wrapper.findComponent({ name: 'PasswordSecurityRules' }).vm.$emit('update:rows', []);
    expect(handler?.dirty()).toBe(true);
    await wrapper.setProps({
      context: context(request, register, 'view', { id: 'p1', tenantId: 't1', complexityMode: 'inherit' }),
    });
    await flushPromises();
    expect(handler?.dirty()).toBe(false);
    await wrapper.findComponent({ name: 'UiInput' }).vm.$emit('update:value', 'abc');
    expect(wrapper.text()).toContain('至少六位');
    wrapper.unmount();
  });
  it('rejects first activation without an enabled draft rule', async () => {
    const request = vi.fn().mockResolvedValue(snapshot());
    let handler: ModulePageWorkspaceDraft | undefined;
    const register = (value: ModulePageWorkspaceDraft | undefined) => {
      handler = value;
    };
    const wrapper = mount(PasswordSecurityCard, {
      props: { context: context(request, register, 'edit', { tenantId: 't1', complexityMode: 'custom' }) },
    });
    await flushPromises();
    wrapper.findComponent({ name: 'PasswordSecurityRules' }).vm.$emit('update:rows', []);
    await expect(
      handler!.save({ record: { complexityMode: 'custom' }, mode: 'create', recordDirty: true }),
    ).rejects.toThrow('至少添加一条启用');
    wrapper.unmount();
  });
  it.each(['query', 'view'] as const)(
    'saves only the policy if rule %s access is denied',
    async (operation) => {
      mocked[operation].mockRejectedValue(new AppError('forbidden', { status: 403 }));
      const request = vi
        .fn()
        .mockImplementation(({ path }) =>
          Promise.resolve(
            path.endsWith('/effective')
              ? snapshot()
              : { data: { record: { id: 'p1' } }, changeSetId: 'workspace-change', changes: [] },
          ),
        );
      let handler: ModulePageWorkspaceDraft | undefined;
      const register = (value: ModulePageWorkspaceDraft | undefined) => {
        handler = value;
      };
      const wrapper = mount(PasswordSecurityCard, {
        props: {
          context: context(request, register, 'edit', {
            id: 'p1',
            tenantId: 't1',
            complexityMode: 'inherit',
          }),
        },
      });
      await flushPromises();
      expect(handler?.ready()).toBe(true);
      expect(wrapper.findComponent({ name: 'PasswordSecurityRules' }).exists()).toBe(false);
      await handler!.save({
        record: { id: 'p1', version: 1, lockoutMode: 'custom' },
        mode: 'edit',
        recordDirty: true,
      });
      expect(request).toHaveBeenLastCalledWith(
        expect.objectContaining({ body: expect.objectContaining({ originalRules: null, rules: null }) }),
      );
      await wrapper.setProps({
        context: context(request, register, 'edit', { id: 'p1', tenantId: 't1', complexityMode: 'custom' }),
      });
      expect(wrapper.findComponent({ name: 'UiInput' }).props('disabled')).toBe(true);
      expect(wrapper.text()).toContain('无法读取租户规则，当前草稿无法试算');
      wrapper.unmount();
    },
  );
});
