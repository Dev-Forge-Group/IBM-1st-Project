import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fileApi, toApiImageUrl } from './fileApi';
import * as files from './fileApi';

const { authenticated } = vi.hoisted(() => ({ authenticated: vi.fn() }));
vi.mock('../../../app/http/apiBase', () => ({ fetchWithAuthRetry: authenticated }));

function response({ status = 200, type = 'image/png', payload, blob = new Blob(['synthetic'], { type }) } = {}) {
  return {
    ok: status >= 200 && status < 300, status,
    headers: { get: (key) => key.toLowerCase() === 'content-type' ? type : 'attachment; filename="../../unsafe.html"' },
    json: vi.fn(async () => payload), text: vi.fn(async () => 'Synthetic file error'),
    blob: vi.fn(async () => blob),
  };
}

describe('private contract file transport', () => {
  beforeEach(() => { vi.resetAllMocks(); vi.stubGlobal('fetch', vi.fn()); localStorage.clear(); });
  afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); vi.unstubAllGlobals(); localStorage.clear(); });

  it.each([[false, 'view'], [true, 'download']])('uses a fixed authenticated endpoint for download=%s', async (download, path) => {
    const server = response();
    authenticated.mockResolvedValue(server);
    const signal = new AbortController().signal;
    const result = await fileApi.getPrivateFileBlob(23, { download, signal });
    const [url, options, policy] = authenticated.mock.lastCall;
    expect(url).toBe(`/files/23/${path}`);
    expect(options.credentials).toBe('same-origin');
    expect(options.signal).toBe(signal);
    expect(policy.auth).toBe(true);
    expect(result.blob).toBe(await server.blob());
    expect(result.filename).toBe('contract-file-23.png');
    expect(fetch).not.toHaveBeenCalled();
  });

  it.each([401, 403])('rejects HTTP%s without converting a JSON rejection to a file', async (status) => {
    const server = response({ status, type: 'application/json', payload: { success: false, message: 'Access denied', errorCode: 'AUTH_DENIED' } });
    authenticated.mockResolvedValue(server);
    await expect(fileApi.getPrivateFileBlob(23)).rejects.toMatchObject({ status });
    expect(server.blob).not.toHaveBeenCalled();
  });

  it.each(['application/json', 'text/html', 'constructor'])('rejects successful %s instead of displaying it as a document', async (type) => {
    const server = response({ type, payload: { success: true, data: null } });
    authenticated.mockResolvedValue(server);
    await expect(fileApi.getPrivateFileBlob(23)).rejects.toBeInstanceOf(Error);
    expect(server.blob).not.toHaveBeenCalled();
  });

  it.each([null, 0, -1, 1.5, 'https://storage.invalid/file', '../23', '23?token=fixture'])('does not request an invalid file identifier %s', async (id) => {
    await expect(fileApi.getPrivateFileBlob(id)).rejects.toBeInstanceOf(Error);
    expect(authenticated).not.toHaveBeenCalled();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('authenticates normalized CONTRACT listing while preserving public ROOM listing', async () => {
    const payload = { success: true, data: [] };
    authenticated.mockResolvedValue(response({ type: 'application/json', payload }));
    fetch.mockResolvedValue(response({ type: 'application/json', payload }));
    await fileApi.getFiles(' contract ', 7);
    expect(authenticated.mock.lastCall[2].auth).toBe(true);
    expect(fetch).not.toHaveBeenCalled();
    await fileApi.getFiles('ROOM', 8);
    expect(fetch.mock.calls.length).toBe(1);
    expect(authenticated.mock.calls.length).toBe(1);
    expect(toApiImageUrl('https://public.invalid/room.png')).toBe('https://public.invalid/room.png');
    expect(toApiImageUrl('/files/8/view')).toBe('/api/files/8/view');
  });

  it('uses the existing shared 401 refresh boundary before retrying a private file', async () => {
    const actual = await vi.importActual('../../../app/http/apiBase');
    authenticated.mockImplementation(actual.fetchWithAuthRetry);
    localStorage.setItem('access_token', 'expired-synthetic-access');
    localStorage.setItem('refresh_token', 'synthetic-refresh');
    localStorage.setItem('device_id', 'synthetic-device');
    fetch.mockResolvedValueOnce(response({ status: 401, type: 'application/json', payload: { success: false } }))
      .mockResolvedValueOnce(response({ type: 'application/json', payload: { success: true, data: {
        accessToken: 'fresh-synthetic-access', refreshToken: 'rotated-synthetic-refresh', deviceId: 'synthetic-device',
      } } })).mockResolvedValueOnce(response());
    await fileApi.getPrivateFileBlob(23);
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(['/api/files/23/view', '/api/auth/refresh', '/api/files/23/view']);
    expect(new Headers(fetch.mock.calls[2][1].headers).get('Authorization') === 'Bearer fresh-synthetic-access').toBe(true);
  });

  it('downloads only a blob URL, removes its temporary anchor and revokes once', () => {
    vi.useFakeTimers();
    const create = vi.fn(() => 'blob:synthetic-download');
    const revoke = vi.fn();
    const OriginalURL = URL;
    vi.stubGlobal('URL', class extends OriginalURL { static createObjectURL = create; static revokeObjectURL = revoke; });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    expect(typeof files.savePrivateFileBlob).toBe('function');
    const release = files.savePrivateFileBlob(new Blob(['synthetic']), '../../untrusted.html');
    const anchor = click.mock.instances[0];
    expect(anchor.getAttribute('href')).toBe('blob:synthetic-download');
    expect(anchor.getAttribute('download')).toBe('contract-document.bin');
    expect(anchor.isConnected).toBe(false);
    expect(revoke).not.toHaveBeenCalled();
    vi.runAllTimers();
    expect(revoke).toHaveBeenCalledWith('blob:synthetic-download');
    release();
    expect(revoke).toHaveBeenCalledTimes(1);
  });

  it('releases a download URL if the anchor fails before a download starts', () => {
    const create = vi.fn(() => 'blob:synthetic-download');
    const revoke = vi.fn();
    const OriginalURL = URL;
    vi.stubGlobal('URL', class extends OriginalURL { static createObjectURL = create; static revokeObjectURL = revoke; });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => { throw new Error('Synthetic click failure'); });
    expect(typeof files.savePrivateFileBlob).toBe('function');
    expect(() => files.savePrivateFileBlob(new Blob(['synthetic']), 'contract-file-23.png')).toThrow();
    expect(revoke).toHaveBeenCalledWith('blob:synthetic-download');
    expect(document.querySelector('a[download]')).toBeNull();
  });
});
