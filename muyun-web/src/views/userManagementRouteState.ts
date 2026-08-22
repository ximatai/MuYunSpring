import type { LocationQuery } from 'vue-router';

export type UserRouteAction = 'add' | 'view' | 'edit';

export interface UserManagementRouteState {
  action?: UserRouteAction;
  userId?: string;
  error?: string;
}

/** 用户地址只表达业务页面状态；页签实例由工作台内部状态管理。 */
export function userManagementRouteStateOf(
  routePath: string | undefined,
  userId: string | undefined,
  query: LocationQuery,
): UserManagementRouteState {
  const action = singleQueryValue(query.action);
  const legacyKey = ['recordId', 'id', 'tenantId', 'userAction'].find((key) => key in query);
  if (legacyKey) return { error: `用户页面不再支持 ${legacyKey} 参数` };

  if (routePath === '/iam/user') {
    if (userId) return { error: '用户列表地址不能携带用户编号' };
    if (action) return { error: '用户列表地址不能携带 action 参数' };
    return {};
  }

  if (routePath === '/iam/user/form') {
    if (userId) return { error: '新建用户地址不能携带用户编号' };
    if (action !== 'add') return { error: '新建用户地址必须携带 action=add' };
    return { action: 'add' };
  }

  if (routePath === '/iam/user/form/:userId') {
    if (!userId) return { error: '用户表单地址缺少用户编号' };
    if (action !== 'view' && action !== 'edit')
      return { error: '用户表单地址必须携带 action=view 或 action=edit' };
    return { action, userId };
  }

  return { error: '不支持的用户页面地址' };
}

/** 给每个地址操作提供页面标题的通用文字。 */
export function userActionTitle(action: UserRouteAction): string {
  if (action === 'add') return '新建用户';
  if (action === 'edit') return '编辑用户';
  return '查看用户';
}

/** 只接受单个且非空的地址值，重复值会被视为无效。 */
function singleQueryValue(value: LocationQuery[string]): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}
