import type { Component } from 'vue';
import type { PageLayoutMode, RouteQueryValue } from '@muyun/web-contracts';

export type WorkspaceViewInput = object;
export type WorkspaceViewPresentation = 'drawer' | 'tab';
export type WorkspaceDrawerProfile = 'detail' | 'wide-work';

/** Stable, URL-restorable declaration shared by all workbench view hosts. */
export interface WorkspaceViewDefinition<TInput extends WorkspaceViewInput> {
  type: string;
  route: string;
  moduleAlias: string;
  component: Component;
  layout?: PageLayoutMode;
  drawerProfile?: WorkspaceDrawerProfile;
  routeTitle?: string;
  titleOf(input: TInput): string;
  /**
   * Returns the input fields that identify one workbench tab. Other parsed
   * input fields remain URL-restorable view state and can change in-place.
   */
  tabIdentityParamsOf?(input: TInput): Record<string, RouteQueryValue>;
  parentRouteQueryOf?(input: TInput): Record<string, RouteQueryValue>;
  parse(query: Record<string, RouteQueryValue>): TInput | undefined;
  presentations: readonly WorkspaceViewPresentation[];
}

export function defineWorkspaceView<TInput extends WorkspaceViewInput>(
  definition: WorkspaceViewDefinition<TInput>,
) {
  return definition;
}
