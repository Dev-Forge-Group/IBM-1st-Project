import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import TourReservationList from './TourReservationList';

const { reservations, network } = vi.hoisted(() => ({
  reservations: {
    reservableRooms: vi.fn(), lookupTourReservations: vi.fn(), cancelTourReservation: vi.fn(),
  },
  network: vi.fn(),
}));

vi.mock('../api/reservationApi', () => ({ reservationApi: reservations }));
vi.mock('../../../app/http/apiBase', () => ({ fetchWithAuthRetry: network }));
vi.mock('../../../app/layouts/components/Header', () => ({ default: () => null }));
vi.mock('../../../app/layouts/components/Footer', () => ({ default: () => null }));

const PHONE = '010-1234-5678';
const PIN = '2468';
let records;

function tour(id, status = 'requested') {
  return {
    tourId: id, buildingId: 1, buildingNm: 'Lookup fixture', roomId: 1, roomNo: 101,
    tourStartAt: '2100-03-12T10:00:00', tourEndAt: '2100-03-12T11:00:00',
    tourNm: `Fixture visitor ${id}`, tourTel: PHONE, tourSt: status,
  };
}

function page(content, { number = 1, total = content.length, size = 10 } = {}) {
  return { content, page: number, size, totalElements: total, totalPages: Math.ceil(total / size) };
}

function serverPage(_body, { page: number = 1, size = 10, status } = {}) {
  const matches = records.filter((item) => !status || item.tourSt === status);
  return Promise.resolve(page(matches.slice((number - 1) * size, number * size), {
    number, total: matches.length, size,
  }));
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

async function resolve(request, value) {
  await act(async () => { request.resolve(value); await request.promise; });
}

async function reject(request, message) {
  await act(async () => { request.reject(new Error(message)); await request.promise.catch(() => {}); });
}

function renderLookup(inlineMode = true) {
  return render(<MemoryRouter><TourReservationList inlineMode={inlineMode} /></MemoryRouter>);
}

function phoneInput() { return screen.getByPlaceholderText('연락처 (예: 010-0000-0000)'); }
function pinInput() { return screen.getByPlaceholderText('비밀번호 4자리'); }
function filter(label) { return screen.getByRole('button', { name: label, exact: true }); }
function next() { return screen.getByRole('button', { name: '다음', exact: true }); }
function previous() { return screen.getByRole('button', { name: '이전', exact: true }); }

async function search() {
  fireEvent.change(phoneInput(), { target: { value: PHONE } });
  fireEvent.change(pinInput(), { target: { value: PIN } });
  fireEvent.click(screen.getByRole('button', { name: '🔍 조회', exact: true }));
  await screen.findByText(`예약 #${records[0].tourId}`, { exact: true });
}

function expectCredentials(body) {
  // Keep even synthetic telephone/PIN values out of failing matcher output.
  expect(body.tourTel === PHONE).toBe(true);
  expect(body.tourPwd === PIN).toBe(true);
}

describe('tour lookup preserves a server-filtered page and its credential context', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.spyOn(window, 'alert').mockImplementation(() => {});
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    records = Array.from({ length: 23 }, (_, index) => tour(23 - index, index < 11 ? 'requested' : 'cancelled'));
    reservations.reservableRooms.mockResolvedValue(page([]));
    reservations.lookupTourReservations.mockImplementation(serverPage);
    reservations.cancelTourReservation.mockImplementation(async (id) => {
      const item = records.find((candidate) => candidate.tourId === id);
      item.tourSt = 'cancelled';
      return { ...item };
    });
  });

  afterEach(() => { cleanup(); vi.restoreAllMocks(); });

  it.each([['inline', true], ['standalone', false]])(
    'finds statuses beyond the current unfiltered page and preserves global totals in %s mode',
    async (_label, inlineMode) => {
      renderLookup(inlineMode);
      await search();
      expect(screen.getByText('23', { selector: 'strong' })).toBeInTheDocument();
      fireEvent.click(filter('취소'));
      await screen.findByText('예약 #12', { exact: true });
      expect(reservations.lookupTourReservations.mock.lastCall[1]).toMatchObject({ page: 1, status: 'cancelled' });
      expect(screen.queryByText('예약 #23', { exact: true })).not.toBeInTheDocument();
      expect(screen.getByText('12', { selector: 'strong' })).toBeInTheDocument();
      expect(next()).toBeEnabled();
      fireEvent.click(next());
      await screen.findByText('예약 #2', { exact: true });
      expect(reservations.lookupTourReservations.mock.lastCall[1]).toMatchObject({ page: 2, status: 'cancelled' });
      expect(screen.getByText('12', { selector: 'strong' })).toBeInTheDocument();
      expect(previous()).toBeEnabled();
      expect(next()).toBeDisabled();
      fireEvent.click(filter('대기'));
      await screen.findByText('예약 #23', { exact: true });
      expect(reservations.lookupTourReservations.mock.lastCall[1]).toMatchObject({ page: 1, status: 'requested' });
      expect(screen.getByText('11', { selector: 'strong' })).toBeInTheDocument();
      expect(previous()).toBeDisabled();
    }
  );

  it('does not offer another page when the final page contains exactly ten rows', async () => {
    records = records.slice(0, 10);
    renderLookup();
    await search();
    expect(next()).toBeDisabled();
    expect(previous()).toBeDisabled();
  });

  it('keeps filters usable for a valid credential with no matching status', async () => {
    renderLookup();
    await search();
    fireEvent.click(filter('확정'));
    await screen.findByText('조회된 예약이 없습니다.');
    expect(reservations.lookupTourReservations.mock.lastCall[1].status).toBe('confirmed');
    expect(screen.getByText('0', { selector: 'strong' })).toBeInTheDocument();
    expect(next()).toBeDisabled();
    fireEvent.click(filter('전체'));
    await screen.findByText('예약 #23', { exact: true });
    expect(reservations.lookupTourReservations.mock.lastCall[1].status).toBeUndefined();
    expect(screen.getByText('23', { selector: 'strong' })).toBeInTheDocument();
  });

  it('clamps a filtered page once after its final matching reservation is cancelled', async () => {
    records = Array.from({ length: 11 }, (_, index) => tour(11 - index));
    renderLookup();
    await search();
    fireEvent.click(filter('대기'));
    await waitFor(() => expect(reservations.lookupTourReservations.mock.lastCall[1].status).toBe('requested'));
    fireEvent.click(next());
    await screen.findByText('예약 #1', { exact: true });
    fireEvent.click(screen.getByRole('button', { name: '예약 취소', exact: true }));
    await screen.findByText('예약 #11', { exact: true });
    expect(screen.getByText('10', { selector: 'strong' })).toBeInTheDocument();
    expect(next()).toBeDisabled();
    expect(previous()).toBeDisabled();
    const calls = reservations.lookupTourReservations.mock.calls;
    expect(calls.slice(-2).map(([, options]) => [options.page, options.status]))
      .toEqual([[2, 'requested'], [1, 'requested']]);
    expectCredentials(reservations.cancelTourReservation.mock.lastCall[1]);
    expect(reservations.cancelTourReservation.mock.lastCall[0]).toBe(1);
  });

  it('locks the current query and prevents duplicate cancellation until its refresh completes', async () => {
    records = [tour(1)];
    const pending = deferred();
    const refresh = deferred();
    reservations.cancelTourReservation.mockReturnValue(pending.promise);
    renderLookup();
    await search();
    reservations.lookupTourReservations.mockReturnValueOnce(refresh.promise);
    const cancelButton = screen.getByRole('button', { name: '예약 취소', exact: true });
    fireEvent.click(cancelButton);
    fireEvent.click(cancelButton);
    expect(reservations.cancelTourReservation.mock.calls.length).toBe(1);
    expect(phoneInput()).toBeDisabled();
    expect(pinInput()).toBeDisabled();
    expect(filter('취소')).toBeDisabled();
    fireEvent.change(phoneInput(), { target: { value: '010-9999-8888' } });
    expect(phoneInput().value === PHONE).toBe(true);
    expectCredentials(reservations.cancelTourReservation.mock.lastCall[1]);
    records[0].tourSt = 'cancelled';
    await resolve(pending, { ...records[0] });
    await waitFor(() => expect(reservations.lookupTourReservations.mock.calls.length).toBe(2));
    expect(phoneInput()).toBeDisabled();
    expect(pinInput()).toBeDisabled();
    expect(filter('취소')).toBeDisabled();
    fireEvent.keyDown(phoneInput(), { key: 'Enter' });
    expect(reservations.lookupTourReservations.mock.calls.length).toBe(2);
    await resolve(refresh, page([{ ...records[0] }], { total: 1 }));
    await waitFor(() => expect(phoneInput()).toBeEnabled());
    expect(screen.queryByRole('button', { name: '예약 취소', exact: true })).not.toBeInTheDocument();
    expectCredentials(reservations.lookupTourReservations.mock.lastCall[0]);
  });

  it('ignores a late success for a previously selected filter', async () => {
    const cancelled = deferred();
    const requested = deferred();
    renderLookup();
    await search();
    reservations.lookupTourReservations.mockImplementation((_body, options) => options.status === 'cancelled'
      ? cancelled.promise : requested.promise);
    fireEvent.click(filter('취소'));
    fireEvent.click(filter('대기'));
    await resolve(requested, page([tour(41)], { total: 1 }));
    await screen.findByText('예약 #41', { exact: true });
    await resolve(cancelled, page([tour(42, 'cancelled')], { total: 1 }));
    expect(screen.queryByText('예약 #42', { exact: true })).not.toBeInTheDocument();
    expect(screen.getByText('예약 #41', { exact: true })).toBeInTheDocument();
    expect(reservations.lookupTourReservations.mock.calls.length).toBe(3);
  });

  it('does not let an old error or completion clear the latest loading state', async () => {
    const cancelled = deferred();
    const requested = deferred();
    renderLookup();
    await search();
    reservations.lookupTourReservations.mockImplementation((_body, options) => options.status === 'cancelled'
      ? cancelled.promise : requested.promise);
    fireEvent.click(filter('취소'));
    fireEvent.click(filter('대기'));
    await reject(cancelled, 'Obsolete lookup error');
    expect(screen.getByRole('button', { name: '조회 중…', exact: true })).toBeDisabled();
    expect(screen.queryByText('Obsolete lookup error')).not.toBeInTheDocument();
    await resolve(requested, page([tour(51)], { total: 1 }));
    await screen.findByText('예약 #51', { exact: true });
    expect(screen.getByRole('button', { name: '🔍 조회', exact: true })).toBeEnabled();
  });

  it('cannot let a stale out-of-range result launch a replacement query', async () => {
    const cancelled = deferred();
    records = Array.from({ length: 40 }, (_, index) => tour(40 - index, 'cancelled'));
    renderLookup();
    await search();
    fireEvent.click(filter('취소'));
    await waitFor(() => expect(reservations.lookupTourReservations.mock.lastCall[1]).toMatchObject({
      status: 'cancelled', page: 1,
    }));
    await screen.findByText('예약 #40', { exact: true });
    fireEvent.click(next());
    await screen.findByText('예약 #30', { exact: true });
    fireEvent.click(next());
    await screen.findByText('예약 #20', { exact: true });
    reservations.lookupTourReservations.mockImplementation((_body, options) =>
      options.status === 'cancelled' && options.page === 4
        ? cancelled.promise : Promise.resolve(page([tour(61)], { total: 1 })));
    fireEvent.click(next());
    await waitFor(() => expect(reservations.lookupTourReservations.mock.lastCall[1]).toMatchObject({
      status: 'cancelled', page: 4,
    }));
    fireEvent.click(filter('대기'));
    await screen.findByText('예약 #61', { exact: true });
    await resolve(cancelled, page([], { number: 4, total: 1 }));
    expect(reservations.lookupTourReservations.mock.calls.length).toBe(6);
    expect(reservations.lookupTourReservations.mock.lastCall[1]).toMatchObject({
      status: 'requested', page: 1,
    });
    expect(screen.getByText('예약 #61', { exact: true })).toBeInTheDocument();
  });

  it('retains filter controls after a current request fails so the user can recover', async () => {
    renderLookup();
    await search();
    reservations.lookupTourReservations.mockRejectedValueOnce(new Error('Current lookup failure'));
    fireEvent.click(filter('취소'));
    await screen.findByText('Current lookup failure');
    expect(screen.queryByText('예약 #23', { exact: true })).not.toBeInTheDocument();
    fireEvent.click(filter('전체'));
    await screen.findByText('예약 #23', { exact: true });
    expect(screen.queryByText('Current lookup failure')).not.toBeInTheDocument();
  });

  it.each(['phone', 'pin'])('invalidates results and a pending response when the %s is edited', async (field) => {
    const pending = deferred();
    renderLookup();
    await search();
    reservations.lookupTourReservations.mockReturnValueOnce(pending.promise);
    fireEvent.click(screen.getByRole('button', { name: '🔍 조회', exact: true }));
    fireEvent.change(field === 'phone' ? phoneInput() : pinInput(), {
      target: { value: field === 'phone' ? '010-9999-8888' : '1357' },
    });
    expect(screen.queryByText('예약 #23', { exact: true })).not.toBeInTheDocument();
    await resolve(pending, page([tour(99)], { total: 1 }));
    expect(screen.queryByText('예약 #99', { exact: true })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '예약 취소', exact: true })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '🔍 조회', exact: true })).toBeEnabled();
  });

  it.each([['cancelled', 'cancelled'], [undefined, null]])(
    'serializes the optional lookup status %s without changing the credential body',
    async (status, expectedStatus) => {
      const actual = await vi.importActual('../api/reservationApi');
      network.mockResolvedValue({
        ok: true, status: 200, headers: { get: () => 'application/json' },
        json: async () => ({ success: true, data: page([]) }),
      });
      await actual.reservationApi.lookupTourReservations({ tourTel: PHONE, tourPwd: PIN }, { status });
      const [url, init] = network.mock.lastCall;
      expect(new URL(url, 'http://fixture.test').searchParams.get('status')).toBe(expectedStatus);
      expect(init.method).toBe('POST');
      expectCredentials(JSON.parse(init.body));
      expect(Object.keys(JSON.parse(init.body)).sort()).toEqual(['tourPwd', 'tourTel']);
    }
  );
});
