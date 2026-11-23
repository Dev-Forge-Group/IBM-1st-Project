import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ownerApi } from '../api/ownerApi';
import { useAuth } from '../../user/hooks/useAuth';
import { hasAccountRole } from '../../../shared/auth/accountAccess';
import styles from '../components/OwnerWorkspace.module.css';

const STATUS = { NONE: '신청 전', PENDING: '확인 중', APPROVED: '승인', REJECTED: '반려' };

export default function OwnerApplication() {
  const { user, refresh } = useAuth();
  const [application, setApplication] = useState(null);
  const [applicationNote, setApplicationNote] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [version, setVersion] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setLoading(true);
    setError('');
    ownerApi.getMyApplication(controller.signal).then((data) => {
      if (!active) return;
      setApplication(data);
      setApplicationNote((old) => old || data?.applicationNote || '');
    }).catch((cause) => { if (active) setError(cause.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; controller.abort(); };
  }, [version]);

  async function submit(event) {
    event.preventDefault();
    if (saving) return;
    if (!applicationNote.trim()) { setError('소유주 확인에 필요한 신청 내용을 입력해주세요.'); return; }
    setSaving(true); setError(''); setNotice('');
    try {
      const result = await ownerApi.apply(applicationNote.trim());
      setApplication(result);
      setNotice('소유주 신청이 접수되었습니다. 확인 결과를 기다려주세요.');
      await refresh?.();
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

  const status = application?.status || 'NONE';
  return <section className={styles.panel}>
    <h1>소유주 신청</h1>
    <p>이용자 계정을 유지하면서 소유주 업무를 함께 이용할 수 있습니다. 소유주 승인 뒤에도 각 건물·호실의 소유 관계를 별도로 확인합니다.</p>
    {loading && <p role="status">신청 상태를 확인하고 있습니다.</p>}
    {error && <p role="alert" className={styles.error}>{error}</p>}
    {notice && <p role="status" className={styles.notice}>{notice}</p>}
    {!loading && application && <>
      <p>현재 상태: <strong>{STATUS[status] || status}</strong></p>
      {application.reviewNote && <p className={styles.notice}>확인 의견: {application.reviewNote}</p>}
      {status === 'APPROVED' ? <p className={styles.notice}>소유주 확인이 완료되었습니다. 새 자산은 소유 관계 확인 후 공개됩니다.</p>
        : status === 'PENDING' ? <p className={styles.notice}>신청을 확인하고 있습니다. 결과 확인 후 다음 업무를 진행할 수 있습니다.</p>
          : <form onSubmit={submit} className={styles.form}>
            <label>신청 내용<textarea value={applicationNote} onChange={(event) => setApplicationNote(event.target.value)} maxLength={2000} required disabled={saving} /></label>
            <p className={styles.muted}>소유주 확인 방법과 담당자가 확인할 수 있는 참고 내용을 입력해주세요. 비밀번호는 입력하지 마세요.</p>
            <button className={styles.button} type="submit" disabled={saving}>{saving ? '신청 중…' : status === 'REJECTED' ? '다시 신청' : '소유주 신청하기'}</button>
          </form>}
    </>}
    <div className={styles.actions}>
      <button className={styles.button} type="button" onClick={checkStatus} disabled={loading || saving}>상태·권한 다시 확인</button>
      {hasAccountRole(user, 'OWNER') && <><Link to="/owner/property/buildings">내 건물·방 관리</Link><Link to="/owner/claims">자산 소유 관계 확인</Link></>}
      <Link to="/me">이용자 업무</Link>
    </div>
  </section>;
}
