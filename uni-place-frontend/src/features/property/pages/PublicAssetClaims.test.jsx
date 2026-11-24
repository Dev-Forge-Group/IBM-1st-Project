import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import OwnerAssetClaims from '../../owner/pages/OwnerAssetClaims';
import BuildingDetail from './BuildingDetail';
import RoomDetail from './RoomDetail';

const { auth, propertyApi, ownerApi } = vi.hoisted(() => ({
  auth: { user: null, loading: false },
  propertyApi: { getBuildingDetail: vi.fn(), getRoomDetail: vi.fn(), getRooms: vi.fn(), getSpaces: vi.fn() },
  ownerApi: { getAssetClaims: vi.fn(), claimAsset: vi.fn() },
}));

vi.mock('../../user/hooks/useAuth', () => ({ useAuth: () => auth }));
vi.mock('../../../app/layouts/components/Header', () => ({ default: () => null }));
vi.mock('../../../app/layouts/components/Footer', () => ({ default: () => null }));
vi.mock('../../chat/config/chatConfig', () => ({ KAKAO_MAP_KEY: '', KMA_KEY: '' }));
vi.mock('../api/propertyApi', () => ({ propertyApi }));
vi.mock('../../owner/api/ownerApi', () => ({ ownerApi }));
vi.mock('../../review/api/reviewApi', () => ({
  reviewApi: {
    getRoomSummary: async () => null,
    getListByRoom: async () => ({ content: [], totalPages: 1 }),
  },
}));
vi.mock('../../contract/api/contractApi', () => ({ contractApi: { myContracts: async () => [] } }));

const owner = { accountKind: 'MEMBER', roles: ['CUSTOMER', 'OWNER'] };
const building = { buildingId: 31, buildingNm: '합성 시범 건물', buildingAddr: '합성 주소', files: [] };
const room = { roomId: 42, roomNo: '402', buildingId: 31, buildingNm: building.buildingNm, roomSt: 'available', verifiedRentalOwner: false, files: [] };

function visit(path) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Link to="/buildings/32">다른 건물</Link>
      <Routes>
        <Route path="/buildings/:buildingId" element={<BuildingDetail />} />
        <Route path="/rooms/:roomId" element={<RoomDetail />} />
        <Route path="/owner/claims" element={<OwnerAssetClaims />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('public asset links to ownership claims', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    auth.user = owner;
    auth.loading = false;
    propertyApi.getBuildingDetail.mockResolvedValue(building);
    propertyApi.getRoomDetail.mockResolvedValue(room);
    propertyApi.getRooms.mockResolvedValue({ content: [], totalPages: 1 });
    propertyApi.getSpaces.mockResolvedValue({ content: [], totalPages: 1 });
    ownerApi.getAssetClaims.mockResolvedValue([]);
  });
  afterEach(cleanup);

  it.each([
    ['/buildings/31', '이 건물의 소유 관계 확인 요청', 'BUILDING', 31],
    ['/rooms/42', '이 호실의 소유 관계 확인 요청', 'ROOM', 42],
  ])('opens the claim form for the displayed asset at %s', async (path, label, type, id) => {
    visit(path);
    fireEvent.click(await screen.findByRole('link', { name: label }));
    await screen.findByText('확인 요청이 없습니다.');
    expect(screen.getByLabelText('대상 종류')).toHaveValue(type);
    expect(screen.getByLabelText('대상 번호')).toHaveValue(id);
    expect(ownerApi.claimAsset).not.toHaveBeenCalled();
  });

  it.each([
    [null],
    [{ accountKind: 'MEMBER', roles: ['CUSTOMER'] }],
    [{ accountKind: 'STAFF', roles: ['ADMIN', 'OWNER'] }],
  ])('keeps unverified rooms viewable without offering claim or booking actions to %j', async (user) => {
    auth.user = user;
    visit('/rooms/42');
    expect(await screen.findByRole('button', { name: /계약하기/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /방 사전 방문 예약하기/ })).toBeDisabled();
    expect(screen.queryByRole('link', { name: /소유 관계 확인 요청/ })).not.toBeInTheDocument();
  });

  it('does not replace the current claim target when an earlier detail request finishes late', async () => {
    let resolveEarlier;
    propertyApi.getBuildingDetail.mockImplementationOnce(() => new Promise((resolve) => { resolveEarlier = resolve; }));
    propertyApi.getBuildingDetail.mockResolvedValueOnce({ ...building, buildingId: 32, buildingNm: '다음 합성 건물' });
    visit('/buildings/31');
    fireEvent.click(screen.getByRole('link', { name: '다른 건물' }));
    const name = '이 건물의 소유 관계 확인 요청';
    expect(await screen.findByRole('link', { name })).toHaveAttribute('href', '/owner/claims?resourceType=BUILDING&resourceId=32');
    await act(async () => { resolveEarlier(building); });
    expect(screen.getByRole('link', { name })).toHaveAttribute('href', '/owner/claims?resourceType=BUILDING&resourceId=32');
  });
});
