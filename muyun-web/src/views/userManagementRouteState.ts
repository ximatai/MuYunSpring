import type { LocationQuery } from 'vue-router';

export type UserRouteAction = 'add' | 'view' | 'edit';

export interface UserManagementRouteState {
  action?: UserRouteAction;
  userId?: string;
  error?: string;
}

/** 用户地址只表达资源和页面状态，InstanceKey 仅区分页签实例。 */
export function userManagementRouteStateOf(
  userId: string | undefined,
  query: LocationQuery,
): UserManagementRouteState {
  const action = singleQueryValue(query.userAction);
  const instanceKey = singleQueryValue(query.InstanceKey);
  const legacyKey = ['recordId', 'id', 'tenantId'].find((key) => key in query);
  if (legacyKey) return { error: `用户页面不再支持 ${legacyKey} 参数` };

  if (!userId) {
    if (!action && !instanceKey) return {};
    if (action !== 'add') return { error: '用户列表地址不能携带用户操作或 InstanceKey 参数' };
    return validInstanceKey(instanceKey)
      ? { action: 'add' }
      : { error: '新建用户页面缺少有效的 InstanceKey 参数' };
  }

  if (action && action !== 'edit') return { error: `不支持的用户页面操作：${action}` };
  if (!validInstanceKey(instanceKey)) return { error: '用户详情页面缺少有效的 InstanceKey 参数' };
  return { action: action === 'edit' ? 'edit' : 'view', userId };
}

/** 给每个地址操作提供页面标题的通用文字。 */
export function userActionTitle(action: UserRouteAction): string {
  if (action === 'add') return '新建用户';
  if (action === 'edit') return '编辑用户';
  return '查看用户';
}

function validInstanceKey(value: string | undefined) {
  return Boolean(
    value && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value),
  );
}

/** 只接受单个且非空的地址值，重复值会被视为无效。 */
function singleQueryValue(value: LocationQuery[string]): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}
