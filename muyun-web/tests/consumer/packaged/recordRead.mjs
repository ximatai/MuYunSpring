// Copied into the installed consumer so native Node resolves only its public package exports.
import { effectScope } from 'vue';
import { resolveRecordReadEntry, useRecordReadSession } from '@ximatai/muyun-web-app/record-read';

const scope = effectScope();
let requestSignal;
let recordId;
try {
  const session = scope.run(() =>
    useRecordReadSession({
      view: {
        sourceId: 'sample.records',
        viewCode: 'current',
        scopeKey: 'user-a',
        descriptor: { page: { list: { fields: { fields: [] } } } },
        recordKey: (row) => row.id,
        query: async (_query, { signal }) => {
          requestSignal = signal;
          return {
            records: [{ id: 'record-1' }],
            total: 1,
            totalKnown: true,
            pages: 1,
            pageNum: 1,
            pageSize: 20,
          };
        },
      },
    }),
  );
  await session.refresh();
  recordId = session.result.value.records[0].id;
} finally {
  scope.stop();
}
console.log(
  JSON.stringify({
    recordId,
    requestAborted: requestSignal.aborted,
    browserGlobalsAbsent: typeof window === 'undefined' && typeof document === 'undefined',
    entryView: resolveRecordReadEntry({
      moduleAlias: 'sample.records',
      viewCodes: ['current'],
      directViewCode: 'current',
    }).viewCode,
  }),
);
