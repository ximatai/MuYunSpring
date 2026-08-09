import { assert, it } from 'vitest';
import { formatPlatformDateTime } from '@/platform-components/platformDateTime.ts';
import type { CurrentUser } from '@/web-contracts/index.ts';

it('platform datetime formats instants in the requested IANA time zone', () => {
  const display = formatPlatformDateTime('2026-07-16T07:13:07.898+00:00', {
    timeZone: 'Asia/Shanghai',
  });

  assert.equal(display.text, '2026-07-16 15:13:07');
  assert.equal(display.datetime, '2026-07-16T07:13:07.898Z');
  assert.match(display.title, /UTC: 2026-07-16T07:13:07\.898Z/);
  assert.match(display.title, /时区: Asia\/Shanghai/);
});

it('platform datetime keeps date boundaries tied to display time zone', () => {
  const display = formatPlatformDateTime('2026-07-16T23:30:00.000Z', {
    timeZone: 'America/New_York',
  });

  assert.equal(display.text, '2026-07-16 19:30:00');
});

it('platform datetime falls back to browser time zone when no user time zone is provided', () => {
  const originalDateTimeFormat = Intl.DateTimeFormat;
  Object.defineProperty(Intl, 'DateTimeFormat', {
    configurable: true,
    value: function DateTimeFormat(
      locale: string | string[] | undefined,
      options?: Intl.DateTimeFormatOptions,
    ) {
      if (options === undefined) {
        return {
          resolvedOptions: () => ({ timeZone: 'Asia/Tokyo' }),
        };
      }
      return new originalDateTimeFormat(locale, options);
    },
  });

  try {
    const display = formatPlatformDateTime('2026-07-16T07:13:07.898+00:00');

    assert.equal(display.text, '2026-07-16 16:13:07');
    assert.equal(display.timeZone, 'Asia/Tokyo');
    assert.match(display.title, /时区: Asia\/Tokyo/);
  } finally {
    Object.defineProperty(Intl, 'DateTimeFormat', {
      configurable: true,
      value: originalDateTimeFormat,
    });
  }
});

it('platform datetime has stable empty and invalid fallbacks', () => {
  assert.deepEqual(formatPlatformDateTime(undefined), {
    text: '-',
    title: '-',
    valid: false,
  });

  assert.deepEqual(formatPlatformDateTime('not-a-date'), {
    text: 'not-a-date',
    title: 'not-a-date',
    valid: false,
  });
});

it('current user contract can carry preferred display time zone', () => {
  const currentUser: CurrentUser = {
    userId: 'user-1',
    username: 'Alice',
    tenantId: 'tenant-a',
    system: false,
    timeZone: 'Asia/Shanghai',
  };

  assert.equal(currentUser.timeZone, 'Asia/Shanghai');
});
