import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import ConstructionPlanCard from '@/platform-workbench/ConstructionPlanCard.vue';
import WorkbenchAssistantPanel from '@/platform-workbench/WorkbenchAssistantPanel.vue';
import { createConstructionPlanSession } from '@/platform-workbench/constructionPlanSession';
import { AppError, createAssistantSurfaceRegistry, type ConstructionPlanClient } from '@muyun/web-core';
import type { ConstructionPlanContent } from '@muyun/web-contracts';
const content: ConstructionPlanContent = {
  title: '订单管理',
  goal: '记录订单',
  inScope: ['录入'],
  outOfScope: ['结算'],
  objects: [],
  relationships: [],
  rules: [],
  questions: [],
  assumptions: [],
  decisions: [],
  acceptanceExamples: ['新增后可以查询'],
};
function fixture() {
  const client: ConstructionPlanClient = {
    task: vi.fn(),
    previewAcceptance: vi.fn(),
    confirmAcceptance: vi.fn(),
    acceptance: vi.fn(),
    previewDelivery: vi.fn(),
    publishDelivery: vi.fn(),
    delivery: vi.fn(),
    progress: vi.fn(),
    designContract: vi.fn(async () => ({
      recordName: { fieldName: 'title', columnName: 'title', fieldType: 'STRING' },
      inheritedFields: ['id'],
      declarableCapabilities: { capabilities: [], metadataFields: [] },
    })),
    businessObjects: vi.fn(async () => [
      { alias: 'crm.customer', title: '客户', kind: 'DYNAMIC', referenceReady: true, explanation: '可复用' },
    ]),
    referenceTarget: vi.fn(async () => ({
      targetModuleAlias: 'crm.customer',
      targetMetadataId: 'customer-metadata',
      keyFields: [{ fieldName: 'id', title: '标识', defaultField: true, selectable: true }],
      labelFields: [{ fieldName: 'name', title: '客户名称', defaultField: true, selectable: true }],
    })),
    describeFields: vi.fn(),
    previewFields: vi.fn(),
    publishFields: vi.fn(),
    fieldChange: vi.fn(),
    initialization: vi.fn(),
    list: vi.fn(async () => []),
    history: vi.fn(async () => []),
    read: vi.fn(),
    confirmation: vi.fn(),
    confirm: vi.fn(async (planId, command) => ({
      planId,
      revision: command.expectedRevision + 1,
      content: command.content,
      confirmedAt: '',
      constructionStatus: 'NOT_STARTED' as const,
      deliveredObjectKeys: [],
      deliveries: [],
      fieldChanges: [],
      initializations: [],
    })),
  };
  const session = createConstructionPlanSession(client, () => 'user');
  return { client, session };
}
it.each([false, true])(
  'confirms requirements in chat, recovering a rejected submission: %s',
  async (rejectFirst) => {
    const { client, session } = fixture();
    if (rejectFirst)
      vi.mocked(client.confirm).mockRejectedValueOnce(new AppError('请求资源不存在', { status: 404 }));
    const registry = createAssistantSurfaceRegistry(
      () => 'user',
      () => ({
        revision: String(session.current().generation),
        facts: { constructionPlan: session.facts() },
      }),
    );
    const requestTurn = vi
      .fn()
      .mockResolvedValueOnce({
        toolCalls: [{ id: 'propose', code: 'construction.propose', input: { generation: 0, content } }],
      })
      .mockResolvedValue({
        toolCalls: [{ id: 'confirm', code: 'construction.prepare-confirmation', input: {} }],
      });
    registry.register({
      pageInstanceKey: 'home',
      contextRevision: () => '0',
      surface: {
        describe: () => ({ surface: 'workbench', facts: {} }),
        capabilities: session.capabilities,
        requestTurn,
      },
    });
    registry.activate('home');
    const wrapper = mount(WorkbenchAssistantPanel, {
      props: { open: true, registry, constructionPlan: session },
    });
    await wrapper.get('.assistant-panel__composer textarea').setValue('确认订单管理本期范围');
    await wrapper.get('.assistant-panel__composer button.ant-btn-primary').trigger('click');
    await flushPromises();
    expect(client.confirm).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('本期范围：录入');
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '确认本期方案')!
      .trigger('click');
    await flushPromises();
    if (rejectFirst) {
      expect(wrapper.get('[role="alert"]').text()).toContain('请求资源不存在');
      expect(wrapper.text()).toContain('操作未提交，已准备的内容保留');
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '重试本次确认')!
        .trigger('click');
      await flushPromises();
      expect(vi.mocked(client.confirm).mock.calls[1]).toEqual(vi.mocked(client.confirm).mock.calls[0]);
    }
    expect(client.confirm).toHaveBeenCalledTimes(rejectFirst ? 2 : 1);
    expect(requestTurn).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain('需求方案已确认');
    expect(wrapper.text()).toContain('尚无建设记录');
    wrapper.unmount();
  },
);
it('allows human scope edits without persisting and invalidates a previous confirmation', async () => {
  const { client, session } = fixture();
  session.edit(content);
  const proposal = session.prepare();
  const wrapper = mount(ConstructionPlanCard, { props: { session } });
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '修改目标与范围')!
    .trigger('click');
  expect(proposal.isCurrent()).toBe(false);
  expect(() => session.prepare()).toThrow('人工修改');
  await wrapper.get('input').setValue('客户订单');
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '更新候选，稍后确认')!
    .trigger('click');
  await flushPromises();
  expect(session.current().candidate?.title).toBe('客户订单');
  expect(proposal.isCurrent()).toBe(false);
  expect(client.confirm).not.toHaveBeenCalled();
  expect(wrapper.text()).toContain('有未确认修改');
  wrapper.unmount();
});

it('shows independent planning choices without treating their display order as execution', async () => {
  const { client, session } = fixture();
  session.edit(content);
  await session.prepare().execute();
  vi.mocked(client.task).mockResolvedValue({
    planRevision: 1,
    objects: [
      {
        objectKey: 'contract',
        title: '合同',
        complete: false,
        options: [
          { action: 'CONFIGURE_FIELDS', explanation: '需要时补充登记内容' },
          { action: 'PUBLISH_PAGE', explanation: '已有内容满足要求，可以准备页面' },
        ],
        requirements: [],
      },
    ],
  });
  await session.readTask();
  const wrapper = mount(ConstructionPlanCard, { props: { session } });
  expect(wrapper.text()).toContain('需要时补充登记内容');
  expect(wrapper.text()).toContain('已有内容满足要求，可以准备页面');
  expect(client.publishFields).not.toHaveBeenCalled();
  expect(client.publishDelivery).not.toHaveBeenCalled();
  wrapper.unmount();
});

it('shows delivered design as read-only history without switching or rebuilding controls', async () => {
  const { session, client } = fixture();
  session.edit(content);
  await session.prepare().execute();
  const snapshot = {
    ...session.current().saved!,
    constructionStatus: 'DELIVERED' as const,
    deliveredObjectKeys: ['order'],
  };
  vi.mocked(client.read).mockResolvedValue(snapshot);
  await session.restore(snapshot.planId);
  const wrapper = mount(ConstructionPlanCard, { props: { session } });
  expect(wrapper.text()).toContain('历史建设记录');
  expect(wrapper.text()).toContain('不代表当前配置');
  expect(wrapper.text()).toContain('查看当时的设计');
  for (const text of ['修改目标与范围', '恢复讨论', '读取已保存方案', '查看下一步', '放弃候选并开始新方案'])
    expect(wrapper.findAll('button').some((button) => button.text() === text)).toBe(false);
  wrapper.unmount();
});

it('loads current progress on restore without asking the user to query or writing configuration', async () => {
  const { client, session } = fixture();
  session.edit(content);
  await session.prepare().execute();
  vi.mocked(client.task).mockResolvedValue({
    planRevision: 1,
    objects: [
      {
        objectKey: 'customer',
        title: '客户',
        complete: false,
        requirements: [],
        options: [{ action: 'PUBLISH_PAGE', explanation: '准备录入页面' }],
        progress: {
          objectKey: 'customer',
          moduleAlias: 'trial.customer',
          runtimeStatus: 'ACTIVE',
          pagePublished: false,
          entryVisible: false,
          menuId: null,
          needsReview: false,
          acceptanceConfirmed: false,
          businessDataStatus: 'NOT_QUERIED',
          remainingWork: [],
          receipts: [],
          requirements: [],
        },
      },
    ],
  });
  const wrapper = mount(ConstructionPlanCard, { props: { session } });
  await flushPromises();
  expect(wrapper.get('[aria-label="当前建设进度"]').text()).toContain('客户：登记内容已配置，页面尚未发布');
  expect(client.publishDelivery).not.toHaveBeenCalled();
  session.resetConversation();
  await flushPromises();
  expect(wrapper.find('[aria-label="当前建设进度"]').exists()).toBe(false);
  wrapper.unmount();
});

it('does not infer missing configuration from absent construction field and page receipts', async () => {
  const { client, session } = fixture();
  session.edit(content);
  await session.prepare().execute();
  const snapshot = {
    ...session.current().saved!,
    constructionStatus: 'INITIALIZED' as const,
    initializations: [
      {
        objectKey: 'order',
        planRevision: 1,
        moduleAlias: 'demo.order',
        metadataId: 'meta',
        relationId: 'main',
        requestId: 'init',
      },
    ],
  };
  vi.mocked(client.read).mockResolvedValue(snapshot);
  await session.restore(snapshot.planId);
  const wrapper = mount(ConstructionPlanCard, { props: { session } });
  expect(wrapper.text()).toContain('当前配置与验收状态以实际查询为准');
  expect(wrapper.text()).not.toContain('仍待建设');
  wrapper.unmount();
});
