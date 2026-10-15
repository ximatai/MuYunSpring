import { flushPromises, shallowMount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { createLanguageContext, provideLanguageContext, createCodeTextPresentation } from '@muyun/web-core';
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

it('projects password trial feedback without re-evaluating rules or reloading on language changes', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const request = vi.fn().mockResolvedValue([
    { id: 'rule-a', title: '未命名规则', message: '未配置失败提示', pattern: 'A', scopeType: 'global' },
    { id: 'rule-b', pattern: '', scopeType: 'global' },
  ]);
  const context = { ...cardContext(request, {}), mode: 'view' as const };
  const wrapper = shallowMount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(PasswordPolicyPreview, { context });
      },
    }),
    { global: { stubs: { PasswordPolicyPreview: false } } },
  );
  await flushPromises();
  const preview = wrapper.findComponent(PasswordPolicyPreview);
  const test = vi.spyOn(RegExp.prototype, 'test');
  await preview.findComponent({ name: 'UiInput' }).vm.$emit('update:value', 'secret');
  const trialCount = () =>
    test.mock.instances.filter((regex) => regex instanceof RegExp && regex.source === '^(?:A)$').length;
  expect(trialCount()).toBe(1);
  expect(preview.text()).toContain('Unnamed rule');
  expect(preview.text()).toContain('The regular expression must not be empty');
  // A business value equal to a platform fallback remains original.
  expect(preview.text()).toContain('未配置失败提示');
  expect(preview.text()).toContain('Rules failed: 2');
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(preview.text()).toContain('正则表达式不能为空');
  language.setLanguage('en');
  await flushPromises();
  expect(preview.text()).toContain('The regular expression must not be empty');
  expect(preview.findComponent({ name: 'UiInput' }).props('value')).toBe('secret');
  expect(preview.findComponent({ name: 'UiRadioGroup' }).props('value')).toBe('ALL');
  expect(trialCount()).toBe(1);
  expect(request).toHaveBeenCalledTimes(1);
  test.mockRestore();
  wrapper.unmount();
});

it('projects version-matched policy names and messages while preserving the browser-only validation result', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  language.registerResources('en', {
    'test.policy.title': 'Length rule',
    'test.policy.message': 'At least six characters',
  });
  const request = vi.fn();
  const rule = {
    id: 'rule',
    title: '长度规则',
    message: '至少六位',
    pattern: '.{6,}',
    enabled: true,
    $texts: {
      title: createCodeTextPresentation('test.policy.title', '长度规则', language),
      message: createCodeTextPresentation('test.policy.message', '至少六位', language),
    },
  };
  const context = cardContext(request, rule);
  const wrapper = shallowMount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(PasswordPolicyPreview, { context });
      },
    }),
    { global: { stubs: { PasswordPolicyPreview: false } } },
  );
  try {
    const preview = wrapper.findComponent(PasswordPolicyPreview);
    preview.findComponent({ name: 'UiInput' }).vm.$emit('update:value', 'abc');
    await flushPromises();
    expect(wrapper.text()).toContain('Length rule');
    expect(wrapper.text()).toContain('At least six characters');
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(wrapper.text()).toContain('长度规则');
    expect(wrapper.text()).toContain('至少六位');
    expect(request).not.toHaveBeenCalled();
    expect(rule.title).toBe('长度规则');
    expect(rule.pattern).toBe('.{6,}');
  } finally {
    wrapper.unmount();
  }
});
