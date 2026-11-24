import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import TourReservationCreate from './TourReservationCreate';

const { property, reservations } = vi.hoisted(() => ({
  property: { getBuildings: vi.fn() },
  reservations: {
    reservableRooms: vi.fn(), reservableTourSlots: vi.fn(), createTourReservation: vi.fn(),
  },
}));

vi.mock('../../property/api/propertyApi', () => ({ propertyApi: property }));
vi.mock('../api/reservationApi', () => ({ reservationApi: reservations }));
vi.mock('../../../app/layouts/components/Header', () => ({ default: () => null }));
vi.mock('../../../app/layouts/components/Footer', () => ({ default: () => null }));

const FIRST_DATE = '2100-03-12';
const NEXT_DATE = '2100-03-13';
const TELEPHONE = '010-1234-5678';
const PIN = '2468';
const BUILDINGS = [
  { buildingId: 1, buildingNm: 'Building A' },
  { buildingId: 2, buildingNm: 'Building B' },
];
const FIRST_ROOMS = { content: [{ roomId: 11, roomNo: 101 }] };
const SECOND_ROOMS = { content: [{ roomId: 22, roomNo: 202 }] };

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function slot(date, label, hour = 10) {
  return { label, startAt: `${date}T${hour}:00:00`, endAt: `${date}T${hour + 1}:00:00` };
}

function slotPage(date, label, hour = 10) {
  return { availableSlots: [slot(date, label, hour)] };
}

async function resolve(request, value) {
  await act(async () => { request.resolve(value); await request.promise; });
}

async function reject(request, message) {
  await act(async () => {
    request.reject(new Error(message));
    await request.promise.catch(() => {});
  });
}

function renderBooking(props = {}) {
  const onSuccess = vi.fn();
  const view = render(
    <MemoryRouter initialEntries={['/reservations/tour/create']}>
      <Routes>
        <Route path="/reservations/tour/create" element={(
          <TourReservationCreate inlineMode onSuccess={onSuccess}
            initialBuildingId={1} initialRoomId="11" initialDate={FIRST_DATE} {...props} />
        )} />
        <Route path="/reservations/tour/list" element={<p>Reservation lookup destination</p>} />
      </Routes>
    </MemoryRouter>
  );
  return { ...view, onSuccess };
}

function changeDate(view, date) {
  fireEvent.change(view.container.querySelector('input[type="date"]'), { target: { value: date } });
}

function fillVisitor() {
  fireEvent.change(screen.getByPlaceholderText('방문자 이름'), { target: { value: 'Fixture visitor' } });
  fireEvent.change(screen.getByPlaceholderText('010-0000-0000'), { target: { value: TELEPHONE } });
  fireEvent.change(screen.getByPlaceholderText('조회·취소 시 사용'), { target: { value: PIN } });
  expect(screen.getByPlaceholderText('조회·취소 시 사용').checkValidity()).toBe(true);
}

function bookingButton() {
  return screen.getByRole('button', { name: /예약 신청/ });
}

async function confirmBooking() {
  fireEvent.click(bookingButton());
  await screen.findByText('예약을 신청하시겠습니까?');
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: '예', exact: true }));
  });
}

async function assertSubmittedScope({ buildingId = 1, roomId = 11, date = FIRST_DATE, hour = 10 } = {}) {
  await waitFor(() => expect(reservations.createTourReservation.mock.calls.length).toBe(1));
  const body = reservations.createTourReservation.mock.calls[0][0];
  expect(body.buildingId).toBe(buildingId);
  expect(body.roomId).toBe(roomId);
  expect(body.tourStartAt).toBe(`${date}T${hour}:00:00`);
  expect(body.tourEndAt).toBe(`${date}T${hour + 1}:00:00`);
  // Keep credential fixtures out of failing matcher output.
  expect(body.tourTel === TELEPHONE).toBe(true);
  expect(body.tourPwd === PIN).toBe(true);
}

describe('tour creation keeps slots within the current building, room and date', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    property.getBuildings.mockResolvedValue({ content: BUILDINGS });
    reservations.reservableRooms.mockImplementation(({ buildingId }) => Promise.resolve(
      buildingId === 1 ? FIRST_ROOMS : SECOND_ROOMS
    ));
    reservations.reservableTourSlots.mockImplementation(({ buildingId, date }) => Promise.resolve(
      buildingId === 1 ? slotPage(date, 'A 10:00') : slotPage(date, 'B 13:00', 13)
    ));
    reservations.createTourReservation.mockResolvedValue({ tourId: 42 });
  });

  afterEach(() => { cleanup(); });

  it.each([true, false])('explains an unavailable operator and resets the reason when a different room is selected (inline %s)', async (inlineMode) => {
    reservations.reservableRooms.mockResolvedValue({
      content: [{ roomId: 11, roomNo: 101 }, { roomId: 12, roomNo: 102 }],
    });
    reservations.reservableTourSlots.mockImplementation(({ roomId, date }) => Promise.resolve(
      roomId === 11
        ? { availableSlots: [], unavailableReason: 'TOUR_RSV_400_7' }
        : slotPage(date, 'Verified 13:00', 13)
    ));
    renderBooking({ inlineMode });

    const reason = '소유 관계 확인 후 방문을 예약할 수 있습니다.';
    expect(await screen.findByText(reason)).toHaveAttribute('role', 'status');
    fillVisitor();
    expect(bookingButton()).toBeDisabled();
    expect(reservations.createTourReservation).not.toHaveBeenCalled();

    fireEvent.change(screen.getByRole('combobox'), { target: { value: '12' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Verified 13:00' }));
    expect(screen.queryByText(reason)).not.toBeInTheDocument();
    expect(bookingButton()).toBeEnabled();
  });

  it.each([
    ['inline conflict', true, 409, 'TOUR_RSV_409_1', '이미 예약된 시간대와 겹칩니다. 다른 시간을 선택해주세요.'],
    ['standalone conflict', false, 409, 'TOUR_RSV_409_1', '이미 예약된 시간대와 겹칩니다. 다른 시간을 선택해주세요.'],
    ['inline server failure', true, 500, 'COMMON_500', '서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.'],
    ['standalone server failure', false, 500, 'COMMON_500', '서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.'],
  ])('shows one error and preserves a deliberate current-context retry after %s', async (
    _name, inlineMode, status, errorCode, message
  ) => {
    reservations.reservableTourSlots.mockImplementation(({ date }) => Promise.resolve(
      slotPage(date, date === FIRST_DATE ? 'Original 10:00' : 'Retry 13:00', date === FIRST_DATE ? 10 : 13)
    ));
    reservations.createTourReservation.mockRejectedValueOnce(Object.assign(
      new Error('Synthetic failed reservation'), { status, errorCode }
    ));
    const view = renderBooking({ inlineMode });
    fireEvent.click(await screen.findByRole('button', { name: 'Original 10:00' }));
    fillVisitor();

    await confirmBooking();

    await waitFor(() => expect(screen.getAllByText(`⚠️ ${message}`)).toHaveLength(1));
    expect(screen.queryByText('예약을 신청하시겠습니까?')).not.toBeInTheDocument();
    expect(screen.queryByText('Reservation lookup destination')).not.toBeInTheDocument();
    expect(view.onSuccess).not.toHaveBeenCalled();
    expect(screen.getByPlaceholderText('방문자 이름')).toHaveValue('Fixture visitor');
    expect(screen.getByPlaceholderText('010-0000-0000').value === TELEPHONE).toBe(true);
    expect(screen.getByPlaceholderText('조회·취소 시 사용').value === PIN).toBe(true);
    expect(bookingButton()).toBeEnabled();
    expect(reservations.createTourReservation.mock.calls.length).toBe(1);

    changeDate(view, NEXT_DATE);
    fireEvent.click(await screen.findByRole('button', { name: 'Retry 13:00' }));
    expect(screen.queryByText(`⚠️ ${message}`)).not.toBeInTheDocument();
    expect(reservations.createTourReservation.mock.calls.length).toBe(1);
    await confirmBooking();

    await waitFor(() => expect(reservations.createTourReservation.mock.calls.length).toBe(2));
    const retried = reservations.createTourReservation.mock.calls[1][0];
    expect(retried.buildingId).toBe(1);
    expect(retried.roomId).toBe(11);
    expect(retried.tourStartAt).toBe(`${NEXT_DATE}T13:00:00`);
    expect(retried.tourEndAt).toBe(`${NEXT_DATE}T14:00:00`);
    expect(retried.tourTel === TELEPHONE).toBe(true);
    expect(retried.tourPwd === PIN).toBe(true);
    if (inlineMode) {
      await waitFor(() => expect(view.onSuccess).toHaveBeenCalledTimes(1));
    } else {
      await screen.findByText('Reservation lookup destination');
    }
  });

  it.each([
    ['inline', true], ['standalone', false],
  ])('cannot submit a previous date while replacement slots load in %s mode', async (_name, inlineMode) => {
    const next = deferred();
    reservations.reservableTourSlots.mockImplementation(({ date }) => date === FIRST_DATE
      ? Promise.resolve(slotPage(FIRST_DATE, 'Previous date 10:00')) : next.promise);
    const view = renderBooking({ inlineMode });
    fireEvent.click(await screen.findByRole('button', { name: 'Previous date 10:00' }));
    fillVisitor();
    expect(bookingButton()).toBeEnabled();

    changeDate(view, NEXT_DATE);
    await waitFor(() => expect(reservations.reservableTourSlots).toHaveBeenCalledWith({
      buildingId: 1, roomId: 11, date: NEXT_DATE,
    }));
    const previous = screen.queryByRole('button', { name: 'Previous date 10:00' });
    if (previous) fireEvent.click(previous);

    expect(bookingButton()).toBeDisabled();
    fireEvent.submit(bookingButton().closest('form'));
    expect(reservations.createTourReservation.mock.calls.length).toBe(0);
    await resolve(next, slotPage(NEXT_DATE, 'Current date 11:00', 11));
    expect(bookingButton()).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Current date 11:00' }));
    await confirmBooking();
    await assertSubmittedScope({ date: NEXT_DATE, hour: 11 });
  });

  it('ignores a previous date success that arrives after the current date success', async () => {
    const previous = deferred();
    const current = deferred();
    reservations.reservableTourSlots.mockImplementation(({ date }) => date === FIRST_DATE
      ? previous.promise : current.promise);
    const view = renderBooking();
    await waitFor(() => expect(reservations.reservableTourSlots).toHaveBeenCalledWith({
      buildingId: 1, roomId: 11, date: FIRST_DATE,
    }));
    changeDate(view, NEXT_DATE);
    await waitFor(() => expect(reservations.reservableTourSlots).toHaveBeenCalledWith({
      buildingId: 1, roomId: 11, date: NEXT_DATE,
    }));
    await resolve(current, slotPage(NEXT_DATE, 'Current result 13:00', 13));
    await resolve(previous, slotPage(FIRST_DATE, 'Obsolete result 10:00'));

    expect(screen.queryByRole('button', { name: 'Obsolete result 10:00' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Current result 13:00' }));
    fillVisitor();
    await confirmBooking();
    await assertSubmittedScope({ date: NEXT_DATE, hour: 13 });
  });

  it('ignores an obsolete slot error without clearing the current successful result', async () => {
    const previous = deferred();
    const current = deferred();
    reservations.reservableTourSlots.mockImplementation(({ date }) => date === FIRST_DATE
      ? previous.promise : current.promise);
    const view = renderBooking();
    await waitFor(() => expect(reservations.reservableTourSlots.mock.calls.length).toBe(1));
    changeDate(view, NEXT_DATE);
    await waitFor(() => expect(reservations.reservableTourSlots.mock.calls.length).toBe(2));
    await resolve(current, slotPage(NEXT_DATE, 'Current successful 13:00', 13));
    await reject(previous, 'Obsolete slot failure');

    expect(screen.getByRole('button', { name: 'Current successful 13:00' })).toBeInTheDocument();
    expect(screen.queryByText('Obsolete slot failure')).not.toBeInTheDocument();
  });

  it('does not let an obsolete completion remove the current loading state', async () => {
    const previous = deferred();
    const current = deferred();
    reservations.reservableTourSlots.mockImplementation(({ date }) => date === FIRST_DATE
      ? previous.promise : current.promise);
    const view = renderBooking();
    await waitFor(() => expect(reservations.reservableTourSlots.mock.calls.length).toBe(1));
    changeDate(view, NEXT_DATE);
    await waitFor(() => expect(reservations.reservableTourSlots.mock.calls.length).toBe(2));
    await resolve(previous, slotPage(FIRST_DATE, 'Obsolete loading completion'));

    expect(screen.getByText('(조회중…)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Obsolete loading completion' })).not.toBeInTheDocument();
    expect(bookingButton()).toBeDisabled();
    await resolve(current, slotPage(NEXT_DATE, 'Finished current request'));
    expect(screen.queryByText('(조회중…)')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Finished current request' })).toBeInTheDocument();
  });

  it('invalidates a pending slot response when the user clears the room', async () => {
    const pending = deferred();
    reservations.reservableTourSlots.mockReturnValue(pending.promise);
    renderBooking();
    await waitFor(() => expect(reservations.reservableTourSlots.mock.calls.length).toBe(1));
    fireEvent.change(screen.getByRole('combobox'), { target: { value: '' } });
    await resolve(pending, slotPage(FIRST_DATE, 'Cleared room obsolete result'));

    expect(screen.getByRole('combobox')).toHaveValue('');
    expect(screen.queryByRole('button', { name: 'Cleared room obsolete result' })).not.toBeInTheDocument();
    expect(bookingButton()).toBeDisabled();
    expect(reservations.createTourReservation.mock.calls.length).toBe(0);
  });

  it.each(['success', 'error'])('ignores an obsolete building rooms %s after switching buildings', async (outcome) => {
    const previous = deferred();
    const current = deferred();
    reservations.reservableRooms.mockImplementation(({ buildingId }) => buildingId === 1
      ? previous.promise : current.promise);
    renderBooking({ initialRoomId: '' });
    await waitFor(() => expect(reservations.reservableRooms).toHaveBeenCalledWith(expect.objectContaining({ buildingId: 1 })));
    fireEvent.click(screen.getByRole('button', { name: 'Building B', exact: true }));
    await waitFor(() => expect(reservations.reservableRooms).toHaveBeenCalledWith(expect.objectContaining({ buildingId: 2 })));
    await resolve(current, SECOND_ROOMS);
    await screen.findByRole('button', { name: 'B 13:00' });
    if (outcome === 'success') await resolve(previous, FIRST_ROOMS);
    else await reject(previous, 'Obsolete rooms failure');

    expect(screen.getByRole('option', { name: '202호' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: '101호' })).not.toBeInTheDocument();
    expect(screen.getByRole('combobox')).toHaveValue('22');
    expect(screen.getByRole('button', { name: 'B 13:00' })).toBeInTheDocument();
    expect(screen.queryByText('Obsolete rooms failure')).not.toBeInTheDocument();
  });

  it('does not query or submit a room from the previous building during a manual switch', async () => {
    const nextRooms = deferred();
    reservations.reservableRooms.mockImplementation(({ buildingId }) => buildingId === 1
      ? Promise.resolve(FIRST_ROOMS) : nextRooms.promise);
    renderBooking({ initialBuildingId: null, initialRoomId: '' });
    fireEvent.click(await screen.findByRole('button', { name: 'Building A', exact: true }));
    fireEvent.click(await screen.findByRole('button', { name: 'A 10:00' }));
    fillVisitor();
    fireEvent.click(screen.getByRole('button', { name: 'Building B', exact: true }));
    await waitFor(() => expect(reservations.reservableRooms).toHaveBeenCalledWith(expect.objectContaining({ buildingId: 2 })));

    expect(screen.getByRole('combobox')).toHaveValue('');
    expect(bookingButton()).toBeDisabled();
    expect(reservations.reservableTourSlots.mock.calls.some(([query]) => query.buildingId === 2 && query.roomId === 11))
      .toBe(false);
    await resolve(nextRooms, SECOND_ROOMS);
    fireEvent.click(await screen.findByRole('button', { name: 'B 13:00' }));
    expect(screen.getByPlaceholderText('방문자 이름')).toHaveValue('Fixture visitor');
    await confirmBooking();
    await assertSubmittedScope({ buildingId: 2, roomId: 22, hour: 13 });
  });

  it('keeps the initial matching AI recommendation usable with the original credentials', async () => {
    renderBooking({ initialSlotStartAt: `${FIRST_DATE}T10:00:00`, initialSlotEndAt: `${FIRST_DATE}T11:00:00` });
    await screen.findByRole('button', { name: 'A 10:00' });
    fillVisitor();

    await waitFor(() => expect(bookingButton()).toBeEnabled());
    await confirmBooking();
    await assertSubmittedScope();
  });

  it('cannot auto-select an old AI recommendation from a late previous-date response', async () => {
    const previous = deferred();
    const current = deferred();
    reservations.reservableTourSlots.mockImplementation(({ date }) => date === FIRST_DATE
      ? previous.promise : current.promise);
    const view = renderBooking({ initialSlotStartAt: `${FIRST_DATE}T10:00:00`, initialSlotEndAt: `${FIRST_DATE}T11:00:00` });
    await waitFor(() => expect(reservations.reservableTourSlots.mock.calls.length).toBe(1));
    fillVisitor();
    changeDate(view, NEXT_DATE);
    await waitFor(() => expect(reservations.reservableTourSlots.mock.calls.length).toBe(2));
    await resolve(current, slotPage(NEXT_DATE, 'Current AI context 10:00'));
    await resolve(previous, slotPage(FIRST_DATE, 'Obsolete AI recommendation'));

    expect(screen.queryByRole('button', { name: 'Obsolete AI recommendation' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Current AI context 10:00' })).toBeInTheDocument();
    expect(bookingButton()).toBeDisabled();
    expect(reservations.createTourReservation.mock.calls.length).toBe(0);
  });
});
