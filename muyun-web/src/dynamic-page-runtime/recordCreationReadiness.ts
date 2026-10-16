import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
/** Shared page preconditions; absence of an executable tool is not evidence of missing permission. */
export function recordCreationReadiness(
  state: {
    tenantReady: boolean;
    pageReady: boolean;
    permitted: boolean;
    editing: boolean;
    busy: boolean;
    scopeReady: boolean;
  },
  language: LanguageContext = platformLanguageContext,
) {
  if (!state.tenantReady)
    return {
      ready: false,
      reason: 'TENANT_REQUIRED',
      message: language.t('platform.screen.recordCreationReadiness.select-a-tenant-first', '请先选择租户'),
    };
  if (!state.pageReady)
    return {
      ready: false,
      reason: 'LOADING',
      message: language.t(
        'platform.screen.recordCreationReadiness.the-page-is-loading-please-wait',
        '页面正在加载，请稍候',
      ),
    };
  if (!state.permitted)
    return {
      ready: false,
      reason: 'FORBIDDEN',
      message: language.t(
        'platform.screen.recordCreationReadiness.you-do-not-have-permission-to-create-records',
        '没有新建权限',
      ),
    };
  if (state.editing)
    return {
      ready: false,
      reason: 'DRAFT_ACTIVE',
      message: language.t(
        'platform.screen.recordCreationReadiness.an-unsaved-draft-is-open-you-can-continue-editing-it',
        '已有未保存草稿，可继续编辑；新建其他记录前请先保存或处理当前草稿',
      ),
    };
  if (state.busy)
    return {
      ready: false,
      reason: 'BUSY',
      message: language.t(
        'platform.screen.recordCreationReadiness.wait-for-the-current-operation-to-complete',
        '请等待当前操作完成',
      ),
    };
  if (!state.scopeReady)
    return {
      ready: false,
      reason: 'SCOPE_REQUIRED',
      message: language.t(
        'platform.screen.recordCreationReadiness.select-a-business-navigation-scope-first',
        '请先选择业务导航范围',
      ),
    };
  return { ready: true };
}
