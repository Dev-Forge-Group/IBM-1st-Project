import React from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../../../features/user/hooks/useAuth';
import { accountRoles, isMemberAccount, isStaffAccount } from '../../../shared/auth/accountAccess';

export default function RequireMember({ allowAnonymous = false }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) return <div style={{ padding: 24 }}>계정 정보를 확인하고 있습니다.</div>;
  if (isStaffAccount(user)) return <Navigate to="/admin" replace />;
  if (isMemberAccount(user)) return <Outlet />;
  if (allowAnonymous && (!user || accountRoles(user).includes('GUEST'))) return <Outlet />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  return <Navigate to="/" replace />;
}
