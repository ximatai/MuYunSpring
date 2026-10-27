import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import * as Vue from 'vue';
import { mount } from '@vue/test-utils';
import { beforeAll, expect, it } from 'vitest';
import type * as Consumer from '../../../src/consumer';

// Source types only assist this test runner. ExplorerConsumer.vue separately compiles installed types.
let sdk: Pick<typeof Consumer, 'UiSearchInput' | 'RecordExplorerPanel' | 'UiThemeProvider'>;
let css: string;
beforeAll(() => {
  const bundle = process.env.MUYUN_CONSUMER_CONTRACT_BUNDLE;
  const root = process.env.MUYUN_CONSUMER_PROJECT_ROOT;
  if (!bundle || !root) throw new Error('Run through npm run verify:consumer.');
  sdk = new Function('Vue', `${readFileSync(bundle, 'utf8')}\nreturn MuyunConsumerContract;`)(Vue);
  const require = createRequire(join(root, 'package.json'));
  css = readFileSync(require.resolve('@ximatai/muyun-web-app/style.css'), 'utf8');
});

function searchInput(props = {}) {
  const wrapper = mount(sdk.UiSearchInput, {
    props: {
      value: 'helmet',
      ...props,
      'onUpdate:value': (value: string) => wrapper.setProps({ value }),
    },
  });
  return wrapper;
}
function panel(props = {}) {
  const wrapper = mount(sdk.RecordExplorerPanel, {
    props: {
      title: '项目',
      ...props,
      'onUpdate:searchKeyword': (searchKeyword: string) => wrapper.setProps({ searchKeyword }),
    },
    slots: { default: '<div>项目列表</div>', footer: '<span>项目统计</span>' },
  });
  return wrapper;
}

it.each([false, true])(
  'round-trips controlled input and searches by button and Enter (icon=%s)',
  async (searchIconOnly) => {
    const wrapper = searchInput({ searchIconOnly });
    expect(wrapper.get('input').element.value).toBe('helmet');
    wrapper.get('input').element.value = '工程';
    await wrapper.get('input').trigger('input');
    expect(wrapper.emitted('update:value')).toEqual([['工程']]);
    expect(wrapper.props('value')).toBe('工程');
    expect(wrapper.emitted('search')).toBeUndefined();
    await wrapper.get('button').trigger('click');
    await wrapper.get('input').trigger('keydown', { key: 'Enter', keyCode: 13 });
    expect(wrapper.emitted('search')).toEqual([
      ['工程', 'input'],
      ['工程', 'input'],
    ]);
    expect(wrapper.classes().includes('ui-search-input--integrated')).toBe(searchIconOnly);
    if (searchIconOnly) expect(wrapper.get('button').attributes('aria-label')).toBe('搜索并选择');
  },
);

it.each([false, true])(
  'clears through the actual clear button and Escape (icon=%s)',
  async (searchIconOnly) => {
    const wrapper = searchInput({ searchIconOnly });
    // This is the rendered adapter's clear affordance, not a manually emitted component event.
    await wrapper.get('.ant-input-clear-icon').trigger('click');
    expect(wrapper.emitted('update:value')?.at(-1)).toEqual(['']);
    expect(wrapper.get('input').element.value).toBe('');
    expect(wrapper.emitted('search')).toEqual([['', 'clear']]);
    await wrapper.get('input').setValue('再次搜索');
    await wrapper.get('input').trigger('keydown', { key: 'Escape' });
    expect(wrapper.emitted('update:value')?.at(-1)).toEqual(['']);
    expect(wrapper.get('input').element.value).toBe('');
    expect(wrapper.emitted('search')).toEqual([
      ['', 'clear'],
      ['', 'clear'],
    ]);
  },
);

it('treats empty button and Enter submissions as input searches', async () => {
  const wrapper = searchInput({ value: '' });
  await wrapper.get('button').trigger('click');
  await wrapper.get('input').trigger('keydown', { key: 'Enter', keyCode: 13 });
  expect(wrapper.emitted('search')).toEqual([
    ['', 'input'],
    ['', 'input'],
  ]);
});

it.each([false, true])('disables input, search and clear affordances (icon=%s)', async (searchIconOnly) => {
  const wrapper = searchInput({ disabled: true, searchIconOnly });
  expect(wrapper.get('input').attributes('disabled')).toBeDefined();
  expect(wrapper.get('button').attributes('disabled')).toBeDefined();
  expect(wrapper.get('.ant-input-clear-icon').classes()).toContain('ant-input-clear-icon-hidden');
  await wrapper.get('button').trigger('click');
  expect(wrapper.emitted('search')).toBeUndefined();
  expect(wrapper.emitted('update:value')).toBeUndefined();
});

it('renders title and both slots with search initially collapsed', async () => {
  const wrapper = panel();
  expect(wrapper.get('header').text()).toContain('项目');
  expect(wrapper.get('.record-explorer-panel-content').text()).toBe('项目列表');
  expect(wrapper.get('footer').text()).toBe('项目统计');
  expect(wrapper.find('input').exists()).toBe(false);
  await wrapper.get('button[title="搜索项目"]').trigger('click');
  wrapper.get('input').element.value = '工程';
  await wrapper.get('input').trigger('input');
  expect(wrapper.emitted('update:searchKeyword')).toEqual([['工程']]);
  expect(wrapper.props('searchKeyword')).toBe('工程');
  expect(wrapper.get('input').element.value).toBe('工程');
  await wrapper.get('.ant-input-clear-icon').trigger('click');
  expect(wrapper.props('searchKeyword')).toBe('');
  expect(wrapper.get('input').element.value).toBe('');
});

it.each(['toggle', 'escape'])('clears controlled panel search on collapse by %s', async (action) => {
  const wrapper = panel({ searchKeyword: '工程' });
  expect(wrapper.get('input').element.value).toBe('工程');
  if (action === 'toggle') await wrapper.get('button[title="搜索项目"]').trigger('click');
  else await wrapper.get('input').trigger('keydown', { key: 'Escape' });
  expect(wrapper.emitted('update:searchKeyword')).toEqual([['']]);
  expect(wrapper.props('searchKeyword')).toBe('');
  expect(wrapper.find('input').exists()).toBe(false);
});

it('automatically opens search when the host supplies a keyword after mount', async () => {
  const wrapper = panel();
  await wrapper.setProps({ searchKeyword: '外部条件' });
  expect(wrapper.get('input').element.value).toBe('外部条件');
});

it.each(['header', 'toolbar'])(
  'emits refresh and blocks disabled refresh in %s',
  async (utilityPlacement) => {
    const wrapper = panel({ utilityPlacement });
    const refresh = () =>
      wrapper.get(
        utilityPlacement === 'header' ? 'button[aria-label="刷新：项目"]' : 'button[title="刷新项目"]',
      );
    await refresh().trigger('click');
    expect(wrapper.emitted('refresh')).toEqual([[]]);
    await wrapper.setProps({ refreshDisabled: true });
    expect(refresh().attributes('disabled')).toBeDefined();
    await refresh().trigger('click');
    expect(wrapper.emitted('refresh')).toEqual([[]]);
    await wrapper.setProps({ refreshDisabled: false });
    await refresh().trigger('click');
    expect(wrapper.emitted('refresh')).toEqual([[], []]);
  },
);

it('ships scoped component CSS and base theme tokens through the installed style.css export', () => {
  const input = searchInput({ searchIconOnly: true });
  const explorer = panel();
  // Check actual bundle scope IDs against the installed stylesheet; jsdom cannot prove layout.
  for (const [wrapper, className] of [
    [input, 'ui-search-input--integrated'],
    [explorer, 'record-explorer-panel'],
  ] as const) {
    const scope = Object.keys(wrapper.attributes()).find((name) => name.startsWith('data-v-'));
    expect(scope).toBeDefined();
    expect(css).toContain(`.${className}[${scope}]`);
  }
  expect(css).toContain('.record-explorer-panel-footer[');
  expect(css).toContain('.record-explorer-search[');
  for (const token of ['--muyun-border', '--muyun-surface']) {
    expect(css).toContain(`var(${token}`);
    expect(css).toMatch(new RegExp(`${token}\\s*:`));
  }
  // Palette values are runtime theme variables, while semantic aliases live in style.css.
  const theme = mount(sdk.UiThemeProvider);
  for (const token of ['--muyun-theme-base', '--muyun-theme-focus']) {
    expect(css).toContain(`var(${token}`);
    expect((theme.element as HTMLElement).style.getPropertyValue(token)).not.toBe('');
  }
});
