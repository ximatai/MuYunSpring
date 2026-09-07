import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import RecordRelationTable from '@/platform-components/RecordRelationTable.vue';
import '@/styles.css';

it.each(['default', 'compact'] as const)(
  'keeps unspecified columns readable and scrolls in %s tables',
  async (density) => {
    const host = document.createElement('div');
    host.style.width = '420px';
    document.body.append(host);
    const wrapper = mount(RecordRelationTable, {
      attachTo: host,
      props: {
        density,
        selection: true,
        columns: [
          { fieldName: 'code', title: '库位编码' },
          { fieldName: 'name', title: '库区名称', width: 220 },
          { fieldName: 'date', title: '巡检日期' },
        ],
        rows: [{ code: 'A001', name: '常温库区', date: '2026-09-07' }],
      },
    });
    try {
      const widths = () => wrapper.findAll('th').map((cell) => cell.element.getBoundingClientRect().width);
      expect(widths()[0]).toBeCloseTo(34, 0);
      expect(widths()[1]).toBeCloseTo(160, 0);
      expect(widths()[2]).toBeCloseTo(220, 0);
      expect(widths()[3]).toBeCloseTo(160, 0);
      const scroll = wrapper.get('.managed-relation-inline__scroll').element;
      expect(scroll.clientWidth).toBeLessThanOrEqual(420);
      expect(scroll.scrollWidth).toBeGreaterThan(scroll.clientWidth);
      host.style.width = '900px';
      expect(scroll.scrollWidth).toBe(scroll.clientWidth);
      expect(wrapper.get('table').element.getBoundingClientRect().width).toBeCloseTo(900, 0);
      await wrapper.setProps({ columns: [{ fieldName: 'code', title: '库位编码', width: 100 }] });
      host.style.width = '420px';
      expect(scroll.scrollWidth).toBe(scroll.clientWidth);
    } finally {
      wrapper.unmount();
      host.remove();
    }
  },
);
