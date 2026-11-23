import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AuthorizedPropertyImage from './AuthorizedPropertyImage';

const getBlob = vi.hoisted(() => vi.fn());
vi.mock('../../file/api/fileApi', () => ({ fileApi: { getPrivateFileBlob: getBlob } }));
const blob = new Blob(['synthetic property'], { type: 'image/png' });
let create, revoke;
describe('authenticated draft property images', () => {
  beforeEach(() => {
    getBlob.mockReset(); getBlob.mockResolvedValue({ blob });
    create = vi.fn().mockReturnValue('blob:synthetic-property'); revoke = vi.fn();
    vi.stubGlobal('URL', class extends URL { static createObjectURL = create; static revokeObjectURL = revoke; });
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
  it('loads only an authorized file ID and releases the Blob URL on unmount', async () => {
    const rendered = render(<AuthorizedPropertyImage fileId={17} alt="Synthetic draft" />);
    const image = await screen.findByRole('img', { name: 'Synthetic draft' });
    expect(image.src).toBe('blob:synthetic-property'); expect(getBlob.mock.lastCall[0]).toBe(17);
    const signal = getBlob.mock.lastCall[1].signal;
    rendered.unmount(); expect(signal.aborted).toBe(true); expect(revoke).toHaveBeenCalledWith('blob:synthetic-property');
  });
  it('retries a denied or temporary image request without a raw storage URL fallback', async () => {
    getBlob.mockRejectedValueOnce(new Error('synthetic denial'));
    render(<AuthorizedPropertyImage fileId={17} alt="Synthetic draft" />);
    fireEvent.click(await screen.findByRole('button', { name: '이미지 다시 불러오기' }));
    await screen.findByRole('img'); expect(getBlob).toHaveBeenCalledTimes(2); expect(create).toHaveBeenCalledTimes(1);
  });
  it('aborts and ignores a late response when a draft image is closed', async () => {
    let resolve; const pending = new Promise((yes) => { resolve = yes; }); getBlob.mockReturnValue(pending);
    const rendered = render(<AuthorizedPropertyImage fileId={17} alt="Synthetic draft" />);
    const signal = getBlob.mock.lastCall[1].signal; rendered.unmount();
    await act(async () => { resolve({ blob }); await pending; });
    expect(signal.aborted).toBe(true); expect(create).not.toHaveBeenCalled();
  });
  it('releases the previous file and prevents its late response from replacing a new file', async () => {
    const rendered = render(<AuthorizedPropertyImage fileId={17} alt="Synthetic draft" />); await screen.findByRole('img');
    create.mockReturnValue('blob:second-property'); rendered.rerender(<AuthorizedPropertyImage fileId={18} alt="Synthetic draft" />);
    await waitFor(() => expect(screen.getByRole('img').src).toBe('blob:second-property'));
    expect(revoke).toHaveBeenCalledWith('blob:synthetic-property'); expect(getBlob.mock.lastCall[0]).toBe(18);
  });
});
