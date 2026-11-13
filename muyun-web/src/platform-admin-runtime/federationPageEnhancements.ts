import { defineComponent, h, type Component, type PropType } from 'vue';
import type { ModulePageDrawerContext, ModulePageEnhancement } from '@muyun/dynamic-page-runtime';
import DirectoryPolicyDrawer from './federation/DirectoryPolicyDrawer.vue';
import JoinApplicationReviewDrawer from './federation/JoinApplicationReviewDrawer.vue';
import DirectorySyncSummary from './federation/DirectorySyncSummary.vue';

function actionSurface(component: Component, action: string) {
  return defineComponent({
    props: { context: { type: Object as PropType<ModulePageDrawerContext>, required: true } },
    setup: (props) => () => h(component, { context: props.context as ModulePageDrawerContext, action }),
  });
}

export const tenantIdentityPolicyPageEnhancement: ModulePageEnhancement = {
  id: 'iam-directory-policy-operations',
  target: { moduleAlias: 'iam.tenant_identity_policy' },
  detail: {
    actions: [
      {
        key: 'directory-preview',
        actionCode: 'preview',
        title: '预览通讯录',
        titleKey: 'platform.federation.preview',
        run: ({ openDrawer }) =>
          openDrawer({
            title: '预览通讯录',
            titleKey: 'platform.federation.preview',
            component: actionSurface(DirectoryPolicyDrawer, 'preview'),
          }),
      },
      {
        key: 'directory-synchronize',
        actionCode: 'synchronize',
        title: '立即同步',
        titleKey: 'platform.federation.synchronize',
        run: ({ openDrawer }) =>
          openDrawer({
            title: '立即同步',
            titleKey: 'platform.federation.synchronize',
            component: actionSurface(DirectoryPolicyDrawer, 'synchronize'),
          }),
      },
      {
        key: 'directory-scope-impact',
        actionCode: 'scopeImpact',
        title: '查看范围变更影响',
        titleKey: 'platform.federation.scope-impact',
        run: ({ openDrawer }) =>
          openDrawer({
            title: '查看范围变更影响',
            titleKey: 'platform.federation.scope-impact',
            component: actionSurface(DirectoryPolicyDrawer, 'scopeImpact'),
          }),
      },
    ],
  },
};

export const joinApplicationPageEnhancement: ModulePageEnhancement = {
  id: 'iam-join-application-review',
  target: { moduleAlias: 'iam.join_application' },
  detail: {
    actions: (['approve', 'reject'] as const).map((action) => ({
      key: `join-${action}`,
      actionCode: action,
      title: action === 'approve' ? '批准加入' : '拒绝加入',
      titleKey: `platform.federation.${action}`,
      state: (record) => ({ visible: record.status === 'pending' }),
      run: ({ openDrawer }) =>
        openDrawer({
          title: action === 'approve' ? '批准加入' : '拒绝加入',
          titleKey: `platform.federation.${action}`,
          component: actionSurface(JoinApplicationReviewDrawer, action),
        }),
    })),
  },
};

export const directorySyncRunPageEnhancement: ModulePageEnhancement = {
  id: 'iam-directory-run-results',
  target: { moduleAlias: 'iam.directory_sync_run' },
  detail: {
    sections: [
      {
        key: 'directory-results',
        title: '同步统计',
        titleKey: 'platform.federation.summary',
        component: DirectorySyncSummary,
      },
    ],
  },
};
