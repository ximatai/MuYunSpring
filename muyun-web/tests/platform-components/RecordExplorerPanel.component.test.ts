import { mount } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import RecordExplorerPanel from '@/platform-components/RecordExplorerPanel.vue';

describe('RecordExplorerPanel', () => {
  it('uses the lightweight selected tool-button state while search is expanded', async () => {
    const wrapper = mount(RecordExplorerPanel, { props: { title: '应用列表' } });
    const search = wrapper
      .findAllComponents({ name: 'UiButton' })
      .find((button) => button.props('title') === '搜索应用列表');

    expect(search?.props('type')).toBe('text');
    await search?.trigger('click');

    expect(search?.props('type')).toBe('text');
    expect(search?.props('selected')).toBe(true);
    expect(wrapper.find('.record-explorer-search').exists()).toBe(true);
  });

  it('uses the primary surface and foreground for selected search', () => {
    const source = readFileSync(
      resolve(import.meta.dirname, '../../src/vue-ui-antdv/components/UiButton.vue'),
      'utf8',
    );

    expect(source).toContain(':global(.ant-btn.ui-button--selected.ui-button--selected:not(:disabled))');
    expect(source).toContain('color: var(--muyun-primary)');
  });
});
