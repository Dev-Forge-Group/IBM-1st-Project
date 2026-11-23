import React, { useEffect, useState } from 'react';
import { fileApi } from '../../file/api/fileApi';

/** Draft property files use the authenticated file-ID proxy, including when stored on S3. */
export default function AuthorizedPropertyImage({ fileId, alt, ...props }) {
  const [source, setSource] = useState(null);
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController(); let active = true; let url;
    setSource(null); setFailed(false);
    fileApi.getPrivateFileBlob(fileId, { signal: controller.signal }).then(({ blob }) => {
      if (!active) return;
      url = URL.createObjectURL(blob); setSource({ fileId, url });
    }).catch(() => { if (active) setFailed(true); });
    return () => { active = false; controller.abort(); if (url) URL.revokeObjectURL(url); };
  }, [fileId, retry]);
  if (failed) return <button type="button" onClick={(event) => { event.stopPropagation(); setRetry((value) => value + 1); }}>이미지 다시 불러오기</button>;
  return source?.fileId === fileId ? <img {...props} src={source.url} alt={alt} /> : <span role="status">이미지 확인 중</span>;
}
