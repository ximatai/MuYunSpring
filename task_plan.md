# Vue type-check remediation plan

## Goal

Resolve every error reported by `npx vue-tsc --noEmit` while preserving the current frontend contracts.

## Phases

- [completed] Establish baseline and group errors by root cause.
- [completed] Repair shared type contracts and component/composable call sites.
- [completed] Re-run type checking, address residual errors, and verify a clean result.

## Errors encountered

| Error | Attempt | Resolution |
| --- | --- | --- |
| Descriptor arrays initially widened to readonly | 1 | Reverted the public contract change and typed fixture descriptors with `satisfies`, avoiding downstream mutable consumer regressions. |
| Realtime query test still asserted deprecated `keyword` payload | 1 | Updated the HTTP assertion to the supported `quickSearch` query property. |
