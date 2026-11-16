import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Testing Library only unmounts by itself when the test runner exposes global hooks.
afterEach(cleanup);
