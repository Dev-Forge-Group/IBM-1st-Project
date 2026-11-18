import { afterEach, describe, expect, it } from 'vitest';
import { addContractDays, contractEndAfterMonths, todayInSeoul } from './contractDates';

const ORIGINAL_TZ = process.env.TZ;

afterEach(() => {
  if (ORIGINAL_TZ === undefined) delete process.env.TZ;
  else process.env.TZ = ORIGINAL_TZ;
});

describe('contract calendar dates', () => {
  it.each([
    ['2026-11-17T14:59:59Z', '2026-11-17'],
    ['2026-11-17T15:00:00Z', '2026-11-18'],
    ['2026-12-31T15:00:00Z', '2027-01-01'],
    ['2028-02-28T15:00:00Z', '2028-02-29'],
  ])('maps instant %s to the Korean business date %s', (instant, expected) => {
    expect(todayInSeoul(new Date(instant))).toBe(expected);
  });

  it.each([
    ['UTC', 0], ['America/Los_Angeles', 480], ['Asia/Seoul', -540], ['Pacific/Kiritimati', -840],
  ])('keeps calendar additions and existing month rollover invariant in %s', (tz, offset) => {
    process.env.TZ = tz;
    expect(new Date('2026-11-17T15:00:00Z').getTimezoneOffset()).toBe(offset);
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe(tz);

    expect(addContractDays('2026-03-05', 7)).toBe('2026-03-12');
    expect(addContractDays('2026-10-31', 7)).toBe('2026-11-07');
    expect(addContractDays('2028-02-25', 7)).toBe('2028-03-03');
    expect(addContractDays('2026-12-29', 7)).toBe('2027-01-05');
    expect(addContractDays('2028-03-03', -7)).toBe('2028-02-25');
    expect(contractEndAfterMonths('2026-03-01', 1)).toBe('2026-03-31');
    expect(contractEndAfterMonths('2026-01-31', 1)).toBe('2026-03-02');
    expect(contractEndAfterMonths('2024-01-31', 1)).toBe('2024-03-01');
    expect(contractEndAfterMonths('2024-02-29', 12)).toBe('2025-02-28');
  });

  it.each(['', null, 'not-a-date'])('keeps absent or unreadable date input %s empty', (input) => {
    expect(addContractDays(input, 7)).toBe('');
    expect(contractEndAfterMonths(input, 1)).toBe('');
  });
});
