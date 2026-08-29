import { assert, it } from 'vitest';
import {
  createUiOrchestrationState,
  emptyUiConfigFieldDraft,
  emptyUiSetDraft,
  isValidUiConfigFieldDraft,
  isValidUiSetDraft,
  normalizeUiConfigFieldDraft,
  normalizeUiSetDraft,
  pageExecutionStatusOf,
} from '@/views/uiOrchestrationState.ts';

it('UI set draft trims stable identity fields and requires set facts', () => {
  assert.equal(isValidUiSetDraft(emptyUiSetDraft()), false);
  const draft = normalizeUiSetDraft({ ...emptyUiSetDraft(), title: ' 客户列表 ', alias: ' customer_list ' });
  assert.deepEqual(draft, {
    title: '客户列表',
    alias: 'customer_list',
    setType: 'LIST',
    defaultSet: false,
    enabled: true,
  });
  assert.equal(isValidUiSetDraft(draft), true);
});

it('UI config field retains the nested module-field identity and normalizes its display span', () => {
  assert.equal(isValidUiConfigFieldDraft(emptyUiConfigFieldDraft()), false);
  const draft = normalizeUiConfigFieldDraft({
    ...emptyUiConfigFieldDraft(),
    moduleMetadataFieldId: 'module-field-customer-name',
    fieldUiControlAlias: ' text ',
    columnSpan: 0,
  });
  assert.equal(draft.moduleMetadataFieldId, 'module-field-customer-name');
  assert.equal(draft.fieldUiControlAlias, 'text');
  assert.equal(draft.columnSpan, 1);
  assert.equal(isValidUiConfigFieldDraft(draft), true);
});

it('published UI configuration blocks every field and config editing entry while allowing unpublish flow', () => {
  const state = createUiOrchestrationState();
  state.handleUiSetsLoaded([{ id: 'set-list', title: '客户列表', alias: 'customer_list', setType: 'LIST' }]);
  state.handleConfigsLoaded([{ id: 'web-list', uiSetId: 'set-list', clientType: 'WEB', published: true }]);

  state.startEditConfig();
  state.startCreateField();
  state.startEditField({
    id: 'field-name',
    uiConfigId: 'web-list',
    moduleMetadataFieldId: 'module-field-name',
  });

  assert.equal(state.configPublished.value, true);
  assert.equal(state.mode.value, 'view');
});

it('switching UI sets clears nested configuration and field selections before their scoped data reloads', () => {
  const state = createUiOrchestrationState();
  state.handleUiSetsLoaded([{ id: 'set-list' }, { id: 'set-form' }]);
  state.handleConfigsLoaded([{ id: 'web-list', uiSetId: 'set-list' }]);
  state.handleFieldsLoaded([{ id: 'field-name', uiConfigId: 'web-list' }]);

  assert.equal(state.selectUiSet({ id: 'set-form' }), true);
  assert.equal(state.selectedUiSetId.value, 'set-form');
  assert.equal(state.selectedUiConfigId.value, undefined);
  assert.deepEqual(state.configs.value, []);
  assert.deepEqual(state.fields.value, []);
});

it('requires published enabled WEB list and form configurations before a module is executable', () => {
  const uiSets = [
    { id: 'set-list', setType: 'LIST' as const, enabled: true },
    { id: 'set-form', setType: 'FORM' as const, enabled: true },
  ];
  assert.equal(
    pageExecutionStatusOf(uiSets, [
      { uiSetId: 'set-list', clientType: 'WEB', enabled: true, published: true },
    ]),
    '已发布但未可执行：缺少Web 表单配置',
  );
  assert.equal(
    pageExecutionStatusOf(uiSets, [
      { uiSetId: 'set-list', clientType: 'WEB', enabled: true, published: true },
      { uiSetId: 'set-form', clientType: 'WEB', enabled: true, published: true },
    ]),
    '可执行：已具备已发布的 Web 列表与表单配置',
  );
});
