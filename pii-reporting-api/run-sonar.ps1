#!/usr/bin/env pwsh
# Script PowerShell pour exécuter l'analyse SonarQube du module Java pii-reporting-api
if (-not $Env:SONAR_QUBE_TOKEN) {
    Write-Error "La variable d'environnement SONAR_QUBE_TOKEN n'est pas définie"
    exit 1
}

Write-Host "Exécution des tests unitaires avec coverage (JaCoCo)..." -ForegroundColor Green
mvn clean verify jacoco:report

if ($LASTEXITCODE -ne 0) {
    Write-Error "Les tests ont échoué"
    exit 1
}

Write-Host "Tests réussis ! Démarrage de l'analyse SonarQube..." -ForegroundColor Green
mvn sonar:sonar "-Dsonar.token=$Env:SONAR_QUBE_TOKEN"
