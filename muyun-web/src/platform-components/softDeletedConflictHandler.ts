import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
import { normalizeError, platformErrorCodes, type AppError } from '@muyun/web-core';
import { confirmAction as defaultConfirmAction, type UiConfirmOptions } from '@muyun/vue-ui-antdv';
import type { PlatformActionErrorHandler } from './platformErrorFeedback';

type ConfirmAction = (options: UiConfirmOptions, language?: LanguageContext) => Promise<boolean>;

/**
 * 软删除冲突定位信息，从后端 RESOURCE_SOFT_DELETED_CONFLICT 错误详情中提取。
 */
export interface SoftDeletedConflictInfo {
  /** 冲突资源所属模块 alias，如 iam.tenant */
  moduleAlias: string;
  /** 冲突资源记录 ID */
  recordId: string;
  /** 删除时间（ISO 字符串） */
  deletedAt?: string;
  /** 是否可恢复 */
  recoveryAvailable: boolean;
}

/**
 * 从 AppError 中提取软删除冲突信息。
 * 如果错误不是 RESOURCE_SOFT_DELETED_CONFLICT，返回 undefined。
 */
export function extractSoftDeletedConflict(cause: unknown): SoftDeletedConflictInfo | undefined {
  const error = normalizeError(cause);
  if (error.code !== platformErrorCodes.resourceSoftDeletedConflict) {
    return undefined;
  }
  const details = error.details ?? {};
  return {
    moduleAlias: String(details.resourceModuleAlias ?? ''),
    recordId: String(details.resourceRecordId ?? ''),
    deletedAt: details.deletedAt != null ? String(details.deletedAt) : undefined,
    recoveryAvailable: Boolean(details.recoveryAvailable),
  };
}

export interface SoftDeletedConflictHandlerOptions {
  language?: LanguageContext;
  /** 资源展示名称，用于提示文案，如“租户”、“组织” */
  resourceLabel?: string;
  /** 用户点击“去回收站恢复”时的回调 */
  onNavigateToRecycleBin?: (info: SoftDeletedConflictInfo) => void;
  /** 确认弹窗实现，默认使用平台 confirmAction，测试时可注入 fake */
  confirmAction?: ConfirmAction;
}

export interface SoftDeletedConflictHandler {
  /**
   * 尝试处理错误。如果是软删除冲突，弹出引导弹窗并返回 true；否则返回 false。
   */
  handle: (cause: unknown) => Promise<boolean>;
}

/**
 * 创建适用于 StaticCrudManagementState.actionErrorHandlers 的软删除冲突处理器。
 *
 * @example
 * ```ts
 * const state = useFlatCrudManagementState({
 *   // ...
 *   actionErrorHandlers: [
 *     createSoftDeletedConflictErrorHandler({
 *       resourceLabel: '租户',
 *       onNavigateToRecycleBin: () => switchToRecycleBin(),
 *     }),
 *   ],
 * });
 * ```
 */
export function createSoftDeletedConflictErrorHandler<TContext>(
  options: SoftDeletedConflictHandlerOptions = {},
): PlatformActionErrorHandler<TContext> {
  const handler = useSoftDeletedConflictHandler(options);
  return {
    code: platformErrorCodes.resourceSoftDeletedConflict,
    handle: (error: AppError) => {
      void handler.handle(error);
      return true;
    },
  };
}

/**
 * 平台级软删除冲突处理器。
 *
 * 当新建记录因唯一键与回收站中已删除记录冲突时，
 * 弹出说明弹窗并引导用户前往回收站恢复。
 *
 * @example
 * ```ts
 * const conflictHandler = useSoftDeletedConflictHandler({
 *   resourceLabel: '租户',
 *   onNavigateToRecycleBin: () => switchToRecycleBin(),
 * });
 *
 * try {
 *   await crud.insert(formValues);
 * } catch (cause) {
 *   if (await conflictHandler.handle(cause)) return;
 *   presentPlatformError(cause, { source: 'tenant-create', phase: 'action' });
 * }
 * ```
 */
export function useSoftDeletedConflictHandler(
  options: SoftDeletedConflictHandlerOptions = {},
): SoftDeletedConflictHandler {
  const language = options.language ?? platformLanguageContext;
  const { resourceLabel = '记录', onNavigateToRecycleBin, confirmAction = defaultConfirmAction } = options;

  return {
    handle: async (cause: unknown): Promise<boolean> => {
      const info = extractSoftDeletedConflict(cause);
      if (!info) {
        return false;
      }

      const confirmed = await confirmAction(
        {
          title: `该${resourceLabel}已存在于回收站`,
          content: `${resourceLabel}「${info.recordId}」已被软删除，位于回收站中。您可以前往回收站恢复原${resourceLabel}，或使用其他标识创建新${resourceLabel}。`,
          okText: '去回收站恢复',
          cancelText: '知道了',
        },
        language,
      );

      if (confirmed && onNavigateToRecycleBin) {
        onNavigateToRecycleBin(info);
      }

      return true;
    },
  };
}
