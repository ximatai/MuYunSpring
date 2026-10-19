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

// A closed dialog does not mount its controls; preference restoration must remain independent.
const CollapsedDialog = defineComponent({
  props: { open: Boolean },
  setup:
    (props, { slots }) =>
    () =>
      props.open ? slots.default?.() : null,
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
    { global: { stubs: { UiModal: CollapsedDialog } } },
  );
  await flushPromises();
  expect(language.languageTag.value).toBe('en');
  expect(businessContentLanguageContext(language).languageTag.value).toBe('de');
  expect(wrapper.findComponent({ name: 'UiSelect' }).exists()).toBe(false);
  expect(wrapper.find('button').exists()).toBe(false);
  expect(businessContentLanguageContext(language).languageTag.value).toBe('de');
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
        h('div', [slots.default?.(), slots.operation?.()]),
  });
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(WorkbenchLanguageSettings, { identityScope: 'one-country', open: true });
      },
    }),
    { global: { stubs: { UiModal: Popover } } },
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
