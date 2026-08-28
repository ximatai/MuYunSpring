import '@muyun/vue-ui-antdv/styles.css';
import '../../src/styles.css';

import { createApp, nextTick, type App as VueApp } from 'vue';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { page } from 'vitest/browser';
import ManagementWorkspaceBrowserFixture from './ManagementWorkspaceBrowserFixture.vue';

let application: VueApp<Element> | undefined;
let host: HTMLElement | undefined;

describe('管理工作区浏览器布局契约', () => {
  beforeAll(async () => {
    host = document.createElement('div');
    host.id = 'app';
    document.body.append(host);
    application = createApp(ManagementWorkspaceBrowserFixture);
    application.mount(host);
    await nextTick();
    if (!document.body.textContent?.includes('参考状态')) {
      throw new Error('Management workspace browser fixture did not render its detail fields');
    }
  });

  afterAll(() => {
    application?.unmount();
    host?.remove();
  });

  it('桌面工作区将 explorer 拉伸到可用高度，并把滚动留在内部列表', () => {
    const workspace = requiredElement<HTMLElement>('.browser-management-workspace');
    const grid = requiredElement<HTMLElement>('.browser-management-workspace .management-workspace__grid');
    const explorer = requiredElement<HTMLElement>('.browser-management-workspace .record-explorer-panel');
    const records = requiredElement<HTMLElement>('.browser-management-workspace .record-list-explorer');

    expect(closeTo(workspace.getBoundingClientRect().height, grid.getBoundingClientRect().height)).toBe(true);
    expect(closeTo(explorer.getBoundingClientRect().height, grid.getBoundingClientRect().height)).toBe(true);
    expect(getComputedStyle(workspace).overflowY).toBe('hidden');
    expect(getComputedStyle(records).overflowY).toBe('auto');
    expect(records.scrollHeight).toBeGreaterThan(records.clientHeight);
  });

  it('详情字段内容由详情面板滚动，不被工作区裁切', async () => {
    const detailContent = requiredElement<HTMLElement>('.record-detail-layout-content');
    const lastField = requiredElement<HTMLElement>('.browser-field-section tbody tr:last-child');

    expect(getComputedStyle(detailContent).overflowY).toBe('auto');
    expect(detailContent.scrollHeight).toBeGreaterThan(detailContent.clientHeight);
    lastField.scrollIntoView({ block: 'end' });
    await nextAnimationFrame();

    const detailBounds = detailContent.getBoundingClientRect();
    const fieldBounds = lastField.getBoundingClientRect();
    expect(fieldBounds.bottom).toBeLessThanOrEqual(detailBounds.bottom + 1);
    expect(fieldBounds.top).toBeGreaterThanOrEqual(detailBounds.top - 1);
  });

  it('980px 下 listSurface 仍在宿主宽度内分配 explorer 与主体', async () => {
    await page.viewport(980, 814);
    await nextTick();

    const workspace = requiredElement<HTMLElement>('.browser-management-workspace');
    const grid = requiredElement<HTMLElement>('.browser-management-workspace .management-workspace__grid');
    const explorer = requiredElement<HTMLElement>(
      '.browser-management-workspace .management-explorer-column',
    );

    expect(grid.scrollWidth).toBeLessThanOrEqual(workspace.clientWidth + 1);
    expect(grid.getBoundingClientRect().width).toBeLessThanOrEqual(
      workspace.getBoundingClientRect().width + 1,
    );
    expect(explorer.getBoundingClientRect().width).toBeGreaterThan(0);
  });
});

function requiredElement<T extends Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (!element) throw new Error(`Expected browser fixture to render ${selector}`);
  return element;
}

function closeTo(left: number, right: number, tolerance = 1) {
  return Math.abs(left - right) <= tolerance;
}

function nextAnimationFrame() {
  return new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
}
