import type { Config } from 'jest';

const config: Config = {
  preset: 'jest-preset-angular',
  testEnvironment: 'jsdom',
  setupFilesAfterEnv: ['<rootDir>/setup-jest.ts'],
  testMatch: ['**/?(*.)+(spec).ts'],
  testPathIgnorePatterns: ['<rootDir>/node_modules/', '<rootDir>/dist/', '<rootDir>/e2e/'],

  // Permet de transformer certains modules ESM dans node_modules (Angular, Transloco, utils, PrimeNG)
  transformIgnorePatterns: [
    String.raw`node_modules/(?!.*\.mjs$|@angular|@jsverse/transloco|@jsverse/utils|primeng|@primeuix)`,
  ],

  // Configuration de la couverture
  collectCoverage: false,
  coverageDirectory: '<rootDir>/coverage/jest',
  coveragePathIgnorePatterns: ['/node_modules/', '/dist/', String.raw`\.spec\.ts$`],

  reporters: ['default'],

  // Module path aliases and PrimeNG/Angular subpath resolution for Jest (which does not support package.json "exports")
  moduleNameMapper: {
    '^@app/(.*)$': '<rootDir>/src/app/$1',
    '^@environments/(.*)$': '<rootDir>/src/environments/$1',
    '^@angular/common/http/testing$': '<rootDir>/node_modules/@angular/common/fesm2022/http-testing.mjs',
    '^@angular/common/http$': '<rootDir>/node_modules/@angular/common/fesm2022/http.mjs',
    '^primeng/api$': '<rootDir>/node_modules/primeng/fesm2022/primeng-api.mjs',
    '^primeng/button$': '<rootDir>/node_modules/primeng/fesm2022/primeng-button.mjs',
    '^primeng/confirmdialog$': '<rootDir>/node_modules/primeng/fesm2022/primeng-confirmdialog.mjs',
    '^primeng/dialog$': '<rootDir>/node_modules/primeng/fesm2022/primeng-dialog.mjs',
    '^primeng/fieldset$': '<rootDir>/node_modules/primeng/fesm2022/primeng-fieldset.mjs',
    '^primeng/iconfield$': '<rootDir>/node_modules/primeng/fesm2022/primeng-iconfield.mjs',
    '^primeng/inputicon$': '<rootDir>/node_modules/primeng/fesm2022/primeng-inputicon.mjs',
    '^primeng/inputnumber$': '<rootDir>/node_modules/primeng/fesm2022/primeng-inputnumber.mjs',
    '^primeng/inputtext$': '<rootDir>/node_modules/primeng/fesm2022/primeng-inputtext.mjs',
    '^primeng/message$': '<rootDir>/node_modules/primeng/fesm2022/primeng-message.mjs',
    '^primeng/multiselect$': '<rootDir>/node_modules/primeng/fesm2022/primeng-multiselect.mjs',
    '^primeng/types/multiselect$': '<rootDir>/node_modules/primeng/fesm2022/primeng-types-multiselect.mjs',
    '^primeng/password$': '<rootDir>/node_modules/primeng/fesm2022/primeng-password.mjs',
    '^primeng/progressspinner$': '<rootDir>/node_modules/primeng/fesm2022/primeng-progressspinner.mjs',
    '^primeng/select$': '<rootDir>/node_modules/primeng/fesm2022/primeng-select.mjs',
    '^primeng/toast$': '<rootDir>/node_modules/primeng/fesm2022/primeng-toast.mjs',
    '^primeng/toggleswitch$': '<rootDir>/node_modules/primeng/fesm2022/primeng-toggleswitch.mjs',
  },
};

export default config;
