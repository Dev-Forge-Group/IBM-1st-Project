import React, { useEffect, useState } from 'react';
import { ownerApi } from '../api/ownerApi';
import styles from '../components/OwnerWorkspace.module.css';

function ReviewCard({ item, asset, onReviewed }) {
  const [note, setNote] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  async function review(approved) {
    if (saving) return;
    if (!note.trim()) { setError('확인 근거와 처리 의견을 입력해주세요.'); return; }
    setSaving(true); setError('');
    try {
      if (asset) await ownerApi.reviewAssetClaim(item, approved, note.trim());
      else await ownerApi.reviewApplication(item.userId, approved, note.trim());
      onReviewed();
    } catch (cause) { setError(cause.message); }
    finally { setSaving(false); }
  }
  return <article className={styles.card}>
    <strong>{item.userNm || item.userId} ({item.userId})</strong>
    {asset && <p>{item.resourceType === 'ROOM' ? '호실' : '건물'} #{item.resourceId} {item.resourceName}</p>}
    <p>상태: {item.status}</p>
    <p>{asset ? item.proofReference || '확인 참고 정보가 없습니다.' : item.applicationNote}</p>
    {item.reviewNote && <p>이전 처리 의견: {item.reviewNote}</p>}
    <label>처리 의견<textarea aria-label={`처리 의견 ${asset ? `${item.resourceType}-${item.membershipId}` : item.userId}`} maxLength={2000} value={note} onChange={(event) => setNote(event.target.value)} disabled={saving} /></label>
    {error && <p role="alert" className={styles.error}>{error}</p>}
    <div className={styles.actions}>
      <button className={styles.button} type="button" onClick={() => review(true)} disabled={saving || (asset && !item.proofReference)}>{saving ? '처리 중…' : '확인·승인'}</button>
      <button className={styles.button} type="button" onClick={() => review(false)} disabled={saving}>{item.status === 'ACTIVE' || item.status === 'APPROVED' ? '권한 회수' : '반려'}</button>
    </div>
  </article>;
}

export default function OwnerApprovalManagement() {
  const [tab, setTab] = useState('applications');
  const [status, setStatus] = useState('PENDING');
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [version, setVersion] = useState(0);
  useEffect(() => {
    const controller = new AbortController(); let active = true;
    setLoading(true); setError(''); setItems([]);
    const fetcher = tab === 'claims' ? ownerApi.getAllAssetClaims : ownerApi.getApplications;
    fetcher(status, controller.signal).then((data) => { if (active) setItems(Array.isArray(data) ? data : []); })
      .catch((cause) => { if (active) setError(cause.message); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; controller.abort(); };
  }, [tab, status, version]);
  function switchTab(next) { setTab(next); setStatus('PENDING'); }
  return <section className={styles.panel}>
    <h1>소유주·자산 권한 확인</h1>
    <p>계정의 소유주 자격과 건물·호실별 접근 권한을 확인합니다. 신청 내용과 확인 근거를 검토한 뒤 처리 의견을 남겨주세요.</p>
    <div className={styles.tabs} role="group" aria-label="확인 요청 종류">
      <button className={styles.button} type="button" aria-pressed={tab === 'applications'} onClick={() => switchTab('applications')}>소유주 신청</button>
      <button className={styles.button} type="button" aria-pressed={tab === 'claims'} onClick={() => switchTab('claims')}>자산 소유 관계</button>
    </div>
    <label>상태 <select className={styles.filter} value={status} onChange={(event) => setStatus(event.target.value)}>
      {(tab === 'claims' ? ['PENDING', 'ACTIVE', 'REJECTED', 'REVOKED'] : ['PENDING', 'APPROVED', 'REJECTED']).map((value) => <option key={value} value={value}>{value}</option>)}
    </select></label>
    <button className={styles.button} type="button" onClick={() => setVersion((value) => value + 1)} disabled={loading}>새로고침</button>
    {error && <p role="alert" className={styles.error}>{error}</p>}
    {loading ? <p role="status">확인 요청을 불러오고 있습니다.</p> : <div className={styles.cards}>
      {items.length === 0 && !error && <p>선택한 상태의 요청이 없습니다.</p>}
      {items.map((item) => <ReviewCard key={`${tab}-${item.resourceType || ''}-${item.membershipId || item.userId}`} item={item} asset={tab === 'claims'} onReviewed={() => setVersion((value) => value + 1)} />)}
    </div>}
  </section>;
}
