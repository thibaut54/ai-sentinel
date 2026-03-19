/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    reporters: ['default'],
    coverage: {
      provider: 'v8',
      reportsDirectory: './coverage/vitest',
      reporter: ['text', 'lcov'],
      thresholds: {
        lines: 50,
        functions: 25,
        branches: 45,
        statements: 50,
      },
    },
  },
});
