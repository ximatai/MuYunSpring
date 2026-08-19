import { flushPromises, shallowMount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import PasswordPolicyPreview from '@/views/PasswordPolicyPreview.vue';
import type { ModulePageCardAssistantContext } from '@/dynamic-page-runtime/modulePageEnhancements.ts';

describe('PasswordPolicyPreview', () => {
  it('evaluates the current draft immediately in the browser without a request', async () => {
    const request = vi.fn();
    const wrapper = shallowMount(PasswordPolicyPreview, {
      props: {
        context: cardContext(request, {
          title: '必须包含大写字母',
          pattern: '.*[A-Z].*',
          message: '缺少大写字母',
        }),
      },
    });

    const input = wrapper.findComponent({ name: 'UiInput' });
    expect(input.props('type')).toBe('text');
    await input.vm.$emit('update:value', 'secret1');

    expect(request).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('必须包含大写字母');
    expect(wrapper.text()).toContain('缺少大写字母');
  });

  it('loads the authoritative all-rules snapshot and overlays the current draft', async () => {
    const request = vi.fn().mockResolvedValue([
      {
        id: 'rule-1',
        title: '旧规则',
        pattern: '.*\\d.*',
        message: '缺少数字',
        enabled: true,
        scopeType: 'global',
        sortOrder: 10,
      },
      {
        id: 'rule-2',
        title: '长度规则',
        pattern: '.{6,}',
        message: '至少六位',
        enabled: true,
        scopeType: 'global',
        sortOrder: 20,
      },
    ]);
    const wrapper = shallowMount(PasswordPolicyPreview, {
      props: {
        context: cardContext(
          request,
          {
            id: 'rule-1',
            title: '必须包含大写字母',
            pattern: '.*[A-Z].*',
            message: '缺少大写字母',
            scopeType: 'global',
          },
          noisyLoadedRecords(),
        ),
      },
    });

    expect(wrapper.findComponent({ name: 'UiRadioGroup' }).exists()).toBe(true);
    await wrapper.findComponent({ name: 'UiRadioGroup' }).vm.$emit('update:value', 'ALL');
    await flushPromises();
    await wrapper.findComponent({ name: 'UiInput' }).vm.$emit('update:value', 'Secret1');

    expect(request).toHaveBeenCalledTimes(1);
    expect(request).toHaveBeenCalledWith({ path: '/iam.password_policy_rule/active-global-rules' });
    expect(wrapper.text()).toContain('必须包含大写字母');
    expect(wrapper.text()).toContain('长度规则');
    expect(wrapper.text()).not.toContain('旧规则');
    expect(wrapper.text()).not.toContain('分页外旧规则');
  });

  it('uses the authoritative server fallback when no current rule is selected', async () => {
    const request = vi.fn().mockResolvedValue([
      {
        title: '密码长度',
        pattern: '^.{6,}$',
        message: '密码长度不能少于 6 位',
        enabled: true,
        scopeType: 'global',
        sortOrder: 10,
      },
    ]);
    const wrapper = shallowMount(PasswordPolicyPreview, {
      props: {
        context: {
          ...cardContext(request, {}, noisyLoadedRecords()),
          mode: 'view',
        },
      },
    });

    await flushPromises();
    const scopeSelector = wrapper.findComponent({ name: 'UiRadioGroup' });
    expect(scopeSelector.props('value')).toBe('ALL');
    expect(scopeSelector.props('options')).toContainEqual({
      value: 'CURRENT',
      label: '本规则',
      disabled: true,
    });
    await wrapper.findComponent({ name: 'UiInput' }).vm.$emit('update:value', '12345');

    expect(request).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain('密码长度不能少于 6 位');
    expect(wrapper.text()).not.toContain('分页外旧规则');
  });

  it('reports an authoritative-snapshot failure instead of falling back to explorer data', async () => {
    const request = vi.fn().mockRejectedValue(new Error('network unavailable'));
    const wrapper = shallowMount(PasswordPolicyPreview, {
      props: {
        context: {
          ...cardContext(request, {}, noisyLoadedRecords()),
          mode: 'view',
        },
      },
    });

    await flushPromises();
    await wrapper.findComponent({ name: 'UiInput' }).vm.$emit('update:value', '12345');

    expect(wrapper.text()).toContain('无法加载权威规则集');
    expect(wrapper.text()).not.toContain('密码长度不能少于 6 位');
    expect(wrapper.text()).not.toContain('分页外旧规则');
  });

  it('appends an enabled global create draft to the authoritative snapshot', async () => {
    const request = vi.fn().mockResolvedValue([
      {
        id: 'rule-1',
        title: '长度规则',
        pattern: '.{6,}',
        message: '至少六位',
        enabled: true,
        scopeType: 'global',
        sortOrder: 10,
      },
    ]);
    const wrapper = shallowMount(PasswordPolicyPreview, {
      props: {
        context: cardContext(request, {
          title: '必须包含特殊字符',
          pattern: '.*[^A-Za-z0-9].*',
          message: '缺少特殊字符',
          enabled: true,
          scopeType: 'global',
          sortOrder: 20,
        }),
      },
    });

    await wrapper.findComponent({ name: 'UiRadioGroup' }).vm.$emit('update:value', 'ALL');
    await flushPromises();
    await wrapper.findComponent({ name: 'UiInput' }).vm.$emit('update:value', 'Secret1');

    expect(wrapper.text()).toContain('长度规则');
    expect(wrapper.text()).toContain('必须包含特殊字符');
  });
});

function noisyLoadedRecords(): readonly Readonly<Record<string, unknown>>[] {
  return Array.from({ length: 201 }, (_, index) =>
    Object.freeze({
      id: `loaded-${index}`,
      title: '分页外旧规则',
      pattern: '.*[Z].*',
      message: '不应参与试算',
      enabled: true,
      scopeType: 'global',
    }),
  );
}

function cardContext(
  request: ReturnType<typeof vi.fn>,
  record: Readonly<Record<string, unknown>>,
  loadedRecords: readonly Readonly<Record<string, unknown>>[] = [],
): ModulePageCardAssistantContext {
  return {
    module: {
      moduleAlias: 'iam.password_policy_rule',
      http: { request },
    } as unknown as ModulePageCardAssistantContext['module'],
    mode: record.id ? 'edit' : 'create',
    record: Object.freeze({ ...record }),
    loadedRecords: Object.freeze(loadedRecords.map((item) => Object.freeze({ ...item }))),
    formSessionKey: record.id ? 2 : 1,
    saving: false,
    loading: false,
    loadFailed: false,
  };
}
