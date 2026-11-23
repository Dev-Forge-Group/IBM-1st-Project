import React from 'react';
import { Link } from 'react-router-dom';
import styles from '../../owner/components/OwnerWorkspace.module.css';

export default function AdminInfo() {
  return <section className={styles.panel}>
    <h1>계정 관리</h1>
    <p>운영 계정으로 회원 상태와 소유주·자산 접근 권한을 관리합니다.</p>
    <div className={styles.actions}>
      <Link to="/admin/users">회원 계정 관리</Link>
      <Link to="/admin/owners">소유주·자산 권한 확인</Link>
    </div>
  </section>;
}
