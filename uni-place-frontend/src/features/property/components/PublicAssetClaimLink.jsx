import { Link } from 'react-router-dom';
import { useAuth } from '../../user/hooks/useAuth';
import { hasAccountRole } from '../../../shared/auth/accountAccess';
import styles from './PublicAssetClaimLink.module.css';

export default function PublicAssetClaimLink({ resourceType, resourceId }) {
  const { user, loading } = useAuth();
  if (loading || !hasAccountRole(user, 'OWNER') || !Number.isInteger(resourceId) || resourceId <= 0) {
    return null;
  }

  const asset = resourceType === 'BUILDING' ? '건물' : '호실';

  return (
    <Link
      className={styles.link}
      to={`/owner/claims?resourceType=${resourceType}&resourceId=${resourceId}`}
    >
      이 {asset}의 소유 관계 확인 요청
    </Link>
  );
}
