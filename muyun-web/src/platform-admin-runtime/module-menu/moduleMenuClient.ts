import { createMenuClient, createModuleCrudClient, withHttpHeaders, type HttpClient } from '@muyun/web-core';
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
    insert(schemeId: string, record: MenuRecord) {
      return createModuleCrudClient<MenuRecord>(
        withHttpHeaders(http, { 'X-MuYun-Page-Context': JSON.stringify({ scheme: schemeId }) }),
        { moduleAlias: 'platform.menu' },
      ).insert(record);
    },
    visible: () =>
      createMenuClient(http)
        .mine()
        .then((result) => result.records),
  };
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
