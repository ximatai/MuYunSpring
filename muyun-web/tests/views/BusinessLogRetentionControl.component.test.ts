import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import { confirmAction } from '@muyun/vue-ui-antdv';
import BusinessLogRetentionControl from '@/views/BusinessLogRetentionControl.vue';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@muyun/web-core', async (original) => ({
  ...(await original<typeof import('@muyun/web-core')>()),
  useModuleContext: () => ({ http: { request }, can: () => true }),
}));
vi.mock('@muyun/vue-ui-antdv', async (original) => ({
  ...(await original<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(async () => true),
  showInfoMessage: vi.fn(),
}));
afterEach(() => {
  request.mockReset();
  vi.mocked(confirmAction).mockClear();
});

const source = readFileSync(
  resolve(import.meta.dirname, '../../src/views/BusinessLogRetentionControl.vue'),
  'utf8',
);

it('embeds retention as a permission-aware control instead of a standalone page', () => {
  expect(source).toContain("moduleAlias: 'platform.business_log_retention'");
  expect(source).toContain("runtimeAccess: 'VIEW'");
  expect(source).toContain('<UiActionButton v-if="canView" @click="openPanel">');
  expect(source).toContain(
    "language.t('platform.screen.BusinessLogRetentionControl.automatic-cleanup', '自动清理')",
  );
  expect(source).toContain('width="standard"');
  expect(source).toContain('visibleTypes.has(policy.eventType)');
});

it('presents each retention policy as a card with distinct header, content, and save regions', () => {
  expect(source).toContain('class="business-log-retention-control__automatic-cleanup"');
  expect(source).toContain('class="business-log-retention-control__retention-row"');
  expect(source).toMatch(/business-log-retention-control__retention-row[\s\S]*?<UiInput[\s\S]*?立即清理/);
  expect(source).toMatch(
    /<footer>[\s\S]*?>\s*{{[\s\S]*?language\.t\('platform\.action\.save', '保存'\)[\s\S]*?}}\s*<\/UiActionButton>/,
  );
});

it('runs one-off cleanup with the current draft cutoff without requiring a policy save', () => {
  expect(source).toContain('if (!validDays(policy.retentionDays) || operationPending.value) return;');
  expect(source).toContain('{value1} 天');
  expect(source).toContain('value1: policy.retentionDays');
  expect(source).toContain('本次清理不会保存对保留天数的修改');
  expect(source).toContain('client.purge(policy.eventType, policy.retentionDays)');
  expect(source).toContain(':disabled="operationPending || !validDays(policy.retentionDays)"');
  expect(source).not.toContain('请先保存当前策略，再执行清理');
});

it('locks every policy card and the drawer while one retention operation is in flight', () => {
  expect(source).toContain(
    'const operationPending = computed(() => savingType.value !== undefined || purgingType.value !== undefined)',
  );
  expect(source).toMatch(
    /showInfoMessage\([\s\S]*?'platform.screen.BusinessLogRetentionControl.a-cleanup-operation-is-in-progress-please-wait',[\s\S]*?'自动清理操作正在执行，请稍候。'/,
  );
  expect(source).toContain(':disabled="!canConfigure || operationPending"');
  expect(source).toContain(
    ':disabled="operationPending || !validDays(policy.retentionDays) || !isDirty(policy)"',
  );
  expect(source).toContain(':disabled="operationPending || !validDays(policy.retentionDays)"');
});

it('reports an already-running cleanup as a non-successful informational outcome', () => {
  expect(source).toContain("if (run.result.status === 'ALREADY_RUNNING')");
  expect(source).toMatch(
    /showInfoMessage\([\s\S]*?'platform.screen.BusinessLogRetentionControl.a-log-cleanup-task-is-already-running-this-request-was',[\s\S]*?'已有日志清理任务正在运行，本次未执行。'/,
  );
});

it('preserves unsaved retention days across language changes and confirms cleanup in the owner language', async () => {
  request
    .mockResolvedValueOnce([
      { eventType: 'ACTION', automaticCleanupEnabled: false, retentionDays: 180, version: 3 },
    ])
    .mockResolvedValueOnce({ result: { status: 'ALREADY_RUNNING', deletedCount: 0 } });
  const language = createLanguageContext({ languageTag: 'en' });
  const Harness = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () => h(BusinessLogRetentionControl, { eventTypes: ['ACTION'] });
    },
  });
  const wrapper = mount(Harness, {
    global: {
      stubs: {
        RecordDetailDrawer: {
          name: 'RecordDetailDrawer',
          props: ['open', 'title'],
          template: '<section v-if="open"><h2>{{ title }}</h2><slot /></section>',
        },
        UiActionButton: {
          props: ['disabled'],
          emits: ['click'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
        },
        UiInput: {
          name: 'UiInput',
          props: ['value'],
          emits: ['update:value'],
          template: '<input :value="value" @input="$emit(\'update:value\', $event.target.value)" />',
        },
        UiSwitch: { template: '<span />' },
      },
    },
  });
  try {
    await wrapper.find('button').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('Action logs');
    await wrapper.find('input').setValue('30');
    const input = wrapper.findComponent({ name: 'UiInput' });
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(wrapper.text()).toContain('动作日志');
    expect(input.props('value')).toBe(30);
    language.setLanguage('en');
    await flushPromises();
    expect(input.props('value')).toBe(30);
    expect(request).toHaveBeenCalledOnce();
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Clean up now')!
      .trigger('click');
    await flushPromises();
    expect(confirmAction).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Clean up Action logs now',
        requiredText: 'Clean up Action logs',
        content: expect.stringContaining('strictly older than the retention threshold (days: 30)'),
      }),
      language,
    );
    expect(request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/platform.business_log_retention/policies/ACTION/purge',
      body: { retentionDays: 30 },
    });
    expect(input.props('value')).toBe(30);
  } finally {
    wrapper.unmount();
  }
});
