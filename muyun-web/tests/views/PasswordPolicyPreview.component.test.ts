import { shallowMount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import PasswordPolicyPreview from '@/views/PasswordPolicyPreview.vue';
import type { ModulePageCardAssistantContext } from '@/dynamic-page-runtime/modulePageEnhancements.ts';

describe('PasswordPolicyPreview', () => {
  it('evaluates the current draft immediately in the browser without a request', async () => {
    const request = () => {
      throw new Error('Password preview must not call the backend');
    };
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

    expect(wrapper.text()).toContain('必须包含大写字母');
    expect(wrapper.text()).toContain('缺少大写字母');
  });

  it('uses the radio-button all-rules mode and overlays the current draft over loaded records', async () => {
    const wrapper = shallowMount(PasswordPolicyPreview, {
      props: {
        context: cardContext(
          () => {
            throw new Error('Password preview must not call the backend');
          },
          {
            id: 'rule-1',
            title: '必须包含大写字母',
            pattern: '.*[A-Z].*',
            message: '缺少大写字母',
            scopeType: 'global',
          },
          [
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
          ],
        ),
      },
    });

    expect(wrapper.findComponent({ name: 'UiRadioGroup' }).exists()).toBe(true);
    await wrapper.findComponent({ name: 'UiRadioGroup' }).vm.$emit('update:value', 'ALL');
    await wrapper.findComponent({ name: 'UiInput' }).vm.$emit('update:value', 'Secret1');

    expect(wrapper.text()).toContain('必须包含大写字母');
    expect(wrapper.text()).toContain('长度规则');
    expect(wrapper.text()).not.toContain('旧规则');
  });

  it('disables current-rule mode and falls back to all rules without a selected rule', async () => {
    const wrapper = shallowMount(PasswordPolicyPreview, {
      props: {
        context: {
          ...cardContext(
            () => {
              throw new Error('Password preview must not call the backend');
            },
            {},
            [
              {
                id: 'rule-1',
                title: '长度规则',
                pattern: '.{6,}',
                message: '至少六位',
                enabled: true,
                scopeType: 'global',
              },
            ],
          ),
          mode: 'view',
        },
      },
    });

    const scopeSelector = wrapper.findComponent({ name: 'UiRadioGroup' });
    expect(scopeSelector.props('value')).toBe('ALL');
    expect(scopeSelector.props('options')).toContainEqual({
      value: 'CURRENT',
      label: '本规则',
      disabled: true,
    });
    await scopeSelector.vm.$emit('update:value', 'CURRENT');
    expect(scopeSelector.props('value')).toBe('ALL');
  });

  it('uses the same default minimum-length rule as the server when no global rule is enabled', async () => {
    const wrapper = shallowMount(PasswordPolicyPreview, {
      props: {
        context: {
          ...cardContext(
            () => {
              throw new Error('Password preview must not call the backend');
            },
            {},
            [
              {
                id: 'disabled-rule',
                title: '已停用规则',
                pattern: '.*[A-Z].*',
                message: '缺少大写字母',
                enabled: false,
                scopeType: 'global',
              },
            ],
          ),
          mode: 'view',
        },
      },
    });

    await wrapper.findComponent({ name: 'UiInput' }).vm.$emit('update:value', '12345');

    expect(wrapper.text()).toContain('密码长度不能少于 6 位');
    expect(wrapper.text()).not.toContain('当前没有参与试算的启用规则');
  });
});

function cardContext(
  request: () => never,
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
