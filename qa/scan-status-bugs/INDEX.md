# Rapports de bug — Affichage des statuts de scan par space

Campagne de test QA (agent Quinn / BMAD) ciblant la partie la moins stable de l'app : l'affichage des statuts de scan par space, sur les scénarios **non-happy-path** (quitter/recharger la UI pendant un scan de fond puis se reconnecter ; pause + resélection + relance ; sémantique « En attente » / file d'attente).

- **Méthode** : cartographie du code (frontend Angular + backend Java, architecture hexagonale), puis vérification **adversariale** multi-agents (3 lentilles par bug : chemin de code, repro concret, contrat front/back). Seuls les bugs confirmés à la majorité sont retenus.
- **Dépôt** : `thibaut54/ai-sentinel` (fork). Label : `bug`.
- **Date** : 2026-07-08.

## Bugs confirmés (6)

| # | Issue | Sévérité | Confiance | Titre |
|---|-------|----------|-----------|-------|
| 1 | [#16](https://github.com/thibaut54/ai-sentinel/issues/16) | Haute | 2/3 | Reload pendant un scan « sélection » : perte du scope → spaces hors-scope affichés « En attente » |
| 2 | [#17](https://github.com/thibaut54/ai-sentinel/issues/17) | Haute | 3/3 | Reconnexion à un scan en cours : relance concurrente via resume → double comptage sévérité PII |
| 3 | [#18](https://github.com/thibaut54/ai-sentinel/issues/18) | Haute | 3/3 | Reprise d'un scan « sélection » en pause : re-scan de toute la base (scope non persisté serveur) |
| 4 | [#19](https://github.com/thibaut54/ai-sentinel/issues/19) | Moyenne | 2/3 | Relance d'un scan « sélection » : un space en file jamais démarré reste bloqué sur « En attente » |
| 5 | [#20](https://github.com/thibaut54/ai-sentinel/issues/20) | Haute | 3/3 | Scan « sélection » force les autres spaces interrompus à COMPLETED : « OK/100 % » masque des PII |
| 6 | [#21](https://github.com/thibaut54/ai-sentinel/issues/21) | Basse | 3/3 | Reload pendant l'inter-space : reconnexion sautée → scan affiché « inactif », badges figés |

## Cause racine transverse

Plusieurs bugs partagent la même origine : **le scope d'un scan « sélection » n'est jamais persisté** (ni côté serveur — le summary agrège le dernier checkpoint par space toutes campagnes confondues, sans notion de scope/queue — ni côté client au-delà d'un signal in-memory perdu au reload), et **le backend ne connaît aucun statut `PENDING`/`QUEUED`**. « En attente » est une pure dérivation frontend qui ne peut structurellement pas refléter la vraie file d'attente. Un 7ᵉ candidat (« summary non scopé au scan actif ») a été **réfuté** (0/3) car le frontend filtre bien via `currentScanSpaceKeys` tant qu'il n'est pas perdu — sa substance réelle est intégrée aux issues #16 et #19.

## Note de reproduction

Les reproductions sont dérivées d'une analyse de code vérifiée (les tests E2E locaux de ce projet sont sujets à des artefacts d'environnement). Chaque rapport fournit des étapes manuelles précises pour rejouer le bug dans l'app.
