import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import RecordQueryListSurface from '@/platform-components/RecordQueryListSurface.vue';
import '@/styles.css';
import 'ant-design-vue/dist/reset.css';

it('keeps shared pagination muted, compact, interactive, and inside a narrow list surface', async () => {
  await page.viewport(992, 814);
  const wrapper = mount(RecordQueryListSurface, {
    attachTo: document.body,
    attrs: { style: 'width: 393px; height: 260px' },
    props: {
      headerVisible: false,
      columns: [],
      rows: [],
      tableVisible: false,
      pageable: true,
      total: 41,
      pageNum: 2,
      pages: 3,
      pageSize: 20,
      pageSizeOptions: [10, 20, 50],
    },
  });
  try {
    const footer = wrapper.get('.record-query-list-pagination').element;
    const controls = wrapper.get('.record-query-list-pagination-controls').element;
    const total = [...footer.querySelectorAll('span')].find((element) => element.textContent === '共 41 条')!;
    const mutedProbe = document.createElement('span');
    mutedProbe.style.color = 'var(--muyun-text-muted)';
    document.body.append(mutedProbe);
    try {
      expect(getComputedStyle(total).fontSize).toBe('13px');
      expect(getComputedStyle(total).color).toBe(getComputedStyle(mutedProbe).color);
    } finally {
      mutedProbe.remove();
    }
    expect(getComputedStyle(controls).gap).toBe('8px');
    expect(footer.scrollWidth).toBeLessThanOrEqual(footer.clientWidth + 1);
    expect(wrapper.element.scrollWidth).toBeLessThanOrEqual(wrapper.element.clientWidth + 1);
    const navigation = wrapper.get('.record-query-list-page-navigation').element;
    expect(navigation.scrollWidth).toBe(navigation.clientWidth);
    expect(wrapper.get('[aria-label="下一页"]').element.getBoundingClientRect().top).toBeCloseTo(
      wrapper.get('[aria-label="上一页"]').element.getBoundingClientRect().top,
      0,
    );
    expect(navigation.getBoundingClientRect().top).toBeLessThanOrEqual(total.getBoundingClientRect().bottom);
    expect(navigation.getBoundingClientRect().bottom).toBeGreaterThanOrEqual(
      total.getBoundingClientRect().top,
    );

    wrapper.element.style.width = '250px';
    await wrapper.vm.$nextTick();
    expect(footer.scrollWidth).toBeLessThanOrEqual(footer.clientWidth + 1);
    expect(navigation.scrollWidth).toBe(navigation.clientWidth);
    expect(wrapper.get('[aria-label="下一页"]').element.getBoundingClientRect().top).toBeCloseTo(
      wrapper.get('[aria-label="上一页"]').element.getBoundingClientRect().top,
      0,
    );

    await page.elementLocator(wrapper.get('[aria-label="下一页"]').element).click();
    expect(wrapper.emitted('pageChange')).toEqual([[3]]);
  } finally {
    wrapper.unmount();
  }
});
