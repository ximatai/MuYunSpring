/** Shared interaction vocabulary for page design and business rendering. */
export function pageActionIntent(code: string, anchor: string) {
  const intents: Record<string, { operation: string; title: string; mode?: 'create' | 'edit' }> = {
    'PAGE:create': { operation: 'OPEN_CREATE', title: '新建' },
    'PAGE:query': { operation: 'REFRESH', title: '刷新' },
    'DETAIL:update': { operation: 'OPEN_EDIT', title: '编辑' },
    'DETAIL:delete': { operation: 'DELETE', title: '删除' },
    'DETAIL:enable': { operation: 'ENABLE', title: '启用' },
    'DETAIL:disable': { operation: 'DISABLE', title: '停用' },
    'FORM:create': { operation: 'SUBMIT_CREATE', title: '保存', mode: 'create' },
    'FORM:update': { operation: 'SUBMIT_UPDATE', title: '保存', mode: 'edit' },
  };
  return intents[`${anchor.toUpperCase()}:${code}`];
}

export function pageActionEntryTitle(entry: { actionCode: string; anchor: string; title?: string }) {
  return entry.title ?? pageActionIntent(entry.actionCode, entry.anchor)?.title ?? entry.actionCode;
}

export function pageActionEntryVisible(
  entry: { actionCode: string; anchor: string },
  mode: 'view' | 'create' | 'edit',
) {
  const anchor = entry.anchor.toUpperCase();
  if (anchor === 'DETAIL') return mode === 'view';
  if (anchor === 'FORM') return mode !== 'view' && pageActionIntent(entry.actionCode, anchor)?.mode === mode;
  return true;
}
export function pageActionEntryDescription(entry: { actionCode: string; anchor: string }) {
  const operation = pageActionIntent(entry.actionCode, entry.anchor)?.operation;
  return (
    (
      {
        OPEN_CREATE: '打开空白表单',
        OPEN_EDIT: '编辑当前记录',
        SUBMIT_CREATE: '校验表单并创建记录',
        SUBMIT_UPDATE: '校验表单并保存修改',
        REFRESH: '重新加载列表',
        DELETE: '确认后删除当前记录',
        ENABLE: '启用当前记录',
        DISABLE: '停用当前记录',
      } as Record<string, string>
    )[operation ?? ''] ?? '暂不支持页面按钮'
  );
}
