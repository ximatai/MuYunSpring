import { describe, expect, it, vi } from 'vitest';
import {
  createModuleMenuClient,
  menuDirectoryOptions,
  menuPlacements,
  menuSchemeScopeLabel,
} from '@/platform-admin-runtime/module-menu/moduleMenuClient';
import type { MenuTreeNode } from '@/web-contracts';

describe('module menu maintenance', () => {
  it('keeps scheme context on both tree reads and standard creation', async () => {
    const request = vi.fn().mockResolvedValueOnce({ records: [] }).mockResolvedValueOnce({ id: 'created' });
    const client = createModuleMenuClient({ request });
    await client.tree('scheme-a');
    await client.insert('scheme-a', { title: '客户', moduleAlias: 'crm.customer' } as never, 'request-a');
    expect(request.mock.calls.map(([call]) => call.headers)).toEqual([
      { 'X-MuYun-Page-Context': '{"scheme":"scheme-a"}' },
      { 'X-MuYun-Page-Context': '{"scheme":"scheme-a"}', 'X-Muyun-Save-Request': 'request-a' },
    ]);
    expect(request.mock.calls[1][0].path).toBe('/platform.menu/insert');
    expect(request.mock.calls[1][0].body).toEqual({ title: '客户', moduleAlias: 'crm.customer' });
  });

  it('looks up the original save with its scheme scope and uses the normal module context', async () => {
    const request = vi.fn().mockResolvedValue({ committed: true, recordId: 'created' });
    const client = createModuleMenuClient({ request });
    await client.receipt('scheme-a', 'original-request');
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({
        path: '/platform.menu/save-receipts/original-request',
        headers: { 'X-MuYun-Page-Context': '{"scheme":"scheme-a"}' },
      }),
    );
    await client.context('crm.customer');
    expect(request).toHaveBeenLastCalledWith({ path: '/platform.module/crm.customer/context' });
  });

  it('keeps identical scheme titles distinct by their declared audience', () => {
    expect(menuSchemeScopeLabel({ title: '租户管理', scopeType: 'tenant', tenantId: 'demo' })).toBe(
      '租户 demo 的用户',
    );
    expect(menuSchemeScopeLabel({ title: '租户管理', scopeType: 'tenant', tenantId: 'test1' })).toBe(
      '租户 test1 的用户',
    );
    expect(menuSchemeScopeLabel({ title: '未配置' })).toBe('尚未声明适用范围');
  });

  it('loads all scheme pages instead of silently hiding later choices', async () => {
    const request = vi
      .fn()
      .mockResolvedValueOnce({ records: [{ id: 'a' }], total: 2 })
      .mockResolvedValueOnce({ records: [{ id: 'b' }], total: 2 });
    expect(await createModuleMenuClient({ request }).schemes()).toEqual([{ id: 'a' }, { id: 'b' }]);
    expect(request.mock.calls[1][0].body.page.pageNum).toBe(2);
  });

  it('keeps complete paths and excludes disabled ancestors and business entries as destinations', () => {
    const tree = [
      {
        record: { schemeId: 'scheme', id: 'a', title: '业务', enabled: false },
        children: [{ record: { schemeId: 'scheme', id: 'b', title: '目录' }, children: [] }],
      },
      { record: { schemeId: 'scheme', id: 'c', title: '客户', moduleAlias: 'crm.customer' }, children: [] },
    ] as MenuTreeNode[];
    expect(menuPlacements(tree, '方案').map((item) => item.path)).toEqual([
      '方案 / 业务',
      '方案 / 业务 / 目录',
      '方案 / 客户',
    ]);
    const directories = menuDirectoryOptions(tree);
    expect(directories[0].disabled).toBe(true);
    expect(directories[0].children![0].disabled).toBe(true);
    expect(directories[1].disabled).toBe(true);
  });
});

it('projects menu paths and destination names without changing their identities or raw titles', async () => {
  const { createLanguageContext } = await import('@/web-core');
  const language = createLanguageContext({ languageTag: 'en' });
  const record = {
    id: 'preset-menu',
    title: '平台管理',
    schemeId: 'scheme',
    $texts: {
      title: {
        source: {
          sourceKind: 'CONFIG',
          sourceId: 'config/menu/preset-menu/title',
          sourceVersion: 'v1',
          originalText: '平台管理',
        },
        languages: {
          en: {
            text: 'Platform administration',
            requestedTag: 'en',
            resourceTag: 'en',
            missing: false,
            fallback: false,
          },
        },
      },
    },
  };
  const tree = [
    { record, children: [{ record: { id: 'custom', title: '平台管理', schemeId: 'scheme' }, children: [] }] },
  ] as MenuTreeNode[];
  expect(menuPlacements(tree, 'Scheme', language).map(({ path }) => path)).toEqual([
    'Scheme / Platform administration',
    'Scheme / Platform administration / 平台管理',
  ]);
  expect(menuDirectoryOptions(tree, false, language)[0]).toMatchObject({
    value: 'preset-menu',
    title: 'Platform administration',
  });
  language.setLanguage('zh-CN');
  expect(menuPlacements(tree, '', language)[0]?.path).toBe('平台管理');
  expect(record.title).toBe('平台管理');
});
