const globalThemeVariables = new Map<symbol, Record<`--muyun-${string}`, string>>();
const globalThemeProviderOrder: symbol[] = [];
const originalDocumentVariables = new Map<string, string | undefined>();

export function installGlobalThemeVariables(
  providerId: symbol,
  variables: Record<`--muyun-${string}`, string>,
) {
  if (typeof document === 'undefined') return;
  Object.keys(variables).forEach((name) => {
    if (!originalDocumentVariables.has(name)) {
      originalDocumentVariables.set(name, document.documentElement.style.getPropertyValue(name) || undefined);
    }
  });
  const previousIndex = globalThemeProviderOrder.indexOf(providerId);
  if (previousIndex !== -1) globalThemeProviderOrder.splice(previousIndex, 1);
  globalThemeProviderOrder.push(providerId);
  globalThemeVariables.set(providerId, variables);
  applyGlobalThemeVariables();
}

export function removeGlobalThemeVariables(providerId: symbol) {
  globalThemeVariables.delete(providerId);
  const index = globalThemeProviderOrder.indexOf(providerId);
  if (index !== -1) globalThemeProviderOrder.splice(index, 1);
  applyGlobalThemeVariables();
}

function applyGlobalThemeVariables() {
  if (typeof document === 'undefined') return;
  const latestProviderId = globalThemeProviderOrder.at(-1);
  const latest = latestProviderId === undefined ? undefined : globalThemeVariables.get(latestProviderId);
  if (latest) {
    Object.entries(latest).forEach(([name, value]) =>
      document.documentElement.style.setProperty(name, value),
    );
    return;
  }
  originalDocumentVariables.forEach((value, name) => {
    if (value === undefined) document.documentElement.style.removeProperty(name);
    else document.documentElement.style.setProperty(name, value);
  });
  originalDocumentVariables.clear();
}
