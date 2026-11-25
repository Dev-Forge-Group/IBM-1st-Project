import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import SpaceReservationList from './SpaceReservationList';

const api = vi.hoisted(() => ({
  mySpaceReservations: vi.fn(), reservableSpaces: vi.fn(), cancelSpaceReservation: vi.fn(),
}));

vi.mock('../api/reservationApi', () => ({ reservationApi: api }));
vi.mock('../../user/hooks/useAuth', () => ({
  useAuth: () => ({ user: { userId: 'synthetic-tenant' }, loading: false }),
}));

function openHistory() {
  return render(<MemoryRouter><SpaceReservationList inlineMode /></MemoryRouter>);
}

function result(page, totalPages, content) {
  return { page, totalPages, content };
}

function item(reservationId, spaceNm, srSt = 'requested') {
  return { reservationId, spaceNm, srSt, srNoPeople: 1 };
}

describe('member space reservation history', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.reservableSpaces.mockResolvedValue(result(1, 0, []));
    api.cancelSpaceReservation.mockResolvedValue(null);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('uses the server pages and fetches only the personal list', async () => {
    api.mySpaceReservations.mockImplementation(({ page }) =>
      Promise.resolve(result(page, 2, [item(page, `Synthetic space page ${page}`)])));
    openHistory();
    await screen.findByText('Synthetic space page 1');
    const next = screen.getByRole('button', { name: '다음' });
    expect(next).toBeEnabled();
    fireEvent.click(next);
    await screen.findByText('Synthetic space page 2');
    expect(next).toBeDisabled();
    expect(api.reservableSpaces).not.toHaveBeenCalled();
  });

  it('applies status from the first page and ignores a previous page response', async () => {
    let resolveEarlier;
    const earlier = new Promise((resolve) => { resolveEarlier = resolve; });
    api.mySpaceReservations.mockImplementation(({ page, status }) => {
      if (status === 'confirmed') return Promise.resolve(result(1, 1, [item(9, 'Confirmed space', status)]));
      if (page === 2) return earlier;
      return Promise.resolve(result(1, 2, [item(1, 'Unfiltered first space')]));
    });
    openHistory();
    await screen.findByText('Unfiltered first space');
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    await waitFor(() => expect(api.mySpaceReservations).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 })));
    fireEvent.click(screen.getByRole('button', { name: '확정' }));
    await screen.findByText('Confirmed space');
    expect(api.mySpaceReservations).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'confirmed', page: 1 }));
    await act(async () => { resolveEarlier(result(2, 2, [item(2, 'Obsolete page')])); });
    expect(screen.queryByText('Obsolete page')).not.toBeInTheDocument();
    expect(screen.getByText('Confirmed space')).toBeInTheDocument();
  });

  it('returns to the last remaining filtered page after cancellation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(window, 'alert').mockImplementation(() => {});
    let cancelled = false;
    api.cancelSpaceReservation.mockImplementation(async () => { cancelled = true; });
    api.mySpaceReservations.mockImplementation(({ page }) => {
      const contents = page === 1
        ? [item(1, 'First requested space')] : cancelled ? [] : [item(2, 'Last requested space')];
      return Promise.resolve(result(page, cancelled ? 1 : 2, contents));
    });
    openHistory();
    await screen.findByText('First requested space');
    fireEvent.click(screen.getByRole('button', { name: '대기' }));
    await screen.findByText('First requested space');
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    await screen.findByText('Last requested space');
    fireEvent.click(screen.getByRole('button', { name: '예약 취소' }));
    await screen.findByText('First requested space');
    expect(api.cancelSpaceReservation).toHaveBeenCalledWith(2);
    expect(api.mySpaceReservations).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'requested', page: 1 }));
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
  });
});
