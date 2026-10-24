import { mount } from '@vue/test-utils';
import { h } from 'vue';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import ModuleReferenceRecordDetailBrowser from '@/dynamic-page-runtime/ModuleReferenceRecordDetailBrowser.vue';
import RecordDetailDrawer from '@/platform-components/RecordDetailDrawer.vue';
import ReadonlyReferenceValue from '@/platform-components/ReadonlyReferenceValue.vue';
import { UiSidePanel } from '@/vue-ui-antdv';
import type { HttpClient } from '@/web-core';
import '@/styles.css';

it('opens a referenced standard record once without replacing a loading drawer inside the source drawer', async () => {
  await page.viewport(1280, 900);
  let releaseContext!: () => void;
  const contextReady = new Promise<void>((resolve) => (releaseContext = resolve));
  let releaseRecord!: () => void;
  const recordReady = new Promise<void>((resolve) => (releaseRecord = resolve));
  let viewRequested = false;
  const http: HttpClient = {
    async request({ path }) {
      if (path === '/platform.module/crm.customer/view-context') {
        await contextReady;
        return {
          moduleAlias: 'crm.customer',
          capabilities: [],
          actions: [{ actionCode: 'view', authorized: true }],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'crm.customer',
            page: { template: 'LIST_DETAIL_CARD', detail: {} },
          },
        } as never;
      }
      if (path === '/crm.customer/view/customer-b') {
        viewRequested = true;
        await recordReady;
        return { id: 'customer-b', title: '客户 B' } as never;
      }
      if (path === '/crm.customer/actions/customer-b')
        return { recordId: 'customer-b', actions: [{ actionCode: 'view', available: true }] } as never;
      throw new Error(`Unexpected request: ${path}`);
    },
  };
  const wrapper = mount(ModuleReferenceRecordDetailBrowser, {
    props: { http, inlineAnchor: true },
    slots: {
      default: () =>
        h(RecordDetailDrawer, { open: true, title: '来源 A', renderMode: 'inline' }, () =>
          h(ReadonlyReferenceValue, {
            reference: { targetModuleAlias: 'crm.customer', cardinality: 'ONE' },
            value: 'customer-b',
            summary: { id: 'customer-b', title: '客户 B' },
          }),
        ),
    },
    attachTo: document.body,
  });
  try {
    const source = wrapper.getComponent(RecordDetailDrawer);
    const openPanels = () => wrapper.findAllComponents(UiSidePanel).filter((panel) => panel.props('open'));
    await page.getByRole('button', { name: '客户 B', exact: true }).click();
    const sourceElement = document.querySelector('.ant-drawer-open')!;
    // The full-surface owner must be the only target drawer; a short-lived loading drawer restarts entry.
    expect(openPanels()).toHaveLength(1);
    releaseContext();
    await expect.poll(() => viewRequested).toBe(true);
    expect(openPanels()).toHaveLength(1);
    releaseRecord();
    await expect.poll(() => openPanels().length).toBe(2);
    await expect.poll(() => document.querySelectorAll('.ant-drawer-open').length).toBe(2);
    expect(source.props('open')).toBe(true);
    expect(sourceElement.isConnected).toBe(true);
    expect(sourceElement.classList.contains('ant-drawer-open')).toBe(true);
    await page.getByRole('button', { name: '关闭', exact: true }).last().click();
    await expect.poll(() => openPanels().length).toBe(1);
    expect(source.props('open')).toBe(true);
  } finally {
    releaseContext();
    releaseRecord();
    wrapper.unmount();
  }
});
