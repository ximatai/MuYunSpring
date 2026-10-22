import type { PageDescriptor } from '@muyun/web-contracts';

export interface RecordReadEntry {
  moduleAlias: string;
  viewCode: string;
  menuId?: string;
  /** Presentation/default-query hints only; the server resolves these again from a visible menu. */
  params: Readonly<Record<string, unknown>>;
  defaultUiConfigId?: string;
  defaultQueryTemplateId?: string;
}

/**
 * Resolves a business-owned named read view from the existing workbench descriptor.
 * This is presentation selection, never authorization. Pass menuId to the ordinary authenticated
 * transport as X-MuYun-Menu-Id; MenuEntryRequestContext validates the visible menu server-side.
 * Direct links must explicitly declare their default view or be rejected by omitting it.
 */
export function resolveRecordReadEntry(options: {
  moduleAlias: string;
  viewCodes: readonly string[];
  page?: PageDescriptor;
  directViewCode?: string;
  defaultViewCode?: string;
}): RecordReadEntry {
  const { page, moduleAlias } = options;
  if (page?.target.moduleAlias && page.target.moduleAlias !== moduleAlias)
    throw new Error(`Read entry module mismatch: ${moduleAlias}`);
  let params: Record<string, unknown> = {};
  if (page?.entryParamsJson !== undefined) {
    const parsed: unknown = JSON.parse(page.entryParamsJson);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed))
      throw new Error('Read entry parameters require a JSON object');
    params = parsed as Record<string, unknown>;
  }
  const menuView = params.view;
  if (menuView !== undefined && (typeof menuView !== 'string' || !menuView.trim()))
    throw new Error('Read entry requires a named view');
  // A supplied menu must identify its view. Never silently reinterpret a stale or malformed menu
  // as the direct-link default. The shell restores menu context; browser query strings cannot do so.
  const viewCode = page?.menuId ? menuView : (options.directViewCode ?? menuView ?? options.defaultViewCode);
  if (typeof viewCode !== 'string' || !options.viewCodes.includes(viewCode))
    throw new Error(`Unknown read view: ${String(viewCode)}`);
  if (page?.menuId && options.directViewCode && options.directViewCode !== viewCode)
    throw new Error('Read entry view conflicts with menu');
  return {
    moduleAlias,
    viewCode,
    menuId: page?.menuId,
    params,
    ...(page?.pageType === 'dynamic-module' || page?.pageType === 'business-route'
      ? {
          defaultUiConfigId: page.target.defaultUiConfigId,
          defaultQueryTemplateId: page.target.defaultQueryTemplateId,
        }
      : {}),
  };
}
