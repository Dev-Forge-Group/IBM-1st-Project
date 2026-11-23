import React from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../../../features/user/hooks/useAuth';
import { hasAccountRole } from '../../../shared/auth/accountAccess';

export default function RequireRole({ allow = [] }) {
  const { user, loading } = useAuth();

  if (loading)
    return <div style={{ padding: 24, fontWeight: 700 }}>로딩중...</div>;
  if (!user) return <Navigate to="/login" replace />;

  const ok = allow.some((role) => hasAccountRole(user, role));

  if (!ok) return <Navigate to="/" replace />;

  return <Outlet />;
}
