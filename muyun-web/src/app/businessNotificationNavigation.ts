import type {
  PageDescriptor,
  MenuTreeNode,
  WebBusinessNotificationNavigateAction,
} from '@muyun/web-contracts';
import {
  resolvePageDescriptor,
  type PageDescriptorResolveOptions,
} from '../platform-workbench/menuNavigation';
import { findMenuByDescriptor } from './workbenchStartup';

/** Notifications carry module intent; the registered page catalog decides its host. */
export function businessNotificationPageDescriptor(
  action: WebBusinessNotificationNavigateAction,
  options: PageDescriptorResolveOptions,
  menus: MenuTreeNode[] = [],
): PageDescriptor {
  const params = { ...(action.query ?? {}), ...(action.recordId ? { recordId: action.recordId } : {}) };
  const descriptor = resolvePageDescriptor(
    {
      menuType: 'module',
      openMode: 'tab',
      moduleAlias: action.moduleAlias,
      pageMode: action.pageMode ?? 'LIST',
      query: params,
    },
    options,
  );
  return {
    ...descriptor,
    title: descriptor.title ?? findMenuByDescriptor(menus, descriptor, options)?.title,
    tabPolicy: { identity: action.recordId ? 'by-params' : 'by-target', closable: true, cacheable: true },
  };
}
