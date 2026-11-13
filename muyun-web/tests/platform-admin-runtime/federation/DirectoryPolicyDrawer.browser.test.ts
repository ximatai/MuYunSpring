import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import {
  ModuleContextProvider,
  useLanguageContext,
  type ModuleContext,
  type HttpClient,
  type HttpRequestOptions,
} from '@muyun/web-core';
import { UiSidePanelHost, UiThemeProvider } from '@muyun/vue-ui-antdv';
import { RecordDetailDrawer } from '@muyun/platform-components';
import DrawerTitleActions from '@/platform-components/DrawerTitleActions.vue';
import { useModulePageDetailExtensionRuntime } from '@/dynamic-page-runtime/composables/useModulePageDetailExtensionRuntime';
import {
  tenantIdentityPolicyPageEnhancement,
  directorySyncRunPageEnhancement,
} from '@/platform-admin-runtime/federationPageEnhancements';
import { configureModulePageEnhancementContributions } from '@muyun/dynamic-page-runtime';
import type { ModulePageDrawer } from '@/dynamic-page-runtime/modulePageEnhancements';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';

function mountPolicy(http: HttpClient) {
  const context = {
    moduleAlias: 'iam.tenant_identity_policy',
    can: () => true,
    recordActions: async () => ({ actions: [] }),
    http,
  } as unknown as ModuleContext<Record<string, unknown>>;
  const Harness = defineComponent({
    setup() {
      const language = useLanguageContext();
      const runtime = useModulePageDetailExtensionRuntime({
        module: context,
        scope: () => undefined,
        refreshList: () => {},
        reload: () => {},
        closeDetail: () => {},
      });
      tenantIdentityPolicyPageEnhancement.detail!.actions![2]!.run({
        module: context,
        record: { id: 'policy' },
        openDrawer: (drawer: ModulePageDrawer) => runtime.openDrawer(drawer, { id: 'policy' }),
      } as never);
      return () =>
        h(UiThemeProvider, {}, () =>
          h(UiSidePanelHost, { style: 'height: 100vh' }, () =>
            h(ModuleContextProvider, { context }, () =>
              h(
                RecordDetailDrawer,
                {
                  open: runtime.drawerOpen.value,
                  title: language.t(
                    runtime.drawer.value!.definition.titleKey!,
                    runtime.drawer.value!.definition.title,
                  ),
                  renderMode: 'inline',
                  width: 'standard',
                },
                {
                  default: () =>
                    h(runtime.drawer.value!.definition.component, { context: runtime.drawer.value!.context }),
                  operation: () =>
                    runtime.drawer.value?.operation &&
                    h(DrawerTitleActions, { actions: runtime.drawer.value.operation.actions }),
                },
              ),
            ),
          ),
        );
    },
  });
  return mount(Harness, { attachTo: document.body });
}

it.each([1100, 640])('keeps the impact report and standard confirmation usable at %ipx', async (width) => {
  await page.viewport(width, 782);
  const wrapper = mountPolicy({
    request: async () =>
      ({
        scopeId: 'pending-scope',
        runId: 'topic-run',
        counts: { departments: 2, people: 10, positions: 1, grants: 1 },
      }) as never,
  });
  try {
    await flushPromises();
    const confirm = page.getByRole('button', { name: '确认范围变更并同步', exact: true });
    await expect.element(confirm).toBeVisible();
    await expect.element(page.getByText('查看范围变更影响', { exact: true })).toBeVisible();
    await expect.element(page.getByText('将回收的来源授权', { exact: true })).toBeVisible();
    const panel = wrapper.get('.record-detail-layout').element.getBoundingClientRect();
    const operation = wrapper.get('.record-detail-layout-operation').element.getBoundingClientRect();
    expect(panel.left).toBeGreaterThanOrEqual(0);
    expect(panel.right).toBeLessThanOrEqual(width);
    expect(operation.bottom).toBeLessThanOrEqual(782);
    const content = wrapper.get('.record-detail-layout-content').element;
    expect(content.scrollWidth).toBeLessThanOrEqual(content.clientWidth + 1);
    content.scrollTop = content.scrollHeight;
    await expect.element(confirm).toBeVisible();
    await page.screenshot({ path: `../../../test-results/federation-scope-${width}.png` });
  } finally {
    wrapper.unmount();
  }
});

it('opens the reviewed preview, confirms once and delegates retry to the standard job detail', async () => {
  await page.viewport(1100, 850);
  const requests: HttpRequestOptions[] = [];
  const runtime = (moduleAlias: string, job = false) => ({
    moduleAlias,
    capabilities: [],
    actions: [
      { actionCode: 'view', authorized: true },
      ...(job
        ? [
            {
              actionCode: 'retry',
              title: '重试失败运行',
              actionLevel: 'RECORD',
              category: 'CUSTOM',
              authorized: true,
            },
          ]
        : []),
    ],
    uiDescriptor: {
      schemaVersion: '1',
      moduleAlias,
      page: {
        template: 'LIST_DETAIL_CARD',
        managedActions: true,
        detail: {
          display: {
            viewCode: 'detail',
            viewKind: 'FORM',
            fields: [{ fieldRef: { fieldName: 'status' }, label: '运行状态', valueType: 'STRING' }],
          },
        },
        ...(job
          ? {
              actions: [
                {
                  actionCode: 'retry',
                  title: '重试失败运行',
                  anchor: 'DETAIL',
                  operation: 'INVOKE',
                  invocation: { method: 'POST', path: '/platform.job_run/{recordId}/retry', input: 'NONE' },
                },
              ],
            }
          : {}),
      },
    },
  });
  const http: HttpClient = {
    async request(options) {
      requests.push(options);
      const { path } = options;
      if (path === '/iam.tenant_identity_policy/policy/scopeImpact')
        return {
          data: {
            scopeId: 'pending-scope',
            runId: 'topic-run',
            counts: { departments: 1, people: 7, positions: 0, grants: 0 },
          },
        } as never;
      if (path === '/iam.tenant_identity_policy/policy/confirmScope') return { data: 'sync-job' } as never;
      if (path === '/platform.module/iam.directory_sync_run/view-context')
        return runtime('iam.directory_sync_run') as never;
      if (path === '/platform.module/platform.job_run/view-context')
        return runtime('platform.job_run', true) as never;
      if (
        path === '/iam.directory_sync_run/actions/topic-run' ||
        path === '/platform.job_run/actions/sync-job'
      )
        return {
          recordId: path.endsWith('topic-run') ? 'topic-run' : 'sync-job',
          actions: [
            { actionCode: 'view', available: true },
            { actionCode: 'retry', available: true },
          ],
        } as never;
      if (path === '/iam.directory_sync_run/view/topic-run')
        return {
          id: 'topic-run',
          status: 'SUCCEEDED',
          jobRunId: 'preview-job',
          statistics: JSON.stringify({
            organizations: 1,
            people: 7,
            duplicates: 0,
            conflicts: 0,
            failedPages: 0,
            unknownStatus: 0,
          }),
          failedPages: '[]',
        } as never;
      if (path === '/platform.job_run/view/sync-job') return { id: 'sync-job', status: 'FAILED' } as never;
      if (path === '/platform.job_run/sync-job/retry') return { data: { id: 'retry-job' } } as never;
      throw new Error(`Unexpected local test request: ${path}`);
    },
  };
  configureModulePageEnhancementContributions('federation-browser-test', [directorySyncRunPageEnhancement]);
  const wrapper = mountPolicy(http);
  try {
    await expect.element(page.getByRole('button', { name: '查看预览运行', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '查看预览运行', exact: true }).click();
    await expect.element(page.getByText('已读取人员', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '关闭', exact: true }).last().click();
    await page.getByRole('button', { name: '确认范围变更并同步', exact: true }).click();
    await expect.element(page.getByText('sync-job', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '查看作业运行', exact: true }).click();
    await expect
      .poll(() => requests.filter((item) => item.path === '/platform.job_run/view/sync-job').length)
      .toBeGreaterThan(0);
    await expect.element(page.getByRole('button', { name: '重试失败运行', exact: true })).toBeVisible();
    await page.screenshot({ path: '../../../test-results/federation-job-detail.png' });
    await page.getByRole('button', { name: '重试失败运行', exact: true }).click();
    await expect
      .poll(() => requests.filter((item) => item.path === '/platform.job_run/sync-job/retry').length)
      .toBe(1);
    expect(requests.filter((item) => item.path.endsWith('/confirmScope'))).toHaveLength(1);
    expect(requests.find((item) => item.path.endsWith('/confirmScope'))?.body).toEqual({
      scopeId: 'pending-scope',
      runId: 'topic-run',
    });
    expect(requests.some((item) => item.path.endsWith('/query'))).toBe(false);
  } finally {
    wrapper.unmount();
    configureModulePageEnhancementContributions('federation-browser-test', []);
  }
});
