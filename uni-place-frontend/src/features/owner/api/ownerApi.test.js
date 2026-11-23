import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ownerApi } from './ownerApi';

const transport = vi.hoisted(() => vi.fn());
vi.mock('../../../app/http/apiBase', () => ({ fetchWithAuthRetry: transport }));

function response(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('owner workspace transport', () => {
  beforeEach(() => {
    transport.mockReset();
    localStorage.setItem('access_token', 'synthetic-owner-token');
    transport.mockImplementation(async () => response({ success: true, data: [] }));
  });
  afterEach(() => localStorage.clear());

  it('uses current authenticated transport and no-store for a private list', async () => {
    transport.mockResolvedValue(response({ success: true, data: { content: [], totalElements: 0 } }));
    expect(await ownerApi.getBuildings({ page: 1, size: 10, buildingNm: 'A & B', unused: undefined })).toEqual({ content: [], totalElements: 0 });
    const [path, options, policy] = transport.mock.lastCall;
    expect(path).toBe('/owner/buildings?page=1&size=10&buildingNm=A+%26+B');
    expect(options.headers.Authorization).toBe('Bearer synthetic-owner-token');
    expect(options.cache).toBe('no-store');
    expect(options.credentials).toBe('same-origin');
    expect(policy).toEqual({ auth: true });
  });

  it('preserves multipart boundaries and a selected building ID when saving a room', async () => {
    const form = new FormData(); form.append('buildingId', '31'); form.append('roomNo', '202');
    await ownerApi.updateRoom(42, form);
    expect(transport).toHaveBeenCalledWith('/owner/rooms/42', expect.objectContaining({ method: 'PUT', body: form }), { auth: true });
    expect(transport.mock.lastCall[1].headers).not.toHaveProperty('Content-Type');
  });

  it('serializes owner application and separate asset evidence using the canonical member endpoints', async () => {
    await ownerApi.apply('synthetic application');
    expect(transport.mock.lastCall[0]).toBe('/users/me/owner-application');
    expect(JSON.parse(transport.mock.lastCall[1].body)).toEqual({ applicationNote: 'synthetic application' });
    await ownerApi.claimAsset({ resourceType: 'room', resourceId: '42', proofReference: 'synthetic reference' });
    expect(transport.mock.lastCall[0]).toBe('/users/me/asset-claims');
    expect(JSON.parse(transport.mock.lastCall[1].body)).toEqual({ resourceType: 'ROOM', resourceId: 42, proofReference: 'synthetic reference' });
  });

  it('passes abort signals to status requests and keeps staff review on account-management endpoints', async () => {
    const controller = new AbortController();
    await ownerApi.getApplications('PENDING', controller.signal);
    expect(transport.mock.lastCall[0]).toBe('/admin/owner-applications?status=PENDING');
    expect(transport.mock.lastCall[1].signal).toBe(controller.signal);
    await ownerApi.reviewAssetClaim({ resourceType: 'BUILDING', membershipId: 7 }, false, 'synthetic rejection');
    expect(transport.mock.lastCall[0]).toBe('/admin/asset-claims/BUILDING/7/reject');
    expect(JSON.parse(transport.mock.lastCall[1].body)).toEqual({ reviewNote: 'synthetic rejection' });
    await ownerApi.reviewApplication('member/one', true, 'synthetic approval');
    expect(transport.mock.lastCall[0]).toBe('/admin/owner-applications/member%2Fone/approve');
  });

  it.each([0, -1, 1.5, 'bad', Number.MAX_SAFE_INTEGER + 1])('rejects unsafe resource ID %s without making a request', (id) => {
    expect(() => ownerApi.getRoomDetail(id)).toThrow('대상 번호');
    expect(transport).not.toHaveBeenCalled();
  });

  it('rejects unsupported asset types before transport', () => {
    expect(() => ownerApi.claimAsset({ resourceType: 'USER', resourceId: 1 })).toThrow('대상 종류');
    expect(transport).not.toHaveBeenCalled();
  });

  it('preserves a denied response status and code for a recoverable UI error', async () => {
    transport.mockResolvedValue(response({ success: false, message: 'Synthetic ownership denied', errorCode: 'OWNER_403' }, 403));
    await expect(ownerApi.getRoomDetail(1)).rejects.toMatchObject({ message: 'Synthetic ownership denied', status: 403, errorCode: 'OWNER_403' });
  });

  it('rejects an unexpected non-JSON success response and accepts an empty successful deletion', async () => {
    transport.mockResolvedValueOnce(new Response('<html>synthetic proxy failure</html>', { headers: { 'Content-Type': 'text/html' } }));
    await expect(ownerApi.getBuildings()).rejects.toThrow('응답을 확인');
    transport.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await expect(ownerApi.deleteBuilding(1)).resolves.toBeNull();
  });

  it('uses owner endpoints for business actions and never the retired admin business API', async () => {
    await ownerApi.getContracts(); await ownerApi.getResidents(); await ownerApi.tourReservations(); await ownerApi.spaceReservations();
    expect(transport.mock.calls.map(([path]) => path)).toEqual(['/owner/contracts', '/owner/residents', '/owner/tour-reservations', '/owner/space-reservations']);
  });
});
