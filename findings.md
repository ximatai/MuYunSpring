# Findings

- `vue-tsc` baseline contained 32 errors across nine test files and one page descriptor guard.
- The primary cause was test doubles falling behind the mandatory module runtime/query-schema contract.
- UI descriptor tests use immutable fixtures; `satisfies ResolvedModuleUiDescriptor` validates them without changing runtime-facing descriptor mutability.
- STOMP test adapters now use the real `IMessage` callback shape.
