const providers = new Map<symbol, { tag: string; direction: 'ltr' | 'rtl' }>();
let original: { tag: string; direction: string | null } | undefined;

/** Matches theme scope ownership: the latest provider wins and the original document is restored last. */
export function installGlobalLanguage(id: symbol, tag: string, direction: 'ltr' | 'rtl') {
  if (typeof document === 'undefined') return;
  original ??= {
    tag: document.documentElement.lang,
    direction: document.documentElement.getAttribute('dir'),
  };
  providers.delete(id);
  providers.set(id, { tag, direction });
  apply();
}
export function removeGlobalLanguage(id: symbol) {
  providers.delete(id);
  apply();
}
function apply() {
  if (typeof document === 'undefined' || !original) return;
  const current = [...providers.values()].at(-1);
  document.documentElement.lang = current?.tag ?? original.tag;
  const direction = current?.direction ?? original.direction;
  if (direction == null) document.documentElement.removeAttribute('dir');
  else document.documentElement.dir = direction;
  if (!providers.size) original = undefined;
}
