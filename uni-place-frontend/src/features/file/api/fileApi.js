// features/file/api/fileApi.js
import { fetchWithAuthRetry } from '../../../app/http/apiBase';

const API = '/api';

function getToken() {
  return (
    localStorage.getItem('access_token') ||
    localStorage.getItem('accessToken') ||
    ''
  );
}

async function parseResponse(res) {
  if (res.status === 204) return null;
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) return res.json().catch(() => null);
  return res.text().catch(() => null);
}

function unwrap(res, payload) {
  const api =
    payload && typeof payload === 'object' && 'success' in payload
      ? payload
      : null;
  if (!res.ok || (api && api.success === false)) {
    const msg =
      (api && api.message) ||
      (payload && payload.message) ||
      '파일 API 요청에 실패했습니다.';
    const err = new Error(msg);
    err.status = res.status;
    err.errorCode = api?.errorCode;
    throw err;
  }
  return api ? api.data : payload;
}

/**
 * 백엔드가 반환하는 /files/{id}/view → /api/files/{id}/view 변환
 */
export function toApiImageUrl(url) {
  if (!url) return null;
  if (url.startsWith('http://') || url.startsWith('https://')) return url;
  if (url.startsWith('/api/')) return url;
  return `/api${url.startsWith('/') ? url : `/${url}`}`;
}

export function toStableFileViewUrl(file) {
  if (!file) return null;
  if (file.fileId != null) return `${API}/files/${file.fileId}/view`;
  return toApiImageUrl(file.viewUrl || file.adminViewUrl || '');
}

const PRIVATE_EXTENSIONS = {
  'image/png': 'png', 'image/jpeg': 'jpg', 'image/gif': 'gif', 'image/webp': 'webp',
  'application/pdf': 'pdf', 'application/octet-stream': 'bin',
};

function privateFileId(value) {
  const id = typeof value === 'number' ? value
    : typeof value === 'string' && /^[1-9][0-9]*$/.test(value) ? Number(value) : NaN;
  if (!Number.isInteger(id) || id <= 0 || id > 2147483647) {
    throw new Error('계약서 파일 번호가 올바르지 않습니다.');
  }
  return id;
}

/** Download only a fetched blob; callers may release it sooner on close/unmount. */
export function savePrivateFileBlob(blob, filename) {
  const safeName = typeof filename === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$/.test(filename)
    && !filename.includes('..') ? filename : 'contract-document.bin';
  const url = URL.createObjectURL(blob);
  let released = false;
  let timer;
  const release = () => {
    if (released) return;
    released = true;
    if (timer != null) clearTimeout(timer);
    URL.revokeObjectURL(url);
  };
  let anchor;
  try {
    anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = safeName;
    document.body.appendChild(anchor);
    anchor.click();
    // Let the browser begin reading the download before releasing its URL.
    timer = setTimeout(release, 0);
    return release;
  } catch (error) {
    release();
    throw error;
  } finally {
    anchor?.remove();
  }
}

export const fileApi = {
  /**
   * GET /files?fileParentType=&fileParentId=
   * 삭제되지 않은 파일 목록 (CONTRACT는 인증 요청)
   */
  getFiles: async (fileParentType, fileParentId) => {
    const url = `${API}/files?fileParentType=${encodeURIComponent(fileParentType)}&fileParentId=${fileParentId}`;
    const privateContract = String(fileParentType).trim().toUpperCase() === 'CONTRACT';
    const token = getToken();
    const res = privateContract
      ? await fetchWithAuthRetry(url, {
        credentials: 'same-origin', headers: token ? { Authorization: `Bearer ${token}` } : {},
      }, { auth: true })
      : await fetch(url);
    const payload = await parseResponse(res);
    return unwrap(res, payload);
  },

  /** CONTRACT documents always pass through the authenticated file API, never a supplied storage URL. */
  getPrivateFileBlob: async (fileId, { download = false, signal } = {}) => {
    const id = privateFileId(fileId);
    const token = getToken();
    const res = await fetchWithAuthRetry(`/files/${id}/${download ? 'download' : 'view'}`, {
      credentials: 'same-origin', cache: 'no-store', signal,
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    }, { auth: true });
    if (!res.ok) {
      unwrap(res, await parseResponse(res));
    }
    const type = (res.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
    const extension = Object.hasOwn(PRIVATE_EXTENSIONS, type) ? PRIVATE_EXTENSIONS[type] : null;
    if (!extension) {
      const error = new Error('계약서 파일 응답 형식이 올바르지 않습니다.');
      error.status = 502;
      throw error;
    }
    return { blob: await res.blob(), filename: `contract-file-${id}.${extension}` };
  },

  /**
   * GET /files/admin?fileParentType=&fileParentId=
   * 삭제된 파일 포함 (관리자 전용)
   */
  getFilesAdmin: async (fileParentType, fileParentId) => {
    const token = getToken();
    const url = `${API}/files/admin?fileParentType=${encodeURIComponent(fileParentType)}&fileParentId=${fileParentId}`;
    const res = await fetch(url, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    const payload = await parseResponse(res);
    return unwrap(res, payload);
  },

  /**
   * POST /files  multipart/form-data
   * { fileParentType, fileParentId, files[] }
   */
  upload: async (fileParentType, fileParentId, files = []) => {
    const token = getToken();
    const fd = new FormData();
    fd.append('fileParentType', fileParentType);
    fd.append('fileParentId', String(fileParentId));
    files.forEach((f) => fd.append('files', f));
    const res = await fetch(`${API}/files`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: fd,
    });
    const payload = await parseResponse(res);
    return unwrap(res, payload);
  },

  /**
   * DELETE /files/admin  body: [fileId, ...]
   */
  deleteFiles: async (fileIds = []) => {
    if (!fileIds.length) return null;
    const token = getToken();
    const res = await fetch(`${API}/files/admin`, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(fileIds),
    });
    const payload = await parseResponse(res);
    return unwrap(res, payload);
  },
};
