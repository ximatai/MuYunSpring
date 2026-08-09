import { assert, it } from 'vitest';
import { formatPlatformFileSize } from '@/platform-components/platformFileSize.ts';

it('platform file size uses stable binary units with at most one decimal place', () => {
  assert.equal(formatPlatformFileSize(0).text, '0 B');
  assert.equal(formatPlatformFileSize(1023).text, '1,023 B');
  assert.equal(formatPlatformFileSize(1024).text, '1 KB');
  assert.equal(formatPlatformFileSize(1536).text, '1.5 KB');
  assert.equal(formatPlatformFileSize(18_742_630).text, '17.9 MB');
  assert.equal(formatPlatformFileSize(1024 ** 3).text, '1 GB');
});

it('platform file size preserves exact bytes in tooltip and has stable empty fallback', () => {
  assert.equal(formatPlatformFileSize(18_742_630).title, '18,742,630 bytes');
  assert.deepEqual(formatPlatformFileSize(null), { text: '-', title: '-', valid: false });
  assert.deepEqual(formatPlatformFileSize(-1), { text: '-', title: '-', valid: false });
});
