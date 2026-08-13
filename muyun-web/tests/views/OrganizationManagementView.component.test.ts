import { mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { assert, it } from 'vitest';
import type { CurrentUser } from '@/web-contracts/index.ts';
import OrganizationManagementView from '@/views/OrganizationManagementView.vue';
import RecordExplorerPanel from '@/platform-components/RecordExplorerPanel.vue';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext.ts';
import { configureModuleContext, createHttpClient, ModuleContextProvider } from '@/web-core/index.ts';

it('organization management renders tenant and organization explorer panels for system users', () => {
  configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
  const Harness = defineComponent({
    setup() {
      provideCurrentUserContext(ref({ system: true } as CurrentUser));
      return () =>
        h(
          ModuleContextProvider,
          { moduleAlias: 'iam.organization' },
          { default: () => h(OrganizationManagementView) },
        );
    },
  });

  const wrapper = mount(Harness, {
    global: {
      stubs: {
        ManagementWorkspace: { template: '<section><slot /></section>' },
        ManagementExplorerColumn: { template: '<aside><slot /></aside>' },
        CrudRecordListExplorer: { template: '<div />' },
        ModuleActionButton: { template: '<button />' },
        TreeRecordExplorer: { template: '<div />' },
        RecordDetailPanel: { template: '<main><slot /><slot name="actions" /><slot name="status" /></main>' },
        RecordActionBar: { template: '<div />' },
        RecordStatusSwitch: { template: '<div />' },
        RecordPicker: { template: '<div />' },
        RecordMetaSection: { template: '<div />' },
        UiEmpty: { template: '<div />' },
        UiInput: { template: '<input />' },
      },
    },
  });

  const panels = wrapper.findAllComponents(RecordExplorerPanel);
  assert.equal(panels.length, 2);
  assert.deepEqual(
    panels.map((panel) => panel.props('title')),
    ['租户', '机构树'],
  );

  wrapper.unmount();
});
