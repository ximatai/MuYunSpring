import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, type Component } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { AppError, platformErrorCodes, createLanguageContext, provideLanguageContext } from '@/web-core';
import EnabledSelect from '@/platform-components/EnabledSelect.vue';
import QueryValueEditor from '@/platform-components/QueryValueEditor.vue';
import QueryCriteriaComposer from '@/platform-components/QueryCriteriaComposer.vue';
import RecordQueryListSurface from '@/platform-components/RecordQueryListSurface.vue';
import DictionaryPicker from '@/platform-components/DictionaryPicker.vue';
import RecordMetaSection from '@/platform-components/RecordMetaSection.vue';
import FileTransferUploader from '@/platform-components/FileTransferUploader.vue';
import { FileTransferUploadError } from '@/platform-components/fileTransferUpload';
import DateTimeText from '@/platform-components/DateTimeText.vue';
import RecordImageFileReferencePreview from '@/platform-components/RecordImageFileReferencePreview.vue';
import UserPicker from '@/platform-components/UserPicker.vue';
import UiModal from '@/vue-ui-antdv/components/UiModal.vue';

function scoped(component: Component, props: Record<string, unknown>) {
  const language = createLanguageContext({ languageTag: 'en' });
  const Host = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () => h(component, props);
    },
  });
  const wrapper = mount(Host, { global: { stubs: { UiDataTable: true } } });
  return { wrapper, language };
}

describe('standard record UI language scope', () => {
  it('changes enabled labels without changing boolean values or emitting a business edit', async () => {
    const { wrapper, language } = scoped(EnabledSelect, { value: false });
    const picker = wrapper.findComponent(EnabledSelect);
    const select = picker.findComponent({ name: 'UiSelect' });
    expect(select.props('options')).toEqual([
      { label: 'Enabled', value: 'true' },
      { label: 'Disabled', value: 'false' },
    ]);
    language.setLanguage('zh-CN');
    await nextTick();
    expect(select.props('options').map((item: { label: string }) => item.label)).toEqual(['启用', '停用']);
    expect(select.props('value')).toBe('false');
    expect(picker.emitted('update:value')).toBeUndefined();
    wrapper.unmount();
  });

  it('refreshes reference prompts without resolving capabilities again or altering selected IDs', async () => {
    const context = {
      runtime: { ready: Promise.resolve() },
      abilities: { tryTree: vi.fn().mockReturnValue(undefined) },
      http: { request: vi.fn().mockResolvedValue([]) },
    };
    const { wrapper, language } = scoped(QueryValueEditor, {
      field: {
        name: 'customerId',
        title: '客户甲',
        valueType: 'STRING',
        operators: ['EQ'],
        reference: { targetModuleAlias: 'crm.customer', cardinality: 'ONE' },
      },
      operator: 'EQ',
      values: ['customer-42'],
      referenceContext: context,
    });
    await flushPromises();
    const editor = wrapper.findComponent(QueryValueEditor);
    const picker = editor.findComponent({ name: 'ReferencePicker' });
    const provider = picker.props('provider');
    expect(picker.props('title')).toBe('Select 客户甲');
    language.setLanguage('zh-CN');
    await nextTick();
    expect(picker.props('title')).toBe('选择客户甲');
    expect(picker.props('provider')).toBe(provider);
    expect(picker.props('value')).toBe('customer-42');
    expect(context.abilities.tryTree).toHaveBeenCalledTimes(1);
    expect(editor.emitted('update:values')).toBeUndefined();
    wrapper.unmount();
  });

  it('keeps query validation reactive and preserves the typed value and business field title', async () => {
    const { wrapper, language } = scoped(QueryCriteriaComposer, {
      fields: [
        {
          name: 'title',
          title: '客户名称',
          valueType: 'STRING',
          operators: ['BETWEEN'],
          defaultOperator: 'BETWEEN',
        },
      ],
      optionItemsByField: {},
      referenceContexts: {},
      disabled: false,
      composition: 'TREE',
    });
    const composer = wrapper.findComponent(QueryCriteriaComposer);
    composer.findComponent(QueryValueEditor).vm.$emit('update:values', ['甲']);
    await nextTick();
    await composer
      .findAll('button')
      .find((button) => button.text() === 'Apply conditions')!
      .trigger('click');
    expect(composer.text()).toContain('Enter both the start and end values for 客户名称');
    expect(composer.emitted('apply')).toBeUndefined();
    language.setLanguage('zh-CN');
    await nextTick();
    expect(composer.text()).toContain('客户名称 需要填写起始和结束两个值');
    expect(composer.findComponent(QueryValueEditor).props('values')).toEqual(['甲']);
    expect(composer.emitted('apply')).toBeUndefined();
    wrapper.unmount();
  });

  it('shows complete English pagination messages and retains caller-owned headings', async () => {
    const { wrapper, language } = scoped(RecordQueryListSurface, {
      columns: [{ key: 'title', title: '原始业务标题' }],
      rows: [],
      title: '客户甲',
      pageable: true,
      total: 42,
      totalKnown: false,
      pageNum: 2,
      pages: 3,
    });
    expect(wrapper.text()).toContain('At least 42 records');
    expect(wrapper.text()).toContain('Page 2 / 3');
    expect(wrapper.text()).toContain('客户甲');
    const surface = wrapper.findComponent(RecordQueryListSurface);
    language.setLanguage('zh-CN');
    await nextTick();
    expect(wrapper.text()).toContain('至少 42 条');
    expect(surface.emitted('pageChange')).toBeUndefined();
    expect(surface.emitted('pageSizeChange')).toBeUndefined();
    wrapper.unmount();
  });

  it('relabels unresolved dictionary input without choosing a code or discarding typed text', async () => {
    const { wrapper, language } = scoped(DictionaryPicker, {
      items: [{ code: 'stable', title: '正式业务名称' }],
      value: 'stable',
      mode: 'dropdown',
    });
    const picker = wrapper.findComponent(DictionaryPicker);
    const select = picker.findComponent({ name: 'UiSelect' });
    select.vm.$emit('update:searchValue', '找不到');
    await picker.trigger('focusout', { relatedTarget: null });
    await nextTick();
    expect(picker.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({
      status: 'unmatched',
      message: 'No matching dictionary item found',
    });
    language.setLanguage('zh-CN');
    await nextTick();
    expect(picker.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({
      status: 'unmatched',
      message: '未找到匹配的字典项',
    });
    expect(select.props('searchValue')).toBe('找不到');
    expect(picker.emitted('update:value')).toBeUndefined();
    wrapper.unmount();
  });

  it('relabels an upload failure without retrying a transfer or replacing the original file', async () => {
    const uploadFile = vi.fn().mockRejectedValue(new FileTransferUploadError('network'));
    const { wrapper, language } = scoped(FileTransferUploader, { uploadFile });
    const uploader = wrapper.findComponent(FileTransferUploader);
    const file = new File(['content'], '业务附件.pdf', { type: 'application/pdf' });
    const input = uploader.get('input[type="file"]');
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] });
    await input.trigger('change');
    await flushPromises();
    expect(uploader.text()).toContain('Could not connect to the file service.');
    expect(uploader.text()).toContain('业务附件.pdf');
    language.setLanguage('zh-CN');
    await nextTick();
    expect(uploader.text()).toContain('文件服务网络连接失败。');
    expect(uploadFile).toHaveBeenCalledExactlyOnceWith(file);
    expect(uploader.emitted('completed')).toBeUndefined();
    wrapper.unmount();
  });

  it('localizes timestamp tooltips while preserving the UTC instant and display time zone', async () => {
    const { wrapper, language } = scoped(DateTimeText, {
      value: '2026-10-14T01:00:00Z',
      timeZone: 'Asia/Shanghai',
    });
    expect(wrapper.get('time').attributes('datetime')).toBe('2026-10-14T01:00:00.000Z');
    expect(wrapper.get('time').attributes('title')).toContain('Time zone: Asia/Shanghai');
    const value = wrapper.get('time').text();
    language.setLanguage('zh-CN');
    await nextTick();
    expect(wrapper.get('time').attributes('title')).toContain('时区: Asia/Shanghai');
    expect(wrapper.get('time').text()).toBe(value);
    wrapper.unmount();
  });

  it('renders a preview failure as translated text without exposing function source or requesting again', async () => {
    const request = vi
      .fn()
      .mockRejectedValue(
        new AppError('private transport details', { code: platformErrorCodes.networkError }),
      );
    const { wrapper, language } = scoped(RecordImageFileReferencePreview, {
      value: 'file-1',
      record: { id: 'record-1' },
      context: { http: { request }, moduleAlias: 'demo.record' },
      definition: { fieldRef: { fieldName: 'image' }, readAvailable: true },
    });
    await flushPromises();
    expect(wrapper.text()).toBe(language.t('platform.error.NETWORK_ERROR'));
    expect(wrapper.text()).not.toContain('=>');
    expect(wrapper.text()).not.toContain('private transport');
    language.setLanguage('zh-CN');
    await nextTick();
    expect(wrapper.text()).toBe(language.t('platform.error.NETWORK_ERROR'));
    expect(request).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('relabels cached user-picker columns and browse axes without changing source-owned candidates or selections', async () => {
    const navigation = {
      showTenantNavigation: true,
      tenants: [{ id: 'tenant-a', title: '租户甲' }],
      organizations: [{ id: 'org-a', title: '机构甲', tenantId: 'tenant-a' }],
      departments: [{ id: 'department-a', title: '部门甲', tenantId: 'tenant-a', organizationId: 'org-a' }],
    };
    const records = [
      {
        id: 'user-a',
        title: '职员甲',
        account: 'login-a',
        employeeName: '职员甲',
        organizationName: '机构甲',
        departmentName: '部门甲',
      },
    ];
    const searchPage = vi.fn().mockResolvedValue({ records, total: 1, navigation });
    const { wrapper, language } = scoped(UserPicker, {
      value: 'user-a',
      searchPage,
      resolveUsers: vi.fn().mockResolvedValue(records),
    });
    const user = wrapper.findComponent(UserPicker);
    await wrapper.get('[aria-label="Search and select"]').trigger('click');
    await flushPromises();
    const reference = user.findComponent({ name: 'ReferencePicker' });
    const table = reference.findComponent({ name: 'UiDataTable' });
    expect(table.props('columns').map((column: { title: string }) => column.title)).toEqual([
      'User account',
      'Employee name',
      'Organization',
      'Department',
    ]);
    const axes = reference.findAllComponents({ name: 'RecordExplorerPanel' });
    expect(axes.map((axis) => axis.props('title'))).toEqual(['Tenant', 'Organization', 'Department']);
    expect(axes[0]!.findComponent({ name: 'UiTree' }).props('nodes')[0].title).toBe('租户甲');
    const count = searchPage.mock.calls.length;
    language.setLanguage('zh-CN');
    await nextTick();
    expect(table.props('columns').map((column: { title: string }) => column.title)).toEqual([
      '用户账号',
      '职员姓名',
      '所属机构',
      '所属部门',
    ]);
    expect(axes.map((axis) => axis.props('title'))).toEqual(['租户', '机构', '部门']);
    expect(searchPage).toHaveBeenCalledTimes(count);
    expect(user.emitted('update:value')).toBeUndefined();
    expect(navigation.tenants[0]).toEqual({ id: 'tenant-a', title: '租户甲' });
    wrapper.unmount();
  });

  it('uses English metadata headings while keeping record timestamps and versions', async () => {
    const { wrapper, language } = scoped(RecordMetaSection, {
      record: { version: 7, createdAt: '2026-10-14T01:00:00Z' },
    });
    expect(wrapper.text()).toContain('System information');
    expect(wrapper.text()).toContain('Version');
    expect(wrapper.text()).toContain('7');
    language.setLanguage('zh-CN');
    await nextTick();
    expect(wrapper.text()).toContain('系统信息');
    expect(wrapper.text()).toContain('7');
    wrapper.unmount();
  });

  it('localizes modal defaults in the consumer scope and keeps a custom action label', async () => {
    const { wrapper, language } = scoped(UiModal, {
      open: true,
      title: '业务标题',
      confirmText: '批准原业务动作',
    });
    const modal = wrapper.findComponent({ name: 'AModal' });
    expect(modal.props('okText')).toBe('批准原业务动作');
    expect(modal.props('cancelText')).toBe('Cancel');
    language.setLanguage('zh-CN');
    await nextTick();
    expect(modal.props('cancelText')).toBe('取消');
    expect(modal.props('okText')).toBe('批准原业务动作');
    wrapper.unmount();
  });
});
