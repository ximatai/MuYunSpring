import {
  createMenuClient,
  createModuleCrudClient,
  withHttpHeaders,
  type HttpClient,
  type ModuleRuntimeContext,
} from '@muyun/web-core';
import type { MenuRecord, MenuScheme, MenuTreeNode, WebListResponse } from '@muyun/web-contracts';
import type { UiTreeSelectNode } from '@muyun/vue-ui-antdv';

export interface MenuPlacement {
  menu: MenuRecord;
  path: string;
}

/** Menu maintenance keeps its existing scheme scope and standard CRUD authorization. */
export function createModuleMenuClient(http: HttpClient) {
  const schemes = createModuleCrudClient<MenuScheme>(http, { moduleAlias: 'platform.menu_scheme' });
  return {
    async schemes() {
      const records: MenuScheme[] = [];
      for (let pageNum = 1; ; pageNum++) {
        const page = await schemes.query({ page: { pageNum, pageSize: 100 } });
        records.push(...page.records);
        if (records.length >= page.total || page.records.length === 0) return records;
      }
    },
    async tree(schemeId: string) {
      const result = await http.request<WebListResponse<MenuTreeNode>>({
        method: 'POST',
        path: '/platform.menu/tree/query',
        headers: { 'X-MuYun-Page-Context': JSON.stringify({ scheme: schemeId }) },
        body: { externalQueryValues: { schemeId } },
      });
      return result.records;
    },
    context(moduleAlias: string) {
      return http.request<ModuleRuntimeContext>({
        path: `/platform.module/${encodeURIComponent(moduleAlias)}/context`,
      });
    },
    insert(schemeId: string, record: MenuRecord, requestId: string) {
      return createModuleCrudClient<MenuRecord>(
        withHttpHeaders(http, { 'X-MuYun-Page-Context': JSON.stringify({ scheme: schemeId }) }),
        { moduleAlias: 'platform.menu' },
      ).insert(record, { requestId });
    },
    view(schemeId: string, recordId: string) {
      return createModuleCrudClient<MenuRecord>(
        withHttpHeaders(http, { 'X-MuYun-Page-Context': JSON.stringify({ scheme: schemeId }) }),
        { moduleAlias: 'platform.menu' },
      ).view(recordId);
    },
    receipt(schemeId: string, requestId: string) {
      return createModuleCrudClient<MenuRecord>(
        withHttpHeaders(http, { 'X-MuYun-Page-Context': JSON.stringify({ scheme: schemeId }) }),
        { moduleAlias: 'platform.menu' },
      ).saveReceipt!(requestId);
    },
    visible: () =>
      createMenuClient(http)
        .mine()
        .then((result) => result.records),
  };
}

export function menuSchemeScopeLabel(scheme?: MenuScheme) {
  if (!scheme) return '尚未确定';
  if (scheme.scopeType === 'system') return '系统配置用户';
  if (scheme.organizationId) return `机构 ${scheme.organizationId} 的用户`;
  if (scheme.tenantId) return `租户 ${scheme.tenantId} 的用户`;
  return '尚未声明适用范围';
}

export function menuPlacements(nodes: MenuTreeNode[], parentPath = ''): MenuPlacement[] {
  return nodes.flatMap(({ record, children }) => {
    const path = parentPath ? `${parentPath} / ${record.title}` : record.title;
    return [{ menu: record, path }, ...menuPlacements(children, path)];
  });
}

export function menuDirectoryOptions(nodes: MenuTreeNode[], parentDisabled = false): UiTreeSelectNode[] {
  return nodes.map(({ record, children }) => {
    const disabled = parentDisabled || record.enabled === false;
    return {
      value: record.id,
      title: record.title,
      disabled: disabled || Boolean(record.moduleAlias),
      children: menuDirectoryOptions(children, disabled),
    };
  });
}
