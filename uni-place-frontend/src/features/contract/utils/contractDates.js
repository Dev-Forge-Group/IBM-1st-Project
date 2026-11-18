const koreanDate = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
});

/** The contract service validates calendar dates against today in Asia/Seoul. */
export function todayInSeoul(now = new Date()) {
  const parts = Object.fromEntries(koreanDate.formatToParts(now).map(({ type, value }) => [type, value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function calendarDate(value) {
  if (!value) return null;
  const date = new Date(`${value}T00:00:00.000Z`);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** Date inputs carry calendar days, so browser offset and daylight saving must not move them. */
export function addContractDays(value, days) {
  const date = calendarDate(value);
  if (!date) return '';
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/** Preserve the existing month rollover followed by one inclusive-end day subtraction. */
export function contractEndAfterMonths(value, months) {
  const date = calendarDate(value);
  if (!date) return '';
  date.setUTCMonth(date.getUTCMonth() + months);
  date.setUTCDate(date.getUTCDate() - 1);
  return date.toISOString().slice(0, 10);
}
