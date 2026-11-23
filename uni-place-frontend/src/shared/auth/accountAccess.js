function normalizeRole(value) {
  const role = typeof value === 'object' && value ? value.authority : value;
  return String(role ?? '').trim().toUpperCase().replace(/^ROLE_/, '');
}

function suppliedRoles(user) {
  if (!user) return [];
  if (Array.isArray(user.roles)) return user.roles.map(normalizeRole).filter(Boolean);
  if (Array.isArray(user.authorities)) return user.authorities.map(normalizeRole).filter(Boolean);
  return [normalizeRole(user.userRole ?? user.role ?? user.userRl ?? user.user_role ?? user.authority)].filter(Boolean);
}

export function isStaffAccount(user) {
  if (!user) return false;
  const kind = String(user.accountKind ?? user.account_kind ?? '').toUpperCase();
  if (kind === 'STAFF') return true;
  if (kind === 'MEMBER') return false;
  return normalizeRole(user.userRole ?? user.role) === 'ADMIN' || suppliedRoles(user).includes('ADMIN');
}

/** Explicit role arrays are authoritative, including an empty array after revocation. */
export function accountRoles(user) {
  const roles = [...new Set(suppliedRoles(user))];
  if (isStaffAccount(user)) return roles.includes('ADMIN') ? ['ADMIN'] : [];
  return roles.filter((role) => role !== 'ADMIN');
}

export function hasAccountRole(user, role) {
  return accountRoles(user).includes(normalizeRole(role));
}

export function isMemberAccount(user) {
  return Boolean(user) && !isStaffAccount(user)
    && accountRoles(user).some((role) => ['CUSTOMER', 'USER', 'TENANT', 'OWNER'].includes(role));
}
