import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ContractApply from './ContractApply';

const { contracts, property } = vi.hoisted(() => ({
  contracts: { myContracts: vi.fn(), createContract: vi.fn() },
  property: { getRoomDetail: vi.fn() },
}));
vi.mock('../api/contractApi', () => ({ contractApi: contracts }));
vi.mock('../../property/api/propertyApi', () => ({ propertyApi: property }));
vi.mock('../../user/hooks/useAuth', () => ({
  useAuth: () => ({ user: { userId: 'synthetic-calendar-applicant' }, loading: false }),
}));
vi.mock('../../../app/layouts/components/Header', () => ({ default: () => null }));
vi.mock('../../../app/layouts/components/Footer', () => ({ default: () => null }));
vi.mock('../../../shared/components/Modal/Modal', () => ({ default: () => null }));

const ORIGINAL_TZ = process.env.TZ;
const ZONES = [
  ['UTC', 0], ['America/Los_Angeles', 480], ['Asia/Seoul', -540], ['Pacific/Kiritimati', -840],
];
const CALENDAR_CASES = [
  ['2026-03-01', '2026-03-08', '2026-03-31'],
  ['2026-03-05', '2026-03-12', '2026-04-04'],
  ['2028-02-25', '2028-03-03', '2028-03-24'],
  ['2026-12-29', '2027-01-05', '2027-01-28'],
  // Preserve the existing UTC calendar rollover instead of introducing month-end clamping.
  ['2026-01-31', '2026-02-07', '2026-03-02'],
];

class FixtureFileReader {
  readAsDataURL() { this.onload?.({ target: { result: 'data:image/png;base64,U3ludGhldGlj' } }); }
}

function zone(tz, offset) {
  process.env.TZ = tz;
  // This guards against a nominal TZ matrix that never changes the worker's actual Date behavior.
  expect(new Date('2026-11-17T15:00:00Z').getTimezoneOffset()).toBe(offset);
  expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe(tz);
}

async function readyForm() {
  const view = render(<MemoryRouter initialEntries={['/contracts/apply?roomId=7']}><ContractApply /></MemoryRouter>);
  await screen.findByPlaceholderText('임차인 성명');
  return {
    start: view.container.querySelector('input[name="contractStart"]'),
    end: view.container.querySelector('input[name="contractEnd"]'),
    form: view.container.querySelector('form'),
    file: view.container.querySelector('input[type="file"]'),
  };
}

function completeApplicant(fields) {
  fireEvent.change(screen.getByPlaceholderText('매월 납부일 (1~31)'), { target: { value: '31' } });
  fireEvent.change(screen.getByPlaceholderText('임차인 성명'), { target: { value: '합성신청자' } });
  fireEvent.change(screen.getByPlaceholderText('010-0000-0000'), { target: { value: '010-0000-0000' } });
  fireEvent.change(screen.getByPlaceholderText('임차인 주소'), { target: { value: 'Synthetic calendar fixture address' } });
  fireEvent.change(screen.getByPlaceholderText('000000-0000000'), { target: { value: '000000-0000000' } });
  const signature = new File(['Synthetic calendar signature'], 'synthetic-signature.png', { type: 'image/png' });
  fireEvent.change(fields.file, { target: { files: [signature] } });
  fireEvent.click(screen.getByRole('checkbox', { name: '전체 동의', exact: true }));
  return signature;
}

describe('contract application uses the Korean business date and timezone-independent calendar dates', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));
    vi.stubGlobal('FileReader', FixtureFileReader);
    property.getRoomDetail.mockResolvedValue({
      roomId: 7, roomNo: 101, buildingNm: 'Synthetic calendar building',
      buildingAddr: 'Synthetic calendar address', rentMin: 1,
      deposit: 1000000, rentPrice: 500000, manageFee: 50000,
    });
    contracts.myContracts.mockResolvedValue([]);
    contracts.createContract.mockResolvedValue({ contractId: 11 });
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    if (ORIGINAL_TZ === undefined) delete process.env.TZ;
    else process.env.TZ = ORIGINAL_TZ;
  });

  for (const [tz, offset] of ZONES) {
    it.each([
      ['2026-11-17T14:59:59Z', '2026-11-17', '2026-12-16'],
      ['2026-11-17T15:00:00Z', '2026-11-18', '2026-12-17'],
    ])(`uses KST initial and minimum dates in ${tz} at %s`, async (instant, today, end) => {
      zone(tz, offset);
      vi.setSystemTime(new Date(instant));
      const fields = await readyForm();
      expect(fields.start).toHaveValue(today);
      expect(fields.start).toHaveAttribute('min', today);
      expect(fields.end).toHaveValue(end);
    });

    it.each(CALENDAR_CASES)(`keeps seven-day and monthly dates for %s in ${tz}`, async (start, minimum, end) => {
      zone(tz, offset);
      const fields = await readyForm();
      fireEvent.change(fields.start, { target: { value: start } });
      expect(fields.end).toHaveAttribute('min', minimum);
      expect(fields.end).toHaveValue(end);
    });
  }

  it('rejects the previous UTC date when Korea has already entered the next day', async () => {
    zone('UTC', 0);
    vi.setSystemTime(new Date('2026-11-17T15:00:00Z'));
    const fields = await readyForm();
    fireEvent.change(fields.start, { target: { value: '2026-11-17' } });
    completeApplicant(fields);
    fireEvent.submit(fields.form);
    expect(contracts.createContract).not.toHaveBeenCalled();
    expect(screen.getByText(/계약 시작일은 오늘 이후 날짜여야 합니다/)).toBeInTheDocument();
    expect(fields.start).toHaveValue('2026-11-17');
  });

  it('rejects a six-day period across the Los Angeles spring clock change', async () => {
    zone('America/Los_Angeles', 480);
    vi.setSystemTime(new Date('2026-03-05T03:00:00Z'));
    const fields = await readyForm();
    fireEvent.change(fields.start, { target: { value: '2026-03-05' } });
    fireEvent.change(fields.end, { target: { value: '2026-03-11' } });
    completeApplicant(fields);
    fireEvent.submit(fields.form);
    expect(contracts.createContract).not.toHaveBeenCalled();
    expect(screen.getByText(/계약 종료일은 시작일로부터 최소 7일 이후여야 합니다/)).toBeInTheDocument();
  });

  it('sends a selected seven-day period unchanged across the Los Angeles spring clock change', async () => {
    zone('America/Los_Angeles', 480);
    vi.setSystemTime(new Date('2026-03-05T03:00:00Z'));
    const fields = await readyForm();
    fireEvent.change(fields.start, { target: { value: '2026-03-05' } });
    fireEvent.change(fields.end, { target: { value: '2026-03-12' } });
    const signature = completeApplicant(fields);
    fireEvent.submit(fields.form);
    await waitFor(() => expect(contracts.createContract).toHaveBeenCalledTimes(1));
    expect(contracts.createContract.mock.lastCall[0]).toMatchObject({
      contractStart: '2026-03-05', contractEnd: '2026-03-12', signFile: signature,
    });
  });

  it('checks the new KST day at submit without silently replacing the selected date or applicant input', async () => {
    zone('UTC', 0);
    vi.setSystemTime(new Date('2026-11-17T14:59:59Z'));
    const fields = await readyForm();
    const signature = completeApplicant(fields);
    expect(fields.start).toHaveValue('2026-11-17');

    vi.setSystemTime(new Date('2026-11-17T15:00:00Z'));
    fireEvent.submit(fields.form);
    expect(contracts.createContract).not.toHaveBeenCalled();
    expect(screen.getByText(/계약 시작일은 오늘 이후 날짜여야 합니다/)).toBeInTheDocument();
    expect(fields.start).toHaveValue('2026-11-17');
    expect(fields.start).toHaveAttribute('min', '2026-11-18');
    expect(screen.getByPlaceholderText('임차인 성명')).toHaveValue('합성신청자');
    expect(screen.getByPlaceholderText('매월 납부일 (1~31)')).toHaveValue(31);
    expect(screen.getByRole('checkbox', { name: '전체 동의', exact: true })).toBeChecked();
    expect(screen.getByAltText('서명 미리보기')).toBeInTheDocument();

    fireEvent.change(fields.start, { target: { value: '2026-11-18' } });
    fireEvent.change(fields.end, { target: { value: '2026-11-25' } });
    fireEvent.submit(fields.form);
    await waitFor(() => expect(contracts.createContract).toHaveBeenCalledTimes(1));
    expect(contracts.createContract.mock.lastCall[0]).toMatchObject({
      contractStart: '2026-11-18', contractEnd: '2026-11-25', signFile: signature,
    });
  });

  it('clears the calculated end and minimum when the selected start is removed', async () => {
    const fields = await readyForm();
    fireEvent.change(fields.start, { target: { value: '' } });
    expect(fields.end).toHaveValue('');
    expect(fields.end).toHaveAttribute('min', '');
  });
});
