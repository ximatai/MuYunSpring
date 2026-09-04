/**
 * Runtime registry for local workspace drafts. Draft values stay inside their
 * owning panel; the workbench only needs a truthful answer before destroying
 * that panel by closing its tab.
 */
const sourcesByPageKey = new Map<string, Map<string, () => boolean>>();

export function registerWorkspaceViewUnsavedState(
  pageKey: string,
  source: string,
  isDirty: () => boolean,
): () => void {
  const sources = sourcesByPageKey.get(pageKey) ?? new Map<string, () => boolean>();
  sources.set(source, isDirty);
  sourcesByPageKey.set(pageKey, sources);
  return () => {
    const current = sourcesByPageKey.get(pageKey);
    if (!current) return;
    current.delete(source);
    if (current.size === 0) sourcesByPageKey.delete(pageKey);
  };
}

export function workspaceViewUnsavedStateSources(pageKey: string): string[] {
  return [...(sourcesByPageKey.get(pageKey) ?? new Map())].flatMap(([source, isDirty]) => {
    try {
      return isDirty() ? [source] : [];
    } catch {
      // A failing optional signal must never prevent a user from leaving a page.
      return [];
    }
  });
}

export function clearWorkspaceViewUnsavedState(pageKey: string) {
  sourcesByPageKey.delete(pageKey);
}
