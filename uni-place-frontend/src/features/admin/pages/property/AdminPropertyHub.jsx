import React, { useEffect, useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { ownerApi } from '../../../owner/api/ownerApi';
import styles from './AdminPropertyHub.module.css';
import ownerStyles from '../../../owner/components/OwnerWorkspace.module.css';

export default function AdminPropertyHub() {
  const [summary, setSummary] = useState(null);
  const [error, setError] = useState('');
  const [version, setVersion] = useState(0);
  useEffect(() => {
    let active = true;
    setError('');
    Promise.all([ownerApi.getBuildings({ size: 1 }), ownerApi.getRooms({ size: 1 }), ownerApi.getSpaces({ size: 1 })])
      .then((values) => { if (active) setSummary(values.map((value) => Number(value?.totalElements ?? value?.content?.length ?? 0))); })
      .catch((cause) => { if (active) setError(cause.message); });
    return () => { active = false; };
  }, [version]);
  return <div className={styles.wrap}>
    <div className={styles.topRow}><div className={styles.titleArea}><span className={styles.eyebrow}>My property</span><h1 className={styles.title}>내 건물·방 관리</h1></div>
      <button type="button" className={ownerStyles.button} onClick={() => setVersion((value) => value + 1)}>현황 새로고침</button></div>
    <p>소유·관리 권한이 있는 자산만 표시합니다. 새 자산은 소유 관계 확인이 완료된 뒤 공개됩니다.</p>
    {error && <p role="alert" className={ownerStyles.error}>{error}</p>}
    {summary && <div className={ownerStyles.summary}>{['건물', '호실', '공용공간'].map((label, index) => <div key={label}>{label} <strong>{summary[index]}</strong></div>)}</div>}
    <div className={styles.tabs}>
      {[['buildings', '건물'], ['rooms', '방'], ['spaces', '공용공간']].map(([path, label]) => <NavLink key={path} to={`/owner/property/${path}`} className={({ isActive }) => `${styles.tab} ${isActive ? styles.tabActive : ''}`}>{label}</NavLink>)}
    </div><div className={styles.panel}><Outlet /></div>
  </div>;
}
