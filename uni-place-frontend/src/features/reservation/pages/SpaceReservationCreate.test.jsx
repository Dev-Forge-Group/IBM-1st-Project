import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import SpaceReservationCreate from './SpaceReservationCreate';

const { property, contracts, reservations } = vi.hoisted(() => ({
  property: { getBuildings: vi.fn() },
  contracts: { myContracts: vi.fn() },
  reservations: { reservableSpaces: vi.fn(), createSpaceReservation: vi.fn() },
}));

vi.mock('../../property/api/propertyApi', () => ({ propertyApi: property }));
vi.mock('../../contract/api/contractApi', () => ({ contractApi: contracts }));
vi.mock('../api/reservationApi', () => ({ reservationApi: reservations }));
vi.mock('../../user/hooks/useAuth', () => ({ useAuth: () => ({ user: { userId: 'synthetic-tenant' }, loading: false }) }));
vi.mock('../../../app/layouts/components/Header', () => ({ default: () => null }));
vi.mock('../../../app/layouts/components/Footer', () => ({ default: () => null }));

const DAY = '2099-06-10';
const NEXT_DAY = '2099-06-11';
const BUILDINGS = [{ buildingId: 1, buildingNm: 'First building' }, { buildingId: 2, buildingNm: 'Second building' }];

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function space(buildingId, spaceId, date, label) {
  return {
    buildingId, spaceId, spaceNm: `Synthetic space ${spaceId}`,
    availableSlots: [{ label, startAt: `${date}T10:00:00`, endAt: `${date}T12:00:00` }],
  };
}

function openForm(props = {}) {
  const onSuccess = vi.fn();
  const { container } = render(
    <MemoryRouter>
      <SpaceReservationCreate inlineMode initBuildingId={1} initSpaceId={11}
        initStartAt={`${DAY}T08:00:00`} initEndAt={`${DAY}T10:00:00`}
        onSuccess={onSuccess} {...props} />
    </MemoryRouter>,
  );
  return { dateInput: container.querySelector('input[type="date"]'), onSuccess };
}

function submitButton() {
  return screen.getByRole('button', { name: /예약 확정/ });
}

describe('space booking availability', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    property.getBuildings.mockResolvedValue({ content: BUILDINGS });
    contracts.myContracts.mockResolvedValue(BUILDINGS.map(({ buildingId }) => ({ buildingId, contractStatus: 'active' })));
    reservations.reservableSpaces.mockImplementation(({ buildingId, date }) => Promise.resolve({
      content: buildingId === 1
        ? [space(1, 11, date, 'Eleven 10:00'), space(1, 12, date, 'Twelve 10:00')]
        : [space(2, 22, date, 'Twenty-two 10:00')],
    }));
    reservations.createSpaceReservation.mockResolvedValue({ srId: 42 });
  });
  afterEach(cleanup);

  it('clears a previous date slot while awaiting the new date and preserves the selected space', async () => {
    const next = deferred();
    reservations.reservableSpaces.mockImplementationOnce(({ date }) => Promise.resolve({
      content: [space(1, 11, date, 'Eleven before'), space(1, 12, date, 'Twelve before')],
    })).mockReturnValueOnce(next.promise);
    const { dateInput, onSuccess } = openForm();
    await screen.findByRole('button', { name: 'Eleven before' });
    fireEvent.change(screen.getByRole('combobox'), { target: { value: '12' } });
    fireEvent.click(screen.getByRole('button', { name: 'Twelve before' }));
    expect(submitButton()).toBeEnabled();

    fireEvent.change(dateInput, { target: { value: NEXT_DAY } });
    expect(screen.queryByRole('button', { name: 'Twelve before' })).not.toBeInTheDocument();
    expect(submitButton()).toBeDisabled();
    expect(reservations.createSpaceReservation).not.toHaveBeenCalled();

    await act(async () => {
      next.resolve({ content: [space(1, 11, NEXT_DAY, 'Eleven next'), space(1, 12, NEXT_DAY, 'Twelve next')] });
    });
    expect(screen.getByRole('combobox')).toHaveValue('12');
    fireEvent.click(await screen.findByRole('button', { name: 'Twelve next' }));
    fireEvent.click(submitButton());
    await waitFor(() => expect(onSuccess).toHaveBeenCalledTimes(1));
    expect(reservations.createSpaceReservation).toHaveBeenCalledWith({
      buildingId: 1, spaceId: 12, srStartAt: `${NEXT_DAY}T10:00:00`, srEndAt: `${NEXT_DAY}T12:00:00`, srNoPeople: 1,
    });
  });

  it.each(['success', 'error'])('ignores an old building request that completes with %s', async (completion) => {
    const earlier = deferred();
    reservations.reservableSpaces.mockReturnValueOnce(earlier.promise);
    openForm();
    await waitFor(() => expect(reservations.reservableSpaces).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Second building' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Twenty-two 10:00' }));
    await act(async () => {
      if (completion === 'success') earlier.resolve({ content: [space(1, 11, DAY, 'Obsolete time')] });
      else earlier.reject(new Error('Obsolete availability error'));
    });

    expect(screen.getByRole('combobox')).toHaveValue('22');
    expect(screen.queryByRole('button', { name: 'Obsolete time' })).not.toBeInTheDocument();
    expect(screen.queryByText('Obsolete availability error')).not.toBeInTheDocument();
    expect(submitButton()).toBeEnabled();
  });

  it('does not auto-select a slot when the suggested end differs from the live slot', async () => {
    openForm({ initStartAt: `${DAY}T10:00:00`, initEndAt: `${DAY}T11:00:00` });
    const slot = await screen.findByRole('button', { name: 'Eleven 10:00' });
    expect(submitButton()).toBeDisabled();
    fireEvent.click(slot);
    expect(submitButton()).toBeEnabled();
  });

  it('auto-selects a recommendation when both times match the current space', async () => {
    openForm({ initStartAt: `${DAY}T10:00:00`, initEndAt: `${DAY}T12:00:00` });
    await screen.findByText(/AI 추천 시간 자동 선택/);
    expect(submitButton()).toBeEnabled();
  });
});
