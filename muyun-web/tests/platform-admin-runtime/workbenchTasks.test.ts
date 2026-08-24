import { assert, it } from 'vitest';
import type { Component } from 'vue';
import {
  createWorkspaceViewDescriptor,
  createWorkspaceViewRegistry,
  dismissWorkspaceViewDescriptor,
  defineWorkspaceView,
} from '@/platform-admin-runtime/workspaceViews.ts';
import { canPromoteWorkspaceView } from '@/platform-admin-runtime/useWorkspaceViewPromotion.ts';
import { roleDetailWorkspaceView } from '@/views/roleDetailWorkspaceView.ts';
import { roleAuthorizationWorkspaceView } from '@/views/roleAuthorizationWorkspaceView.ts';

const view = defineWorkspaceView({
  type: 'iam.user.detail',
  route: '/iam/users',
  moduleAlias: 'iam.user',
  component: {} as Component,
  presentations: ['drawer', 'tab'],
  titleOf: (input: { recordId: string }) => `用户：${input.recordId}`,
  parse: (query) => {
    const recordId = query.recordId;
    if (typeof recordId !== 'string' || !recordId) {
      return undefined;
    }
    return { recordId };
  },
});

it('workspace view registry restores only a declared and valid view input', () => {
  const registry = createWorkspaceViewRegistry([view]);
  const descriptor = createWorkspaceViewDescriptor(view, { recordId: 'user-1' });

  assert.deepEqual(registry.resolve(descriptor), {
    view,
    input: { recordId: 'user-1' },
    presentation: 'tab',
  });
  assert.equal(
    registry.resolve({
      ...descriptor,
      target: { ...descriptor.target, query: { workspaceView: view.type } },
    }),
    undefined,
  );
});

it('workspace view descriptors have stable parameter identity and presentation', () => {
  const descriptor = createWorkspaceViewDescriptor(view, { recordId: 'user-1' });

  assert.equal(descriptor.title, '用户：user-1');
  assert.equal(descriptor.tabPolicy?.identity, 'by-params');
  assert.deepEqual(descriptor.target.query, {
    workspaceView: 'iam.user.detail',
    workspacePresentation: 'tab',
    recordId: 'user-1',
  });
});

it('workspace view descriptors may use a resolved main title without changing URL identity', () => {
  const descriptor = createWorkspaceViewDescriptor(view, { recordId: 'user-1' }, 'tab', 'alice');

  assert.equal(descriptor.title, 'alice');
  assert.deepEqual(descriptor.target.query, {
    workspaceView: 'iam.user.detail',
    workspacePresentation: 'tab',
    recordId: 'user-1',
  });
});

it('workspace drawer descriptors are restored and dismissed as ordinary route descriptors', () => {
  const drawer = createWorkspaceViewDescriptor(view, { recordId: 'user-1' }, 'drawer');
  const registry = createWorkspaceViewRegistry([view]);

  assert.equal(registry.resolve(drawer)?.presentation, 'drawer');
  assert.deepEqual(dismissWorkspaceViewDescriptor(drawer, view).target.query, {});
  assert.deepEqual(dismissWorkspaceViewDescriptor(drawer, view).params, {});
});

it('workspace view registry rejects duplicate view types', () => {
  assert.throws(() => createWorkspaceViewRegistry([view, view]), /重复的工作视图类型/);
});

it('workspace view promotion requires a stable, idle source host', () => {
  assert.equal(canPromoteWorkspaceView({ hasStableIdentity: false }), false);
  assert.equal(canPromoteWorkspaceView({ hasStableIdentity: true, busy: true }), false);
  assert.equal(canPromoteWorkspaceView({ hasStableIdentity: true, canChangeHost: false }), false);
  assert.equal(canPromoteWorkspaceView({ hasStableIdentity: true }), true);
});

it('role detail workspace identity includes its authorization scope', () => {
  const descriptor = createWorkspaceViewDescriptor(roleDetailWorkspaceView, {
    recordId: 'role-1',
    scopeKind: 'organization',
    scopeId: 'org-1',
  });
  const registry = createWorkspaceViewRegistry([roleDetailWorkspaceView]);

  assert.deepEqual(descriptor.target.query, {
    workspaceView: 'iam.role.detail',
    workspacePresentation: 'tab',
    recordId: 'role-1',
    scopeKind: 'organization',
    scopeId: 'org-1',
  });
  assert.deepEqual(registry.resolve(descriptor)?.input, {
    recordId: 'role-1',
    scopeKind: 'organization',
    scopeId: 'org-1',
  });
  assert.equal(
    registry.resolve({
      ...descriptor,
      target: { ...descriptor.target, query: { ...descriptor.target.query, scopeId: undefined } },
    }),
    undefined,
  );
});

it('dismissing a role drawer restores its owner scope without retaining work-view identity', () => {
  const drawer = createWorkspaceViewDescriptor(
    roleDetailWorkspaceView,
    { recordId: 'role-1', scopeKind: 'organization', scopeId: 'org-1' },
    'drawer',
  );

  const dismissed = dismissWorkspaceViewDescriptor(drawer, roleDetailWorkspaceView);

  assert.deepEqual(dismissed.target.query, { scopeKind: 'organization', scopeId: 'org-1' });
  assert.deepEqual(dismissed.params, { scopeKind: 'organization', scopeId: 'org-1' });
  assert.equal(dismissed.target.query?.recordId, undefined);
  assert.equal(dismissed.target.query?.workspaceView, undefined);
  assert.equal(dismissed.target.query?.workspacePresentation, undefined);
});

it('role authorization workspace is a wide work drawer with role-only identity', () => {
  const descriptor = createWorkspaceViewDescriptor(
    roleAuthorizationWorkspaceView,
    { roleId: 'role-1' },
    'drawer',
  );
  const registry = createWorkspaceViewRegistry([roleAuthorizationWorkspaceView]);

  assert.equal(roleAuthorizationWorkspaceView.drawerProfile, 'wide-work');
  assert.deepEqual(descriptor.target.query, {
    workspaceView: 'iam.role.authorization',
    workspacePresentation: 'drawer',
    roleId: 'role-1',
  });
  assert.deepEqual(registry.resolve(descriptor)?.input, { roleId: 'role-1' });
  assert.equal(
    registry.resolve({
      ...descriptor,
      target: { ...descriptor.target, query: { ...descriptor.target.query, roleId: '' } },
    }),
    undefined,
  );
});
