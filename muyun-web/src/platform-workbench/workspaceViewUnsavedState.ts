/**
 * Runtime registry for local workspace drafts. Draft values stay inside their
 * owning panel; the workbench only needs a truthful answer before destroying
 * that panel by closing its tab. Dirty state requires a discard confirmation;
 * an in-flight mutation is owned by its session and blocks destruction outright.
 */
interface WorkspaceViewStateSignal {
  source: string;
  isDirty: () => boolean;
  isBusy?: () => boolean;
}

const sourcesByPageKey = new Map<string, Map<symbol, WorkspaceViewStateSignal>>();

export function registerWorkspaceViewUnsavedState(
  pageKey: string,
  source: string,
  isDirty: () => boolean,
  isBusy?: () => boolean,
): () => void {
  const sources = sourcesByPageKey.get(pageKey) ?? new Map<symbol, WorkspaceViewStateSignal>();
  const token = Symbol(source);
  sources.set(token, { source, isDirty, isBusy });
  sourcesByPageKey.set(pageKey, sources);
  return () => {
    const current = sourcesByPageKey.get(pageKey);
    if (!current) return;
    current.delete(token);
    if (current.size === 0) sourcesByPageKey.delete(pageKey);
  };
}

export function workspaceViewUnsavedStateSources(pageKey: string): string[] {
  return [...(sourcesByPageKey.get(pageKey) ?? new Map())].flatMap(([, state]) => {
    try {
      return state.isDirty() ? [state.source] : [];
    } catch {
      // A failing optional signal must never prevent a user from leaving a page.
      return [];
    }
  });
}

/** Sources with an in-flight mutation cannot be destroyed until their work completes. */
export function workspaceViewBusyStateSources(pageKey: string): string[] {
  return [...(sourcesByPageKey.get(pageKey) ?? new Map())].flatMap(([, state]) => {
    try {
      return state.isBusy?.() === true ? [state.source] : [];
    } catch {
      return [];
    }
  });
}

export function clearWorkspaceViewUnsavedState(pageKey: string) {
  sourcesByPageKey.delete(pageKey);
}
