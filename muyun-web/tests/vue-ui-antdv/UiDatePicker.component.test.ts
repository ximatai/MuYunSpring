import { mount } from '@vue/test-utils';
import { CalendarDatePicker as DatePicker } from '@/vue-ui-antdv/datePickerCalendar';
import { expect, it } from 'vitest';
import UiDatePicker from '@/vue-ui-antdv/components/UiDatePicker.vue';
import UiInput from '@/vue-ui-antdv/components/UiInput.vue';
import { parseDatePickerValue } from '@/vue-ui-antdv/datePickerValue';

it('exposes controlled strings while keeping vendor date objects inside the adapter', async () => {
  const wrapper = mount(UiDatePicker, {
    props: { value: '2026-10-20T14:15:37', mode: 'datetime', ariaLabel: '生效时间' },
  });
  try {
    expect(wrapper.get('input').element.value).toBe('2026-10-20 14:15:37');
    expect(wrapper.get('input').attributes('aria-label')).toBe('生效时间');
    const picker = wrapper.getComponent(DatePicker);
    picker.vm.$emit('change', parseDatePickerValue('2026-11-01T01:30:17', 'datetime'));
    expect(wrapper.emitted('update:value')).toEqual([['2026-11-01T01:30:17']]);
    await wrapper.setProps({ value: '2026-12-01T09:00:00' });
    expect(wrapper.get('input').element.value).toBe('2026-12-01 09:00:00');
    picker.vm.$emit('change', null);
    expect(wrapper.emitted('update:value')?.at(-1)).toEqual(['']);
  } finally {
    wrapper.unmount();
  }
});
it('does not coerce invalid model values or write through a disabled picker', async () => {
  const wrapper = mount(UiDatePicker, { props: { value: '2026-02-30', disabled: true } });
  try {
    expect(wrapper.find('.ant-picker-status-error').exists()).toBe(true);
    expect(wrapper.emitted('update:value')).toBeUndefined();
    wrapper.getComponent(DatePicker).vm.$emit('change', parseDatePickerValue('2026-02-28', 'date'));
    expect(wrapper.emitted('update:value')).toBeUndefined();
    await wrapper.setProps({ value: '2026-02-28', disabled: false });
    expect(wrapper.find('.ant-picker-status-error').exists()).toBe(false);
  } finally {
    wrapper.unmount();
  }
});
it('upgrades the existing date input entry without changing its events or value contract', async () => {
  const wrapper = mount(UiInput, {
    props: { type: 'datetime-local', value: '2026-10-20T14:15:37', step: 1, ariaLabel: '预约时间' },
  });
  try {
    const picker = wrapper.getComponent(UiDatePicker);
    expect(picker.props('mode')).toBe('datetime');
    expect(picker.props('precision')).toBe('second');
    expect(wrapper.get('input').attributes('type')).not.toBe('datetime-local');
    picker.vm.$emit('update:value', '2026-10-21T10:00:00');
    expect(wrapper.emitted('update:value')).toEqual([['2026-10-21T10:00:00']]);
    await wrapper.setProps({ step: 60 });
    expect(picker.props('precision')).toBe('minute');
  } finally {
    wrapper.unmount();
  }
});
it('reserves Enter for the open calendar instead of submitting the owning form early', () => {
  const wrapper = mount(UiDatePicker);
  try {
    const picker = wrapper.getComponent(DatePicker);
    picker.vm.$emit('openChange', true);
    picker.vm.$emit('keydown', new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(wrapper.emitted('keydown')).toBeUndefined();
    picker.vm.$emit('openChange', false);
    picker.vm.$emit('keydown', new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(wrapper.emitted('keydown')).toHaveLength(1);
  } finally {
    wrapper.unmount();
  }
});

it('follows the platform language context without discarding the controlled time', async () => {
  const { h } = await import('vue');
  const { createLanguageContext } = await import('@/web-core/language');
  const { default: UiLanguageProvider } = await import('@/vue-ui-antdv/components/UiLanguageProvider.vue');
  const context = createLanguageContext();
  context.setCatalog({
    ...context.catalog.value,
    languages: context.catalog.value.languages.map((language) =>
      language.tag === 'fr' ? { ...language, enabled: true } : language,
    ),
  });
  const wrapper = mount(UiLanguageProvider, {
    props: { context },
    slots: { default: () => h(UiDatePicker, { mode: 'datetime', value: '2026-10-20T14:15:37' }) },
  });
  try {
    const vendor = wrapper.getComponent(DatePicker);
    expect(vendor.props('locale')?.lang.locale).toBe('zh_CN');
    for (const [language, locale] of [
      ['en', 'en_US'],
      ['fr', 'fr_FR'],
    ]) {
      context.setLanguage(language!);
      await wrapper.vm.$nextTick();
      expect(vendor.props('locale')?.lang.locale).toBe(locale);
      expect(wrapper.get('input').element.value).toBe('2026-10-20 14:15:37');
    }
  } finally {
    wrapper.unmount();
  }
});
