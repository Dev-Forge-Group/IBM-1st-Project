// src/features/admin/components/AdminSidebar.jsx
import { useLocation, useNavigate } from 'react-router-dom';
import styles from './AdminSidebar.module.css';
import { adminMenu } from '../constants/adminMenu';
import { useAuth } from '../../user/hooks/useAuth';
import { hasAccountRole } from '../../../shared/auth/accountAccess';

export default function AdminSidebar() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user } = useAuth();

  const isActive = (path) => {
    if (path === '/admin') return location.pathname === '/admin';
    return location.pathname.startsWith(path);
  };

  const initials = (user?.userNm || user?.name || '관')[0];
  const roleLabel = hasAccountRole(user, 'ADMIN') ? '계정 관리자' : '운영 계정';

  return (
    <aside className={styles.sidebar}>
      {/* 프로필 카드 */}
      <div className={styles.profileCard}>
        <div className={styles.avatarWrap}>
          <div className={styles.avatar}>{initials}</div>
          <div>
            <div className={styles.profileName}>{user?.userNm || user?.name || '관리자'}</div>
            <div className={styles.profileEmail}>{user?.userEmail || ''}</div>
          </div>
        </div>
        <div className={styles.profileStats}>
          <div className={styles.profileStat}>
            <div className={styles.profileStatVal}>{roleLabel}</div>
            <div className={styles.profileStatLbl}>계정 유형</div>
          </div>
          {user?.userNickname && (
            <div className={styles.profileStat}>
              <div className={styles.profileStatVal}>{user.userNickname}</div>
              <div className={styles.profileStatLbl}>닉네임</div>
            </div>
          )}
        </div>
      </div>

      <div className={styles.sideBox}>
        <div className={styles.sideNavHeader}>ADMIN PANEL</div>
        {adminMenu.map((m) => (
          <button
            key={m.key}
            type="button"
            className={`${styles.sideItem} ${isActive(m.path) ? styles.sideItemActive : ''}`}
            onClick={() => navigate(m.path)}
          >
            <span className={styles.dot} />
            {m.label}
          </button>
        ))}
      </div>
    </aside>
  );
}
