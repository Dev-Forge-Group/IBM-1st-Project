import React, { useEffect } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import Header from '../../../app/layouts/components/Header';
import { useAuth } from '../../user/hooks/useAuth';
import { hasAccountRole } from '../../../shared/auth/accountAccess';
import layout from '../../admin/components/AdminShell.module.css';
import styles from './OwnerWorkspace.module.css';

export default function OwnerShell() {
  const { user, refresh } = useAuth();
  useEffect(() => { refresh?.(); }, [refresh]);
  const owner = hasAccountRole(user, 'OWNER');
  return <div className={layout.page}>
    <Header />
    <div className={layout.body}>
      <aside className={styles.sidebar}>
        <strong>소유주 업무</strong>
        <nav aria-label="소유주 메뉴">
          <NavLink to="/owner" end>소유주 신청·상태</NavLink>
          {owner && <>
            <NavLink to="/owner/property/buildings">건물·방 관리</NavLink>
            <NavLink to="/owner/claims">자산 소유 관계 확인</NavLink>
            <NavLink to="/owner/contracts">계약 관리</NavLink>
            <NavLink to="/owner/residents">입주자 조회</NavLink>
            <NavLink to="/owner/reservations/tours">투어 예약 관리</NavLink>
            <NavLink to="/owner/reservations/spaces">공용공간 예약 관리</NavLink>
          </>}
          <NavLink to="/me">이용자 업무로 전환</NavLink>
        </nav>
      </aside>
      <main className={layout.main}><Outlet /></main>
    </div>
  </div>;
}
