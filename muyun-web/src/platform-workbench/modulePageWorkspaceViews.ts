import { modulePageWorkspaceViews, type ModulePageWorkspaceView } from '@muyun/dynamic-page-runtime';
import { configureWorkspaceViewContributions } from './workspaceViews';

/**
 * Publishes application-contributed module workspace views before URL or
 * preference restoration resolves a workbench tab.  The module enhancement
 * registry is the source of truth; this only adapts it to the Workbench view
 * registry.
 */
export function syncModulePageWorkspaceViewContributions() {
  configureWorkspaceViewContributions(
    'module-page-enhancements',
    modulePageWorkspaceViews().map(workspaceViewDefinitionForModulePage),
  );
}

function workspaceViewDefinitionForModulePage(view: ModulePageWorkspaceView) {
  return {
    ...view,
    route: view.route ?? `/_workspace/${encodeURIComponent(view.type)}`,
    presentations: ['tab'] as const,
  };
}
