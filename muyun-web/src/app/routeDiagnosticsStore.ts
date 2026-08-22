import { defineStore } from 'pinia';
import type { RouteConfigurationIssue } from '@/app/menuRouteCompiler';

export const useRouteDiagnosticsStore = defineStore('routeDiagnostics', {
  state: () => ({
    issues: [] as RouteConfigurationIssue[],
    issuesByMenuId: {} as Record<string, RouteConfigurationIssue[]>,
    issuesByRoute: {} as Record<string, RouteConfigurationIssue[]>,
  }),
  actions: {
    replaceIssues(issues: RouteConfigurationIssue[]) {
      this.issues = issues;
      this.issuesByMenuId = {};
      this.issuesByRoute = {};
      for (const issue of issues) {
        if (issue.menuId)
          this.issuesByMenuId[issue.menuId] = [...(this.issuesByMenuId[issue.menuId] ?? []), issue];
        if (issue.route)
          this.issuesByRoute[issue.route] = [...(this.issuesByRoute[issue.route] ?? []), issue];
      }
    },
    findIssues(menuId?: string, route?: string) {
      const byMenu = menuId ? this.issuesByMenuId[menuId] : undefined;
      return byMenu?.length ? byMenu : route ? (this.issuesByRoute[route] ?? []) : [];
    },
    clear() {
      this.issues = [];
      this.issuesByMenuId = {};
      this.issuesByRoute = {};
    },
  },
});
