# Backlog SonarQube - ai-sentinel-pii-reporting-ui

> Backlog genere depuis SonarQube (http://localhost:9000), projet **ai-sentinel-pii-reporting-ui**.
> Genere le 2026-06-10. Perimetre : issues a l'etat **OPEN / CONFIRMED / REOPENED**.
> Couverture remontee : **64.9% (line 64.1%, branch 66.0%)**.
> Effort total estime par Sonar : **15min**. Total issues : **3**.

## Note couverture

La couverture s'affiche desormais correctement. Deux causes racines corrigees :

1. `sonar-project.properties` pointait sur `coverage/lcov.info` alors que Vitest ecrit le rapport dans `coverage/vitest/lcov.info` (cf. `vitest.config.ts` -> `reportsDirectory`). Chemin corrige.
2. `run-sonar.ps1` ne generait pas la couverture avant l'analyse. Il execute maintenant `pnpm exec ng test --coverage --watch=false` puis `sonar-scanner`, en un seul lancement.

## Synthese

| Severite | Nb |
|----------|----|
| MAJOR | 3 |

| Type | Nb |
|------|----|
| CODE_SMELL | 3 |

---

## Detail des issues (groupees par regle)

### `Web:S6819` - Prefer tag over ARIA role

**Severite** : MAJOR | **Occurrences** : 3 | **Effort** : 15min

Tags : _accessibility_

- [ ] `src/app/features/confluence-dashboard/components/space-scan-stats-popover/space-scan-stats-popover.component.html:10` (MAJOR) - Use <section> instead of the region role to ensure accessibility across all devices.
- [ ] `src/app/features/confluence-dashboard/components/space-scan-stats-popover/space-scan-stats-popover.component.html:15` (MAJOR) - Use <output> instead of the status role to ensure accessibility across all devices.
- [ ] `src/app/features/confluence-dashboard/components/space-scan-stats-popover/space-scan-stats-popover.component.html:21` (MAJOR) - Use <output> instead of the status role to ensure accessibility across all devices.

