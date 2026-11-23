import React, { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ownerApi } from '../api/ownerApi';
import { useAuth } from '../../user/hooks/useAuth';
import styles from '../components/OwnerWorkspace.module.css';

const STATUS = { PENDING: '확인 중', ACTIVE: '확인 완료', REJECTED: '반려', REVOKED: '권한 회수' };

export default function OwnerAssetClaims() {
  const { refresh } = useAuth();
  const [searchParams] = useSearchParams();
  const [claims, setClaims] = useState([]);
  const [form, setForm] = useState(() => {
    const type = searchParams.get('resourceType');
    const id = searchParams.get('resourceId') || '';
    return { resourceType: type === 'ROOM' ? 'ROOM' : 'BUILDING', resourceId: /^[1-9]\d*$/.test(id) && Number.isSafeInteger(Number(id)) ? id : '', proofReference: '' };
  });
  const [version, setVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  useEffect(() => {
    const controller = new AbortController(); let active = true;
    setLoading(true); setError('');
    ownerApi.getAssetClaims(controller.signal).then((data) => { if (active) setClaims(Array.isArray(data) ? data : []); })
      .catch((cause) => { if (active) setError(cause.message); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; controller.abort(); };
  }, [version]);
  async function submit(event) {
    event.preventDefault(); if (saving) return;
    if (!form.proofReference.trim()) { setError('소유 관계를 확인할 수 있는 참고 정보를 입력해주세요.'); return; }
    setSaving(true); setError(''); setNotice('');
    try {
      await ownerApi.claimAsset({ ...form, proofReference: form.proofReference.trim() });
      setNotice('소유 관계 확인 요청이 접수되었습니다. 확인이 완료되면 공개·관리 권한에 반영됩니다.');
      setVersion((value) => value + 1);
    } catch (cause) { setError(cause.message); }
    finally { setSaving(false); }
  }
  async function checkStatus() {
    setError('');
    try {
      await refresh();
      setVersion((value) => value + 1);
    } catch (cause) { setError(cause.message); }
  }
  return <section className={styles.panel}>
    <h1>자산 소유 관계 확인</h1>
    <p>건물이나 호실별 소유·관리 권한을 요청합니다. 새로 등록한 자산도 확인 정보를 제출해야 공개됩니다.</p>
    {error && <p role="alert" className={styles.error}>{error}</p>}
    {notice && <p role="status" className={styles.notice}>{notice}</p>}
    <form className={styles.form} onSubmit={submit}>
      <label>대상 종류<select value={form.resourceType} onChange={(event) => setForm({ ...form, resourceType: event.target.value })} disabled={saving}><option value="BUILDING">건물</option><option value="ROOM">호실</option></select></label>
      <label>대상 번호<input type="number" min="1" step="1" required value={form.resourceId} onChange={(event) => setForm({ ...form, resourceId: event.target.value })} disabled={saving} /></label>
      <label>확인 참고 정보<textarea required maxLength={1000} value={form.proofReference} onChange={(event) => setForm({ ...form, proofReference: event.target.value })} disabled={saving} /></label>
      <button className={styles.button} type="submit" disabled={saving}>{saving ? '요청 중…' : '소유 관계 확인 요청'}</button>
    </form>
    <div className={styles.actions}><button className={styles.button} type="button" onClick={checkStatus} disabled={loading || saving}>목록·권한 다시 확인</button></div>
    {loading ? <p role="status">확인 요청을 불러오고 있습니다.</p> : <div className={styles.cards}>
      {claims.length === 0 && <p>확인 요청이 없습니다.</p>}
      {claims.map((claim) => <article className={styles.card} key={`${claim.resourceType}-${claim.membershipId}`}>
        <strong>{claim.resourceType === 'ROOM' ? '호실' : '건물'} #{claim.resourceId} {claim.resourceName}</strong>
        <p><span className={styles.badge}>{STATUS[claim.status] || claim.status}</span></p>
        <p>{claim.proofReference || '소유 관계 확인 정보를 제출해주세요.'}</p>
        {claim.reviewNote && <p>확인 의견: {claim.reviewNote}</p>}
        {['PENDING', 'REJECTED', 'REVOKED'].includes(claim.status) && <button className={styles.button} type="button" onClick={() => setForm({ resourceType: claim.resourceType, resourceId: String(claim.resourceId), proofReference: claim.proofReference || '' })}>이 자산의 확인 정보 입력</button>}
      </article>)}
    </div>}
  </section>;
}
