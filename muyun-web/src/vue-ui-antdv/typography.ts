/** Platform text roles are shared by CSS consumers and the UI adapter, independent of skin. */
export const uiTypography = {
  body: { fontSize: 13, lineHeight: 21 },
  helper: { fontSize: 12, lineHeight: 18 },
  section: { fontSize: 14, lineHeight: 20 },
  panel: { fontSize: 16, lineHeight: 22 },
  page: { fontSize: 18, lineHeight: 26 },
  fontFamily: 'ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
} as const;

export function typographyCssVariables(): Record<`--muyun-${string}`, string> {
  const variables: Record<`--muyun-${string}`, string> = {
    '--muyun-font-family': uiTypography.fontFamily,
  };
  for (const role of ['body', 'helper', 'section', 'panel', 'page'] as const) {
    variables[`--muyun-font-size-${role}`] = `${uiTypography[role].fontSize}px`;
    variables[`--muyun-line-height-${role}`] = `${uiTypography[role].lineHeight}px`;
  }
  return variables;
}
