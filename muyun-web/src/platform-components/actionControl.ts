import type { Component } from 'vue';

/** A frontend-owned control, mounted once in the platform action bar. */
export interface ActionControl {
  component: Component;
  moduleAlias: string;
  record: Readonly<Record<string, unknown>>;
  sessionKey: string | number;
  /** Identity of the owning page scope, including its tenant boundary. */
  scopeKey: object;
  active: boolean;
  refresh(): void;
}

/** The component receives this read-only value as its `context` prop. */
export interface ActionControlContext {
  actionKey: string;
  title: string;
  moduleAlias: string;
  record: Readonly<Record<string, unknown>>;
  sessionKey: string | number;
  active: boolean;
  disabled: boolean;
  disabledReason?: string;
  loading: boolean;
  /** Aborted on inactivity, disablement, record/session changes and unmount. */
  signal: AbortSignal;
  /** Refreshes record data, action availability and its list while this session is usable. */
  refresh(): void;
}
