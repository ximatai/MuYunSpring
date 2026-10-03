/** Shared page preconditions; absence of an executable tool is not evidence of missing permission. */
export function recordCreationReadiness(state: {
  tenantReady: boolean;
  pageReady: boolean;
  permitted: boolean;
  editing: boolean;
  busy: boolean;
  scopeReady: boolean;
}) {
  if (!state.tenantReady) return { ready: false, reason: 'TENANT_REQUIRED', message: '请先选择租户' };
  if (!state.pageReady) return { ready: false, reason: 'LOADING', message: '页面正在加载，请稍候' };
  if (!state.permitted) return { ready: false, reason: 'FORBIDDEN', message: '没有新建权限' };
  if (state.editing)
    return {
      ready: false,
      reason: 'DRAFT_ACTIVE',
      message: '已有未保存草稿，可继续编辑；新建其他记录前请先保存或处理当前草稿',
    };
  if (state.busy) return { ready: false, reason: 'BUSY', message: '请等待当前操作完成' };
  if (!state.scopeReady) return { ready: false, reason: 'SCOPE_REQUIRED', message: '请先选择业务导航范围' };
  return { ready: true };
}
