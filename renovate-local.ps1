#!/usr/bin/env pwsh
#
# Lance Renovate localement sur la fork via Docker (mode on-demand).
# Equivalent Windows/PowerShell de renovate-local.sh
#
#   .\renovate-local.ps1            -> DRY-RUN (defaut : ne cree RIEN, simule et logue)
#   .\renovate-local.ps1 -Live      -> cree reellement les branches / PRs sur GitHub
#
# Pre-requis : Docker en marche + gh CLI authentifie (ou un PAT fourni via $env:RENOVATE_TOKEN).
#
# Variables d'env utiles :
#   RENOVATE_REPO     defaut "thibaut54/ai-sentinel"
#   RENOVATE_GH_USER  compte gh dont on prend le token (defaut "thibautvuillaume" = read-only,
#                     suffisant pour le dry-run ; pour -Live il faut un compte avec DROIT D'ECRITURE,
#                     ex: $env:RENOVATE_GH_USER='thibaut54' apres "gh auth login")
#   RENOVATE_TOKEN    si defini, utilise directement ce token (PAT) et ignore gh
#
# IMPORTANT pour -Live : le renovate.json doit etre commite/pousse sur la branche main
# de la fork, sinon Renovate ouvrira une PR d'onboarding au lieu d'appliquer cette config.

[CmdletBinding()]
param(
    [switch]$Live
)

$ErrorActionPreference = 'Stop'

$repo   = if ($env:RENOVATE_REPO)    { $env:RENOVATE_REPO }    else { 'thibaut54/ai-sentinel' }
$ghUser = if ($env:RENOVATE_GH_USER) { $env:RENOVATE_GH_USER } else { 'thibaut54' }
$image  = 'renovate/renovate'

# Token : $env:RENOVATE_TOKEN explicite sinon via gh
$token = $env:RENOVATE_TOKEN
if (-not $token) {
    try { $token = (gh auth token -u $ghUser -h github.com 2>$null | Out-String).Trim() }
    catch { $token = $null }
}
if ([string]::IsNullOrWhiteSpace($token)) {
    Write-Error "Aucun token. Definis `$env:RENOVATE_TOKEN, ou authentifie gh pour '$ghUser' (gh auth login)."
    exit 1
}

try {
    $env:RENOVATE_TOKEN = $token

    if (-not $Live) {
        Write-Host ">> DRY-RUN sur $repo (aucune ecriture). Config = $PSScriptRoot\renovate.json"
        $env:RENOVATE_CONFIG = Get-Content -Raw (Join-Path $PSScriptRoot 'renovate.json')
        $dockerArgs = @(
            'run', '--rm',
            '-e', 'RENOVATE_TOKEN',
            '-e', 'RENOVATE_CONFIG',
            '-e', 'LOG_LEVEL=info',
            $image,
            '--platform=github', '--onboarding=false', '--require-config=optional',
            '--dry-run=full', $repo
        )
    }
    else {
        Write-Host ">> LIVE sur $repo : Renovate va CREER branches/PRs."
        Write-Host ">> Token = compte '$ghUser' (doit avoir le droit d'ecriture sur $repo)."
        Write-Host ">> Rappel : renovate.json doit etre sur la branche main de la fork."
        $dockerArgs = @(
            'run', '--rm',
            '-e', 'RENOVATE_TOKEN',
            '-e', 'LOG_LEVEL=info',
            $image, '--platform=github', $repo
        )
    }

    docker @dockerArgs
    exit $LASTEXITCODE
}
finally {
    # Hygiene : ne pas laisser le token / la config dans l'environnement de la session
    Remove-Item Env:\RENOVATE_TOKEN  -ErrorAction SilentlyContinue
    Remove-Item Env:\RENOVATE_CONFIG -ErrorAction SilentlyContinue
}
