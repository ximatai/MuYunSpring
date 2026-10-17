import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import {
  businessContentLanguageContext,
  createLanguageContext,
  provideLanguageContext,
  userPreferences,
} from '@muyun/web-core';
import WorkbenchLanguageSettings from '@/platform-workbench/WorkbenchLanguageSettings.vue';

// The real popover lazily mounts controls. Preference ownership must not depend on that mount.
const CollapsedPopover = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      slots.default?.(),
});
afterEach(() => vi.restoreAllMocks());
it('restores both preferences before the settings controls are opened', async () => {
  vi.spyOn(userPreferences, 'get').mockImplementation((_, fallback) => fallback);
  vi.spyOn(userPreferences, 'restore').mockImplementation(
    async (key, fallback) =>
      (key === 'business-content.language'
        ? 'de'
        : key === 'interface.language'
          ? 'en'
          : fallback) as typeof fallback,
  );
  const language = createLanguageContext();
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(WorkbenchLanguageSettings, { identityScope: 'qa-user' });
      },
    }),
    { global: { stubs: { UiPopover: CollapsedPopover } } },
  );
  await flushPromises();
  expect(language.languageTag.value).toBe('en');
  expect(businessContentLanguageContext(language).languageTag.value).toBe('de');
  expect(wrapper.findComponent({ name: 'UiSelect' }).exists()).toBe(false);
  expect(wrapper.get('button').text()).toContain('Deutsch');
  expect(userPreferences.restore).toHaveBeenCalledWith('business-content.language', undefined, {
    persistence: 'backend',
  });
  wrapper.unmount();
});

it('omits a redundant interface choice with one enabled UI language while keeping business reading languages available', async () => {
  vi.spyOn(userPreferences, 'get').mockImplementation((_, fallback) => fallback);
  vi.spyOn(userPreferences, 'restore').mockResolvedValue(undefined);
  vi.spyOn(userPreferences, 'set').mockResolvedValue(undefined);
  vi.spyOn(userPreferences, 'remove').mockResolvedValue(undefined);
  const language = createLanguageContext();
  language.setCatalog({
    ...language.catalog.value,
    languages: language.catalog.value.languages.map((entry) => ({
      ...entry,
      enabled: entry.tag === 'zh-CN',
    })),
  });
  const Popover = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.content?.()]),
  });
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(WorkbenchLanguageSettings, { identityScope: 'one-country' });
      },
    }),
    { global: { stubs: { UiPopover: Popover } } },
  );
  await flushPromises();
  const controls = wrapper.findAllComponents({ name: 'UiSelect' });
  expect(controls).toHaveLength(1);
  expect(controls[0]!.props('options').map((item: { value: string }) => item.value)).toEqual([
    '__follow__',
    'zh-CN',
    'en',
    'de',
  ]);
  controls[0]!.vm.$emit('update:value', 'de');
  await flushPromises();
  expect(language.languageTag.value).toBe('zh-CN');
  expect(businessContentLanguageContext(language).languageTag.value).toBe('de');
  expect(userPreferences.set).toHaveBeenCalledWith('business-content.language', 'de', {
    persistence: 'backend',
  });
  controls[0]!.vm.$emit('update:value', '__follow__');
  await flushPromises();
  expect(businessContentLanguageContext(language).requestedLanguageTag.value).toBeUndefined();
  wrapper.unmount();
});
