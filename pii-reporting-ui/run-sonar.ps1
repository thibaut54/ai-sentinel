#!/usr/bin/env pwsh
# Script PowerShell pour exécuter sonar-scanner sur le projet Angular.
# Génère d'abord le rapport de couverture (lcov via Vitest) puis lance l'analyse.

if (-not $Env:SONAR_QUBE_TOKEN) {
    Write-Error "La variable d'environnement SONAR_QUBE_TOKEN n'est pas définie"
    exit 1
}

$lcovPath = "coverage/vitest/lcov.info"

Write-Host "Génération de la couverture (Vitest)..." -ForegroundColor Green
pnpm exec ng test --coverage --watch=false
$testExit = $LASTEXITCODE
if ($testExit -ne 0) {
    # On n'interrompt pas l'analyse : Vitest écrit le lcov avant d'échouer sur un seuil.
    # On avertit mais on continue tant que le rapport existe.
    Write-Warning "ng test --coverage a retourné un code non nul ($testExit) — poursuite si le lcov existe."
}

if (-not (Test-Path $lcovPath)) {
    Write-Error "Rapport de couverture introuvable : $lcovPath. L'analyse Sonar n'aurait aucune couverture à remonter."
    exit 1
}

Write-Host "Démarrage de sonar-scanner pour pii-reporting-ui (Angular)..." -ForegroundColor Green
sonar-scanner "-Dsonar.token=$Env:SONAR_QUBE_TOKEN"
exit $LASTEXITCODE
