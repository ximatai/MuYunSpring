import { assert, it } from 'vitest';
import {
  canSwitchEmployeeDetailContext,
  isEmployeeFormDisabled,
  shouldCommitEmployeeDetailRequest,
  shouldCloseEmployeeDetailOnCancel,
  shouldShowEmployeeDetailContent,
  validateEmployeeRequiredFormFields,
} from '@/views/employeeDetailStateModel.ts';

it('employee detail form stays disabled until edit detail record is loaded', () => {
  assert.equal(
    isEmployeeFormDisabled({
      mode: 'edit',
      loadingDetail: false,
      saving: false,
      selectedEmployeeId: undefined,
    }),
    true,
  );
  assert.equal(
    isEmployeeFormDisabled({
      mode: 'edit',
      loadingDetail: false,
      saving: false,
      selectedEmployeeId: 'emp-1',
    }),
    false,
  );
});

it('employee detail request commits only for latest selected record', () => {
  assert.equal(
    shouldCommitEmployeeDetailRequest({
      activeRequestSeq: 2,
      requestSeq: 1,
      selectedEmployeeKey: 'emp-1',
      recordId: 'emp-1',
    }),
    false,
  );
  assert.equal(
    shouldCommitEmployeeDetailRequest({
      activeRequestSeq: 2,
      requestSeq: 2,
      selectedEmployeeKey: 'emp-2',
      recordId: 'emp-1',
    }),
    false,
  );
  assert.equal(
    shouldCommitEmployeeDetailRequest({
      activeRequestSeq: 2,
      requestSeq: 2,
      selectedEmployeeKey: 'emp-2',
      recordId: 'emp-2',
    }),
    true,
  );
});

it('employee detail content hides temporary records while loading or failed', () => {
  assert.equal(
    shouldShowEmployeeDetailContent({ mode: 'create', loadingDetail: false, loadFailed: true }),
    true,
  );
  assert.equal(
    shouldShowEmployeeDetailContent({
      mode: 'view',
      loadingDetail: true,
      loadFailed: false,
      selectedEmployeeId: 'emp-1',
    }),
    false,
  );
  assert.equal(
    shouldShowEmployeeDetailContent({
      mode: 'edit',
      loadingDetail: false,
      loadFailed: true,
      selectedEmployeeId: 'emp-1',
    }),
    false,
  );
  assert.equal(
    shouldShowEmployeeDetailContent({ mode: 'view', loadingDetail: false, loadFailed: false }),
    false,
  );
  assert.equal(
    shouldShowEmployeeDetailContent({
      mode: 'view',
      loadingDetail: false,
      loadFailed: false,
      selectedEmployeeId: 'emp-1',
    }),
    true,
  );
});

it('employee detail context cannot switch while saving', () => {
  assert.equal(canSwitchEmployeeDetailContext({ saving: true }), false);
  assert.equal(canSwitchEmployeeDetailContext({ saving: false }), true);
});

it('employee detail cancel closes create but restores loaded edit detail', () => {
  assert.equal(
    shouldCloseEmployeeDetailOnCancel({
      mode: 'create',
      selectedEmployeeId: undefined,
    }),
    true,
  );
  assert.equal(
    shouldCloseEmployeeDetailOnCancel({
      mode: 'edit',
      selectedEmployeeId: undefined,
    }),
    true,
  );
  assert.equal(
    shouldCloseEmployeeDetailOnCancel({
      mode: 'edit',
      selectedEmployeeId: 'emp-1',
    }),
    false,
  );
});

it('employee required form fields use visible required labels for validation', () => {
  assert.equal(
    validateEmployeeRequiredFormFields([
      { fieldName: 'departmentId', label: '所属部门', required: true, visible: true, value: undefined },
      { fieldName: 'employeeNo', label: '职员编号', required: true, visible: true, value: '  ' },
      { fieldName: 'title', label: '职员姓名', required: true, visible: true, value: '张三' },
      { fieldName: 'mobile', label: '手机号', required: true, visible: false, value: undefined },
      { fieldName: 'email', label: '邮箱', required: false, visible: true, value: undefined },
    ]),
    '请填写所属部门、职员编号',
  );
  assert.equal(
    validateEmployeeRequiredFormFields([
      { fieldName: 'departmentId', label: '所属部门', required: true, visible: true, value: 'dept-1' },
      { fieldName: 'employeeNo', label: '职员编号', required: true, visible: true, value: 'E001' },
      { fieldName: 'title', label: '职员姓名', required: true, visible: true, value: '张三' },
    ]),
    undefined,
  );
});
