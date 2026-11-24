import { fetchWithAuthRetry } from '../../../app/http/apiBase';
import { tokenStore } from '../../../app/http/tokenStore';

function query(values = {}) {
  const entries = Object.entries(values).filter(([, value]) => value !== undefined && value !== null && value !== '');
  return entries.length ? `?${new URLSearchParams(entries)}` : '';
}

async function request(path, { method = 'GET', body, signal } = {}) {
  const multipart = body instanceof FormData;
  const token = tokenStore.getAccess();
  const response = await fetchWithAuthRetry(path, {
    method, signal, credentials: 'same-origin', cache: 'no-store',
    headers: {
      ...(multipart ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : multipart ? body : JSON.stringify(body),
  }, { auth: true });
  if (response.status === 204) return null;
  const type = response.headers.get('content-type') || '';
  const payload = type.includes('application/json') ? await response.json().catch(() => null) : null;
  if (!response.ok || payload?.success === false) {
    const error = new Error(payload?.message || '요청을 처리하지 못했습니다. 다시 시도해주세요.');
    error.status = response.status;
    error.errorCode = payload?.errorCode;
    throw error;
  }
  if (payload == null) throw new Error('응답을 확인하지 못했습니다. 다시 시도해주세요.');
  return Object.hasOwn(payload, 'success') ? payload.data : payload;
}

function resourceId(value) {
  const id = Number(value);
  if (!Number.isSafeInteger(id) || id <= 0) throw new Error('대상 번호를 확인해주세요.');
  return id;
}

function resourceType(value) {
  const type = String(value).toUpperCase();
  if (!['BUILDING', 'ROOM'].includes(type)) throw new Error('대상 종류를 확인해주세요.');
  return type;
}

const collection = (name, id) => `/owner/${name}${id == null ? '' : `/${resourceId(id)}`}`;

export const ownerApi = {
  getMyApplication: (signal) => request('/users/me/owner-application', { signal }),
  apply: (applicationNote) => request('/users/me/owner-application', { method: 'POST', body: { applicationNote } }),
  getAssetClaims: (signal) => request('/users/me/asset-claims', { signal }),
  claimAsset: ({ resourceType: type, resourceId: id, proofReference }) => request('/users/me/asset-claims', {
    method: 'POST', body: { resourceType: resourceType(type), resourceId: resourceId(id), proofReference },
  }),
  getApplications: (status = 'PENDING', signal) => request(`/admin/owner-applications${query({ status })}`, { signal }),
  reviewApplication: (userId, approved, reviewNote, expectedUpdatedAt) => request(
    `/admin/owner-applications/${encodeURIComponent(userId)}/${approved ? 'approve' : 'reject'}`,
    { method: 'POST', body: { reviewNote, expectedUpdatedAt } }
  ),
  getAllAssetClaims: (status = 'PENDING', signal) => request(`/admin/asset-claims${query({ status })}`, { signal }),
  reviewAssetClaim: (claim, approved, reviewNote) => request(
    `/admin/asset-claims/${resourceType(claim.resourceType)}/${resourceId(claim.membershipId)}/${approved ? 'approve' : 'reject'}`,
    { method: 'POST', body: { reviewNote, expectedUpdatedAt: claim.updatedAt } }
  ),
  getBuildings: (params = {}) => request(`${collection('buildings')}${query(params)}`),
  getBuildingDetail: (id) => request(collection('buildings', id)),
  createBuilding: (formData) => request(collection('buildings'), { method: 'POST', body: formData }),
  updateBuilding: (id, formData) => request(collection('buildings', id), { method: 'PUT', body: formData }),
  deleteBuilding: (id) => request(collection('buildings', id), { method: 'DELETE' }),
  getRooms: (params = {}) => request(`${collection('rooms')}${query(params)}`),
  getRoomDetail: (id) => request(collection('rooms', id)),
  createRoom: (formData) => request(collection('rooms'), { method: 'POST', body: formData }),
  updateRoom: (id, formData) => request(collection('rooms', id), { method: 'PUT', body: formData }),
  deleteRoom: (id) => request(collection('rooms', id), { method: 'DELETE' }),
  getSpaces: (params = {}) => request(`${collection('spaces')}${query(params)}`),
  getSpaceDetail: (id) => request(collection('spaces', id)),
  createSpace: (formData) => request(collection('spaces'), { method: 'POST', body: formData }),
  updateSpace: (id, formData) => request(collection('spaces', id), { method: 'PUT', body: formData }),
  deleteSpace: (id) => request(collection('spaces', id), { method: 'DELETE' }),
  getPropertyDashboard: (buildingId) => request(`/owner/property-dashboard${query({ buildingId })}`),
  getResidents: () => request('/owner/residents'),
  getContracts: (params = {}) => request(`/owner/contracts${query(params)}`),
  getContractById: (id) => request(`/owner/contracts/${resourceId(id)}`),
  updateContract: (id, { contractStatus, moveinAt, pdfFile } = {}) => {
    const form = new FormData();
    if (contractStatus != null) form.append('contractStatus', contractStatus);
    if (moveinAt != null) form.append('moveinAt', moveinAt);
    if (pdfFile != null) form.append('pdfFile', pdfFile);
    return request(`/owner/contracts/${resourceId(id)}`, { method: 'PUT', body: form });
  },
  tourReservations: (params = {}) => request(`/owner/tour-reservations${query(params)}`),
  changeTourStatus: (id, status) => request(`/owner/tour-reservations/status/${resourceId(id)}${query({ status })}`, { method: 'PUT' }),
  spaceReservations: (params = {}) => request(`/owner/space-reservations${query(params)}`),
  spaceReservationDetail: (id) => request(`/owner/space-reservations/${resourceId(id)}`),
  confirmSpaceReservation: (id) => request(`/owner/space-reservations/${resourceId(id)}/confirm`, { method: 'PUT' }),
  endSpaceReservation: (id) => request(`/owner/space-reservations/${resourceId(id)}/end`, { method: 'PUT' }),
  cancelSpaceReservation: (id, reason) => request(`/owner/space-reservations/${resourceId(id)}/cancel`, {
    method: 'PUT', body: reason ? { cancelReason: reason } : undefined,
  }),
  deleteFiles: (fileIds) => request('/owner/files', { method: 'DELETE', body: fileIds }),
};
