import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import {
  AppError,
  createHttpClient,
  createModuleContext,
  createWorkflowDefinitionClient,
  createAssistantSurfaceRegistry,
  provideAssistantSurfaceHost,
  ModuleContextProvider,
  type HttpClient,
  type HttpRequestOptions,
} from '@muyun/web-core';
import type { CurrentUser } from '@muyun/web-contracts';
import { UiThemeProvider, UiSidePanelHost } from '@muyun/vue-ui-antdv';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext';
import { providePageLayout } from '@/platform-components/pageLayoutContext';
import WorkflowConfigurationView from '@/views/WorkflowConfigurationView.vue';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';

declare const __PLATFORM_SERVICE_ENABLED__: boolean;
declare const __PLATFORM_SERVICE_TOKEN__: string;

// Opt-in: this scenario creates and publishes a definition in a disposable school-demo database.
it.skipIf(!__PLATFORM_SERVICE_ENABLED__)(
  'recovers a lost committed save and a concurrent publication through real authorized endpoints',
  async () => {
    await page.viewport(1280, 900);
    const transport = createHttpClient({ baseUrl: '/api', token: __PLATFORM_SERVICE_TOKEN__ });
    const user = await transport.request<CurrentUser>({ path: '/iam.auth/context' });

    const client = createWorkflowDefinitionClient(transport, 'education.student');
    const definition = await client.create({
      title: `保存恢复验收 ${Date.now()}`,
      approvalEnabled: true,
      organizationId: null,
      matchExpression: null,
      defaultDefinition: false,
      enabled: true,
    });
    let loseResponse = true;
    let saves = 0;
    let originalSave:
      | { version: number; design: import('@muyun/web-contracts').WorkflowDesign; requestId: string }
      | undefined;
    let rejectedSaveStatus: number | undefined;
    const http: HttpClient = {
      async request<T>(options: HttpRequestOptions) {
        if (options.method === 'POST' && options.path.endsWith('/design'))
          originalSave = JSON.parse(JSON.stringify(options.body)) as typeof originalSave;
        const result = await transport.request<T>(options).catch((cause: unknown) => {
          if (options.method === 'POST' && options.path.endsWith('/design') && cause instanceof AppError)
            rejectedSaveStatus = cause.status;
          throw cause;
        });
        if (options.method === 'POST' && options.path.endsWith('/design')) {
          saves++;
          if (loseResponse) {
            loseResponse = false;
            throw new TypeError('Simulated lost response after server commit');
          }
        }
        return result;
      },
    };
    const context = createModuleContext({ moduleAlias: 'platform.workflow.definition', http });
    const registry = createAssistantSurfaceRegistry();
    registry.activate('workflow-service');
    const Harness = defineComponent({
      setup() {
        providePageLayout('workspace');
        provideCurrentUserContext(ref(user));
        provideAssistantSurfaceHost({ registry, activePageInstanceKey: () => 'workflow-service' });
        return () =>
          h(UiThemeProvider, {}, () =>
            h(UiSidePanelHost, {}, () =>
              h(ModuleContextProvider, { context }, () =>
                h(WorkflowConfigurationView, {
                  moduleAlias: 'education.student',
                  definitionId: definition.id,
                }),
              ),
            ),
          );
      },
    });
    let wrapper = mount(Harness, {
      attachTo: document.body,
      global: { stubs: { RecordDetailDrawer: true } },
    });
    (wrapper.element as HTMLElement).style.cssText = 'display:block;height:800px';
    try {
      await expect
        .poll(() => registry.snapshot()?.capabilities.some((item) => item.code === 'workflow.revise-draft'), {
          timeout: 15000,
        })
        .toBe(true);
      const snapshot = registry.snapshot()!;
      await registry.invoke(
        {
          id: 'revise',
          code: 'workflow.revise-draft',
          input: {
            operations: [
              {
                op: 'update-node',
                node: 'approval',
                patch: { title: '助手审批', participants: [{ type: 'INITIATOR_SELF' }] },
              },
            ],
          },
        },
        snapshot.token,
      );
      const graph = () => wrapper.findAllComponents({ name: 'WorkflowDiagram' }).at(-1)!;
      graph().vm.$emit('select', 'approval');
      await flushPromises();
      await page.getByRole('textbox', { name: '节点名称', exact: true }).fill('人工调整审批');
      await page.getByRole('button', { name: '完成配置', exact: true }).click();
      await page.getByRole('button', { name: '保存草稿', exact: true }).click();
      await expect.poll(() => wrapper.find('.configuration-recovery').exists()).toBe(true);
      const [saved] = await client.versions(definition.id);
      const persisted = await client.design(definition.id, saved.id);
      expect(persisted.nodes.find((node) => node.nodeKey === 'approval')?.title).toBe('人工调整审批');
      expect(graph().props('editable')).toBe(false);
      expect(saves).toBe(1);
      const replay = await client.saveDesign(
        definition.id,
        saved.id,
        originalSave!.version,
        originalSave!.design,
        originalSave!.requestId,
      );
      expect(replay.version).toBe(saved.version);
      expect((await client.saveReceipt(definition.id, saved.id, originalSave!.requestId)).committed).toBe(
        true,
      );
      const abandonedId = crypto.randomUUID();
      expect((await client.abandonSave(definition.id, saved.id, abandonedId)).cancelled).toBe(true);
      await expect(
        client.saveDesign(definition.id, saved.id, saved.version, persisted, abandonedId),
      ).rejects.toMatchObject({ status: 400 });
      expect((await client.versions(definition.id))[0].version).toBe(saved.version);
      wrapper.unmount();
      wrapper = mount(Harness, {
        attachTo: document.body,
        global: { stubs: { RecordDetailDrawer: true } },
      });
      (wrapper.element as HTMLElement).style.cssText = 'display:block;height:800px';
      await expect.poll(() => wrapper.text()).toContain('检测到上次保存的查询引用');
      await expect.poll(() => graph().props('editable')).toBe(false);
      const recoveryButton = (title: string) =>
        wrapper
          .findAllComponents({ name: 'UiButton' })
          .find((button) => button.element.closest('.configuration-recovery') && button.text() === title)!;
      await expect.poll(() => recoveryButton('读取当前版本').props('disabled')).toBe(false);
      await recoveryButton('读取当前版本').trigger('click');
      await expect
        .poll(() => wrapper.findAllComponents({ name: 'WorkflowDesignProperties' }).length)
        .toBeGreaterThan(0);
      await expect.poll(() => wrapper.text()).toContain('已确认原保存请求提交成功');
      expect(recoveryButton('保留本地草稿并重新核对').props('disabled')).toBe(true);
      await recoveryButton('采用当前版本').trigger('click');
      await flushPromises();
      expect(saves).toBe(1);
      expect(graph().props('editable')).toBe(true);
      // A different editor publishes while this page still holds the draft version.
      const currentDefinition = await client.view(definition.id);
      await client.publish(definition.id, saved.id, currentDefinition.version, saved.version);
      graph().vm.$emit('select', 'approval');
      await flushPromises();
      await page.getByRole('textbox', { name: '节点名称', exact: true }).fill('并发发布后的本地修改');
      await page.getByRole('button', { name: '完成配置', exact: true }).click();
      await page.getByRole('button', { name: '保存草稿', exact: true }).click();
      await expect
        .poll(async () => (await client.versions(definition.id))[0].publishStatus)
        .toBe('published');
      await flushPromises();
      await expect
        .poll(
          () =>
            wrapper
              .findComponent({ name: 'AdaptiveHeaderActionBar' })
              .props('actions')
              .find((action: { key: string; disabled?: boolean }) => action.key === 'read-current')?.disabled,
        )
        .toBe(false);
      expect(rejectedSaveStatus).toBe(400);
      // A definite 400 rejection must still have a path to inspect the frozen version.
      wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'read-current' });
      await expect.poll(() => recoveryButton('采用当前版本')?.exists()).toBe(true);
      expect(recoveryButton('保留本地草稿并重新核对').props('disabled')).toBe(true);
      await recoveryButton('采用当前版本').trigger('click');
      await flushPromises();
      expect(graph().props('editable')).toBe(false);
      expect(
        graph()
          .props('nodes')
          .find((node: { nodeKey: string }) => node.nodeKey === 'approval').title,
      ).toBe('人工调整审批');
    } finally {
      wrapper.unmount();
    }
  },
  60000,
);
