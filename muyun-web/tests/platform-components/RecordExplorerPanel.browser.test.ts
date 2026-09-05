import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import { UiButton } from '@muyun/vue-ui-antdv';
import RecordExplorerPanel from '@/platform-components/RecordExplorerPanel.vue';
import '@/styles.css';
import 'ant-design-vue/dist/reset.css';

it('keeps selected search hover styling aligned with the shared button adapter', async () => {
  const panel = mount(RecordExplorerPanel, {
    attachTo: document.body,
    props: { title: '应用列表' },
  });
  const reference = mount(UiButton, {
    attachTo: document.body,
    props: { type: 'text', iconOnly: true, size: 'small', selected: true },
  });
  const search = panel.get('button[title="搜索应用列表"]');

  await search.trigger('click');
  await page.elementLocator(search.element).hover();
  const searchStyle = getComputedStyle(search.element);
  const searchColor = searchStyle.color;
  const searchBackgroundColor = searchStyle.backgroundColor;
  const searchBorderTopColor = searchStyle.borderTopColor;

  await page.elementLocator(reference.element).hover();

  const referenceStyle = getComputedStyle(reference.element);
  expect(searchColor).toBe(referenceStyle.color);
  expect(searchBackgroundColor).toBe(referenceStyle.backgroundColor);
  expect(searchBorderTopColor).toBe(referenceStyle.borderTopColor);
});
