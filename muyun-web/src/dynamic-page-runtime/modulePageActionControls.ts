import type { QueryListRecord, RecordActionItem } from '@muyun/platform-components';
import {
  createReadonlyCardRecordSnapshot,
  type ModulePageActionControlContribution,
  type ModulePageActionStateContext,
} from './modulePageEnhancements';

/** Shares one control binding path across cards, drawers and workspace details. */
export function resolveModulePageActionControls(options: {
  controls: readonly ModulePageActionControlContribution[];
  record?: QueryListRecord;
  stateContext: ModulePageActionStateContext;
  sessionKey: string | number;
  active: boolean;
  busy: boolean;
  refresh(): void;
}): RecordActionItem[] {
  if (!options.active || !options.record || options.controls.length === 0) return [];
  const record = createReadonlyCardRecordSnapshot(options.record);
  return options.controls.map(({ component, state, ...action }) => {
    const resolved = state?.(record, options.stateContext);
    return {
      ...action,
      ...resolved,
      disabled: options.busy || action.disabled === true || resolved?.disabled === true,
      control: {
        component,
        moduleAlias: options.stateContext.module.moduleAlias,
        record,
        sessionKey: options.sessionKey,
        scopeKey: options.stateContext.module,
        active: options.active,
        refresh: options.refresh,
      },
    };
  });
}
