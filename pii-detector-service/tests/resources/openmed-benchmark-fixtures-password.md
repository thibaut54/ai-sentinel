# OpenMed — Fixtures benchmark : True / False positives

**Cible** : 24 types PII activés par défaut côté détecteur OpenMed (v1.2.0).
**Format** : pour chaque type, 10 cas devant être détectés (TP) et 10 cas pièges plausibles dans le même contexte mais qui **NE doivent PAS** être étiquetés sous ce type.

Toutes les données ci-dessous sont **synthétiques** (générées pour le benchmark, ne correspondent à aucune personne / institution réelle).

> Utilisation : alimenter ces phrases dans un test paramétré sur `OpenMedDetector.detect_pii()`. Mesurer precision = TP / (TP + FP_classés_comme_ce_type) et recall = TP / 10.

---


## Auth

### PASSWORD

#### True positives
1. `My password is Hunter2!@.`
2. `Use password Sup3r$ecret to login.`
3. `Password reset to TempPass#2024.`
4. `Old password was Welcome123!.`
5. `Le mot de passe est Été2025@CH.`
6. `Set the password to RailWay!88.`
7. `Generated password: Z9!kLm2$pQ.`
8. `Master password Vault#Pass9.`
9. `New WiFi password CafeLatte!2024.`
10. `Token password = Cl0udNative#42.`

#### False positives
1. `The word "password" appeared in logs.` (mention du mot lui-même, pas la valeur)
2. `Password manager 1Password is recommended.` (nom de produit)
3. `Reset link expires in 30 minutes.` (instruction sans valeur)
4. `Use a strong, unique password.` (conseil générique)
5. `Hunter2 is a famous joke.` (référence culturelle, pas password réel)
6. `Project codename: Welcome123.` (nom de projet)
7. `Subject of the talk: passwords in 2025.` (sujet conférence)
8. `Argument --password is required.` (option CLI)
9. `Class field private String password;` (déclaration de variable)
10. `Documentation: choose a memorable password.` (instruction utilisateur)

---
