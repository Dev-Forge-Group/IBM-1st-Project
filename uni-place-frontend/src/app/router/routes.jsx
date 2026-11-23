import React from 'react';
import { createBrowserRouter } from 'react-router-dom';
import App from '../../App';

// Both entrypoints use the same role and account-kind boundaries.
const router = createBrowserRouter([{ path: '*', element: <App /> }]);
export default router;
