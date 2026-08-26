import { describe, expect, it } from 'vitest';
import {
  NAVIGATOR_ENTRY_MODULE_ALIAS_QUERY_KEY,
  NAVIGATOR_ENTRY_RECORD_ID_QUERY_KEY,
  navigatorEntrySelectionOf,
} from '@/dynamic-page-runtime/navigatorEntrySelection';

describe('navigatorEntrySelectionOf', () => {
  it('accepts the explicit navigator module alias and record id pair', () => {
    expect(
      navigatorEntrySelectionOf({
        [NAVIGATOR_ENTRY_MODULE_ALIAS_QUERY_KEY]: 'platform.module',
        [NAVIGATOR_ENTRY_RECORD_ID_QUERY_KEY]: 'platform.application',
      }),
    ).toEqual({
      moduleAlias: 'platform.module',
      recordId: 'platform.application',
      identity: 'platform.module\u0000platform.application',
    });
  });

  it('rejects incomplete, repeated, and blank values instead of guessing a navigator scope', () => {
    expect(
      navigatorEntrySelectionOf({ [NAVIGATOR_ENTRY_MODULE_ALIAS_QUERY_KEY]: 'platform.module' }),
    ).toBeUndefined();
    expect(
      navigatorEntrySelectionOf({
        [NAVIGATOR_ENTRY_MODULE_ALIAS_QUERY_KEY]: ['platform.module', 'iam.tenant'],
        [NAVIGATOR_ENTRY_RECORD_ID_QUERY_KEY]: 'platform.application',
      }),
    ).toBeUndefined();
    expect(
      navigatorEntrySelectionOf({
        [NAVIGATOR_ENTRY_MODULE_ALIAS_QUERY_KEY]: ' ',
        [NAVIGATOR_ENTRY_RECORD_ID_QUERY_KEY]: 'platform.application',
      }),
    ).toBeUndefined();
  });
});
