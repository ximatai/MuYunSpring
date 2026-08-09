import { assert, it } from 'vitest';
import {
  emptyModuleDraft,
  isValidModule,
  moduleValidationMessage,
  normalizeModuleDraft,
} from '@/views/moduleManagementState.ts';

it('module management draft stays inside the selected application', () => {
  const draft = emptyModuleDraft('crm');

  assert.equal(draft.applicationAlias, 'crm');
  assert.equal(draft.moduleKind, 'static');
  assert.equal(draft.entryType, 'module');
  assert.equal(draft.enabled, true);
});

it('module management normalizer preserves standard fields and clears inactive entry values', () => {
  const normalized = normalizeModuleDraft(
    {
      id: 'crm.customer',
      alias: ' crm.customer ',
      title: ' 客户管理 ',
      applicationAlias: 'wrong',
      parentId: ' ',
      entryType: 'module',
      entryRoute: '/crm/customers',
      entryExternalUrl: 'https://example.test/customers',
      enabled: true,
      version: 3,
    },
    'crm',
  );

  assert.equal(normalized.id, 'crm.customer');
  assert.equal(normalized.alias, 'crm.customer');
  assert.equal(normalized.title, '客户管理');
  assert.equal(normalized.applicationAlias, 'crm');
  assert.equal(normalized.parentId, undefined);
  assert.equal(normalized.entryRoute, undefined);
  assert.equal(normalized.entryExternalUrl, undefined);
  assert.equal(normalized.version, 3);
});

it('module management normalizer keeps only the entry value required by the selected entry type', () => {
  const route = normalizeModuleDraft(
    { alias: 'crm.order', title: '订单', entryType: 'route', entryRoute: ' /crm/orders ' },
    'crm',
  );
  const link = normalizeModuleDraft(
    {
      alias: 'crm.bi',
      title: '分析',
      entryType: 'link',
      entryRoute: '/crm/analytics',
      entryExternalUrl: ' https://bi.example.test ',
    },
    'crm',
  );

  assert.equal(route.entryRoute, '/crm/orders');
  assert.equal(route.entryExternalUrl, undefined);
  assert.equal(link.entryRoute, undefined);
  assert.equal(link.entryExternalUrl, 'https://bi.example.test');
  assert.equal(isValidModule(route), true);
  assert.equal(isValidModule({ ...route, alias: '' }), false);
  assert.equal(moduleValidationMessage({ ...route, entryRoute: undefined }), '请输入内部路由');
  assert.equal(moduleValidationMessage({ ...link, entryExternalUrl: undefined }), '请输入外部链接');
});
