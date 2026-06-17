#!/usr/bin/env bash
#
# Lance Renovate localement sur la fork via Docker (mode on-demand).
#
#   ./renovate-local.sh            -> DRY-RUN (defaut : ne cree RIEN, simule et logue)
#   ./renovate-local.sh --live     -> cree reellement les branches / PRs sur GitHub
#
# Pre-requis : Docker en marche + gh CLI authentifie (ou un PAT fourni via RENOVATE_TOKEN).
#
# Variables d'env utiles :
#   RENOVATE_REPO     defaut "thibaut54/ai-sentinel"
#   RENOVATE_GH_USER  compte gh dont on prend le token (defaut "thibautvuillaume" = read-only,
#                     suffisant pour le dry-run ; pour --live il faut un compte avec DROIT D'ECRITURE,
#                     ex: RENOVATE_GH_USER=thibaut54 apres "gh auth login")
#   RENOVATE_TOKEN    si defini, utilise directement ce token (PAT) et ignore gh
#
# IMPORTANT pour --live : le renovate.json doit etre commite/pousse sur la branche main
# de la fork, sinon Renovate ouvrira une PR d'onboarding au lieu d'appliquer cette config.

set -euo pipefail

REPO="${RENOVATE_REPO:-thibaut54/ai-sentinel}"
GH_USER="${RENOVATE_GH_USER:-thibaut54}"
IMAGE="renovate/renovate"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MODE="dry"
[ "${1:-}" = "--live" ] && MODE="live"

# Token : RENOVATE_TOKEN explicite sinon via gh
TOKEN="${RENOVATE_TOKEN:-$(gh auth token -u "$GH_USER" -h github.com 2>/dev/null || true)}"
if [ -z "${TOKEN:-}" ]; then
  echo "ERREUR: aucun token. Definis RENOVATE_TOKEN, ou authentifie gh pour '$GH_USER'." >&2
  exit 1
fi

if [ "$MODE" = "dry" ]; then
  echo ">> DRY-RUN sur $REPO (aucune ecriture). Config = $SCRIPT_DIR/renovate.json"
  RENOVATE_CONFIG="$(cat "$SCRIPT_DIR/renovate.json")" \
  RENOVATE_TOKEN="$TOKEN" \
  docker run --rm -e RENOVATE_TOKEN -e RENOVATE_CONFIG -e LOG_LEVEL=info \
    "$IMAGE" \
    --platform=github --onboarding=false --require-config=optional \
    --dry-run=full "$REPO"
else
  echo ">> LIVE sur $REPO : Renovate va CREER branches/PRs."
  echo ">> Token = compte '$GH_USER' (doit avoir le droit d'ecriture sur $REPO)."
  echo ">> Rappel : renovate.json doit etre sur la branche main de la fork."
  RENOVATE_TOKEN="$TOKEN" \
  docker run --rm -e RENOVATE_TOKEN -e LOG_LEVEL=info \
    "$IMAGE" --platform=github "$REPO"
fi
