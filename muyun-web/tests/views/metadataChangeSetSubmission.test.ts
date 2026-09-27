import { expect, it, vi } from 'vitest';
import { prepareMetadataChangeSetSubmission } from '@/views/metadataChangeSetSubmission';
import type { MetadataModelChangeSetProposal } from '@/views/metadataModelEditSession';
import type { HttpClient, HttpRequestOptions } from '@/web-core';

function fixture() {
  const proposal: MetadataModelChangeSetProposal = {
    relationDrafts: [
      {
        relationId: 'main',
        expectedMetadataVersion: 3,
        fieldDrafts: [
          {
            operation: 'ADD',
            field: { fieldName: 'note', title: '备注', fieldSpecAlias: 'string', required: false },
          },
        ],
      },
    ],
    relationOrders: [],
    fieldOrders: [],
  };
  const preview = {
    proposalFingerprint: 'checked',
    errors: [],
    warnings: [{ message: '需要刷新生效' }],
    fieldImpacts: [{ fieldName: 'note', description: '新增字段' }],
    schemaImpacts: [],
    orderImpacts: [],
  };
  const request = vi.fn<(options: HttpRequestOptions) => Promise<typeof preview>>(async () => preview);
  return { proposal, preview, request, http: { request } as unknown as HttpClient };
}

it('captures the reviewed payload and fingerprint, with one submission even after a transport failure', async () => {
  const { http, request, proposal } = fixture();
  const submission = await prepareMetadataChangeSetSubmission(http, 'demo.order', proposal, () => true);
  expect(submission.lines.join('\n')).toContain('新增「备注」');
  expect(submission.lines.join('\n')).toContain('可以不填');
  expect(submission.details.join('\n')).toContain('备注（note）');
  expect(submission.lines.join('\n')).toContain('注意：需要刷新生效');
  proposal.relationDrafts[0].fieldDrafts[0].field!.title = 'later mutation';
  request.mockRejectedValueOnce(new Error('connection lost'));
  await expect(submission.apply()).rejects.toThrow('connection lost');
  await expect(submission.apply()).rejects.toThrow('不要重复保存');
  expect(request).toHaveBeenCalledTimes(2);
  expect(request.mock.calls[1]).toMatchObject([
    {
      body: {
        proposalFingerprint: 'checked',
        proposal: { relationDrafts: [{ fieldDrafts: [{ field: { title: '备注' } }] }] },
      },
    },
  ]);
});

it('rejects changes while preview is pending and between preview and apply', async () => {
  const { http, request, proposal, preview } = fixture();
  let current = true;
  request.mockImplementationOnce(async () => {
    current = false;
    return preview;
  });
  await expect(
    prepareMetadataChangeSetSubmission(http, 'demo.order', proposal, () => current),
  ).rejects.toThrow('候选或基线已变化');
  current = true;
  const submission = await prepareMetadataChangeSetSubmission(http, 'demo.order', proposal, () => current);
  current = false;
  await expect(submission.apply()).rejects.toThrow('候选或基线已变化');
  expect(request).toHaveBeenCalledTimes(2);
});

it.each(['errors', 'fingerprint'])('never prepares a submission with invalid %s', async (kind) => {
  const { http, request, proposal, preview } = fixture();
  request.mockResolvedValueOnce({
    ...preview,
    ...(kind === 'errors' ? { errors: [{ message: '字段不合法' }] } : { proposalFingerprint: '' }),
  } as typeof preview);
  await expect(prepareMetadataChangeSetSubmission(http, 'demo.order', proposal, () => true)).rejects.toThrow(
    kind === 'errors' ? '字段不合法' : '候选指纹',
  );
  expect(request).toHaveBeenCalledOnce();
});
