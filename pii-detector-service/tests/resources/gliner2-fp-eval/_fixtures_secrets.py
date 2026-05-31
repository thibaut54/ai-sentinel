"""PII test fixtures — Secrets / credentials family for the GLiNER2 FP/FN eval.

Five types: PASSWORD, SECRET, API_KEY, ACCESS_TOKEN, RECOVERY_CODE.

Each builder follows the shared contract: it returns a dict with ``pii_type``,
``detector_label``, ``threshold`` and a list of ``cases`` produced via
``_case``/``_span`` so every expected span stays byte-exact
(``text[start:end] == value``).
"""
from __future__ import annotations

from typing import Any, Dict, List

from _fixture_helpers import _span, _case


def build_password() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue (TP : mot-cle + valeur)
    t = "Mot de passe utilisateur : Tr0ub4dor!2024"
    cases.append(_case("PASSWORD_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t, [_span(t, "Tr0ub4dor!2024")]))
    t = "Account password: P@ssw0rd-Spring#2024"
    cases.append(_case("PASSWORD_canonical_with_clue_en_01", "en", "canonical_with_clue", t, [_span(t, "P@ssw0rd-Spring#2024")]))
    t = "Das Passwort lautet Sommer$Regen-19!"
    cases.append(_case("PASSWORD_canonical_with_clue_de_01", "de", "canonical_with_clue", t, [_span(t, "Sommer$Regen-19!")]))
    t = "La password dell'account e Estate&Sole#2024"
    cases.append(_case("PASSWORD_canonical_with_clue_it_01", "it", "canonical_with_clue", t, [_span(t, "Estate&Sole#2024")]))

    # canonical_no_clue (TP isole)
    t = "Tr0ub4dor!2024"
    cases.append(_case("PASSWORD_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t, [_span(t, "Tr0ub4dor!2024")]))
    t = "Connecte-toi avec P@ssw0rd-Spring#2024 puis change-le."
    cases.append(_case("PASSWORD_canonical_no_clue_en_01", "en", "canonical_no_clue", t, [_span(t, "P@ssw0rd-Spring#2024")]))
    t = "Notiere dir Sommer$Regen-19! fuer den ersten Login."
    cases.append(_case("PASSWORD_canonical_no_clue_de_01", "de", "canonical_no_clue", t, [_span(t, "Sommer$Regen-19!")]))
    t = "Usa Estate&Sole#2024 per accedere al portale."
    cases.append(_case("PASSWORD_canonical_no_clue_it_01", "it", "canonical_no_clue", t, [_span(t, "Estate&Sole#2024")]))

    # look_alikes (formes ressemblantes mais NON secret, aucun span)
    cases.append(_case("PASSWORD_look_alikes_en_01", "en", "look_alikes",
        "Function signature: def authenticate(username: str, password: str) -> bool:"))
    cases.append(_case("PASSWORD_look_alikes_en_02", "en", "look_alikes",
        "Read the credentials from the DB_PASSWORD_KEY environment variable."))
    cases.append(_case("PASSWORD_look_alikes_fr_01", "fr", "look_alikes",
        "Le nom de code de l'operation est PROJET-PHENIX-7."))
    cases.append(_case("PASSWORD_look_alikes_de_01", "de", "look_alikes",
        "Die Seriennummer des Geraets ist SN-4471-AB-2024."))
    cases.append(_case("PASSWORD_look_alikes_it_01", "it", "look_alikes",
        "Il campo del modulo si chiama PasswordConfirm ed e obbligatorio."))
    cases.append(_case("PASSWORD_look_alikes_en_03", "en", "look_alikes",
        "The constant PASSWORD_MIN_LENGTH is set to 12 characters."))

    # explicit_negatives (mot-cle sans valeur reelle, aucun span)
    cases.append(_case("PASSWORD_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Veuillez saisir votre mot de passe pour continuer."))
    cases.append(_case("PASSWORD_explicit_negatives_en_01", "en", "explicit_negatives",
        "Your password has been reset successfully."))
    cases.append(_case("PASSWORD_explicit_negatives_de_01", "de", "explicit_negatives",
        "Bitte geben Sie Ihr Passwort ein."))
    cases.append(_case("PASSWORD_explicit_negatives_it_01", "it", "explicit_negatives",
        "Hai dimenticato la tua password? Clicca qui."))

    # adversarial_formatting (span si valeur contigue, sinon aucun)
    t = "pwd=Tr0ub4dor!2024;host=db01"
    cases.append(_case("PASSWORD_adversarial_formatting_en_01", "en", "adversarial_formatting", t, [_span(t, "Tr0ub4dor!2024")]))
    t = '{"user":"root","password":"P@ssw0rd-Spring#2024"}'
    cases.append(_case("PASSWORD_adversarial_formatting_en_02", "en", "adversarial_formatting", t, [_span(t, "P@ssw0rd-Spring#2024")]))
    cases.append(_case("PASSWORD_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        "Mot | de | passe | : | (valeur masquee dans le coffre)"))

    # long_context (1 positif + 1 negatif)
    t = ("Suite a votre demande de creation de compte, voici vos acces au portail "
         "interne. Identifiant : jdupont. Mot de passe provisoire : Sommer$Regen-19!. "
         "Merci de le modifier des votre premiere connexion via le menu Profil.")
    cases.append(_case("PASSWORD_long_context_fr_01", "fr", "long_context", t, [_span(t, "Sommer$Regen-19!")]))
    cases.append(_case("PASSWORD_long_context_en_01", "en", "long_context",
        "Our security policy requires every employee to choose a strong password, "
        "rotate it every ninety days, and never reuse a password across systems. "
        "If you forget your password, use the self-service reset portal."))

    return {"pii_type": "PASSWORD", "detector_label": "password", "threshold": 0.50, "cases": cases}


def build_secret() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Le secret du client est : s3cr3t_v4lue_9xQ"
    cases.append(_case("SECRET_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t, [_span(t, "s3cr3t_v4lue_9xQ")]))
    t = "Use this client secret: 8a9f2c1d4b6e7081"
    cases.append(_case("SECRET_canonical_with_clue_en_01", "en", "canonical_with_clue", t, [_span(t, "8a9f2c1d4b6e7081")]))
    t = "Das Geheimnis im Vault: vault://prod/db#Hk7-Zq91"
    cases.append(_case("SECRET_canonical_with_clue_de_01", "de", "canonical_with_clue", t, [_span(t, "vault://prod/db#Hk7-Zq91")]))
    t = "Il segreto condiviso e s3cr3t_v4lue_9xQ"
    cases.append(_case("SECRET_canonical_with_clue_it_01", "it", "canonical_with_clue", t, [_span(t, "s3cr3t_v4lue_9xQ")]))

    # canonical_no_clue
    t = "s3cr3t_v4lue_9xQ"
    cases.append(_case("SECRET_canonical_no_clue_en_01", "en", "canonical_no_clue", t, [_span(t, "s3cr3t_v4lue_9xQ")]))
    t = "Copie 8a9f2c1d4b6e7081 dans la configuration."
    cases.append(_case("SECRET_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t, [_span(t, "8a9f2c1d4b6e7081")]))
    t = "Hinterlege Hk7-Zq91-Wm33 in der Pipeline-Variable."
    cases.append(_case("SECRET_canonical_no_clue_de_01", "de", "canonical_no_clue", t, [_span(t, "Hk7-Zq91-Wm33")]))
    t = "Inserisci s3cr3t_v4lue_9xQ nel file di ambiente."
    cases.append(_case("SECRET_canonical_no_clue_it_01", "it", "canonical_no_clue", t, [_span(t, "s3cr3t_v4lue_9xQ")]))

    # look_alikes
    cases.append(_case("SECRET_look_alikes_en_01", "en", "look_alikes",
        "The Vault path vault://prod/db has no value committed here."))
    cases.append(_case("SECRET_look_alikes_en_02", "en", "look_alikes",
        "Set the constant SOME_PLACEHOLDER before deploying."))
    cases.append(_case("SECRET_look_alikes_fr_01", "fr", "look_alikes",
        "C'est un secret de Polichinelle, tout le monde le sait."))
    cases.append(_case("SECRET_look_alikes_de_01", "de", "look_alikes",
        "Das Verzeichnis heisst secrets/ und liegt im Repo-Root."))
    cases.append(_case("SECRET_look_alikes_it_01", "it", "look_alikes",
        "Il nome della variabile e CLIENT_SECRET_NAME senza valore."))
    cases.append(_case("SECRET_look_alikes_en_03", "en", "look_alikes",
        "She kept the surprise party a secret until the very last minute."))

    # explicit_negatives
    cases.append(_case("SECRET_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Le secret partage sera communique par un autre canal."))
    cases.append(_case("SECRET_explicit_negatives_en_01", "en", "explicit_negatives",
        "Please rotate the client secret before the end of the quarter."))
    cases.append(_case("SECRET_explicit_negatives_de_01", "de", "explicit_negatives",
        "Das Geheimnis ist im Tresor abgelegt."))
    cases.append(_case("SECRET_explicit_negatives_it_01", "it", "explicit_negatives",
        "Il segreto verra fornito separatamente dal team di sicurezza."))

    # adversarial_formatting
    t = "client_secret=8a9f2c1d4b6e7081&grant_type=client_credentials"
    cases.append(_case("SECRET_adversarial_formatting_en_01", "en", "adversarial_formatting", t, [_span(t, "8a9f2c1d4b6e7081")]))
    t = "secret:::s3cr3t_v4lue_9xQ"
    cases.append(_case("SECRET_adversarial_formatting_en_02", "en", "adversarial_formatting", t, [_span(t, "s3cr3t_v4lue_9xQ")]))
    cases.append(_case("SECRET_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        "s e c r e t : (valeur retiree avant publication)"))

    # long_context
    t = ("Dans le cadre de l'integration OAuth, l'application backend doit echanger un "
         "jeton. Voici les parametres a configurer cote serveur : client_id=app-42 et "
         "client secret : 8a9f2c1d4b6e7081. Ne committez jamais ces valeurs dans Git.")
    cases.append(_case("SECRET_long_context_fr_01", "fr", "long_context", t, [_span(t, "8a9f2c1d4b6e7081")]))
    cases.append(_case("SECRET_long_context_en_01", "en", "long_context",
        "Our onboarding guide explains how secrets are managed: every team stores its "
        "credentials in the central Vault, references them by path, and never hardcodes "
        "a secret in source control. Ask the platform team for read access."))

    return {"pii_type": "SECRET", "detector_label": "secret", "threshold": 0.50, "cases": cases}


def build_api_key() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Cle API Stripe : sk_live_4eC39HqLyjWDarjtT1zdp7dc"
    cases.append(_case("API_KEY_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t, [_span(t, "sk_live_4eC39HqLyjWDarjtT1zdp7dc")]))
    t = "AWS access key id: AKIAIOSFODNN7EXAMPLE"
    cases.append(_case("API_KEY_canonical_with_clue_en_01", "en", "canonical_with_clue", t, [_span(t, "AKIAIOSFODNN7EXAMPLE")]))
    t = "Der API-Schluessel lautet AIzaSyD-EXAMPLE-key"
    cases.append(_case("API_KEY_canonical_with_clue_de_01", "de", "canonical_with_clue", t, [_span(t, "AIzaSyD-EXAMPLE-key")]))
    t = "La chiave API e sk_live_4eC39HqLyjWDarjtT1zdp7dc"
    cases.append(_case("API_KEY_canonical_with_clue_it_01", "it", "canonical_with_clue", t, [_span(t, "sk_live_4eC39HqLyjWDarjtT1zdp7dc")]))

    # canonical_no_clue
    t = "sk_live_4eC39HqLyjWDarjtT1zdp7dc"
    cases.append(_case("API_KEY_canonical_no_clue_en_01", "en", "canonical_no_clue", t, [_span(t, "sk_live_4eC39HqLyjWDarjtT1zdp7dc")]))
    t = "Colle AKIAIOSFODNN7EXAMPLE dans le champ prevu."
    cases.append(_case("API_KEY_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t, [_span(t, "AKIAIOSFODNN7EXAMPLE")]))
    t = "Trage AIzaSyD-EXAMPLE-key in die Konfiguration ein."
    cases.append(_case("API_KEY_canonical_no_clue_de_01", "de", "canonical_no_clue", t, [_span(t, "AIzaSyD-EXAMPLE-key")]))
    t = "Usa sk_live_4eC39HqLyjWDarjtT1zdp7dc per autenticare le richieste."
    cases.append(_case("API_KEY_canonical_no_clue_it_01", "it", "canonical_no_clue", t, [_span(t, "sk_live_4eC39HqLyjWDarjtT1zdp7dc")]))

    # look_alikes
    cases.append(_case("API_KEY_look_alikes_en_01", "en", "look_alikes",
        "Read the value from the API_KEY_ENV variable at startup."))
    cases.append(_case("API_KEY_look_alikes_en_02", "en", "look_alikes",
        "The header was logged as Authorization: ApiKey ******** (redacted)."))
    cases.append(_case("API_KEY_look_alikes_fr_01", "fr", "look_alikes",
        "L'identifiant de session est 550e8400-e29b-41d4-a716-446655440000."))
    cases.append(_case("API_KEY_look_alikes_de_01", "de", "look_alikes",
        "Die Datei liegt unter /etc/app/keys/api_key.pem im Container."))
    cases.append(_case("API_KEY_look_alikes_it_01", "it", "look_alikes",
        "Il modulo espone il campo apiKey come stringa vuota di default."))
    cases.append(_case("API_KEY_look_alikes_en_03", "en", "look_alikes",
        "Documentation section: How to obtain an API key from the dashboard."))

    # explicit_negatives
    cases.append(_case("API_KEY_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Votre cle API a ete revoquee pour raisons de securite."))
    cases.append(_case("API_KEY_explicit_negatives_en_01", "en", "explicit_negatives",
        "Generate a new API key in the developer settings page."))
    cases.append(_case("API_KEY_explicit_negatives_de_01", "de", "explicit_negatives",
        "Der API-Schluessel wurde erfolgreich erstellt."))
    cases.append(_case("API_KEY_explicit_negatives_it_01", "it", "explicit_negatives",
        "La chiave API e scaduta, rinnovala dalla console."))

    # adversarial_formatting
    t = "STRIPE_KEY=sk_live_4eC39HqLyjWDarjtT1zdp7dc"
    cases.append(_case("API_KEY_adversarial_formatting_en_01", "en", "adversarial_formatting", t, [_span(t, "sk_live_4eC39HqLyjWDarjtT1zdp7dc")]))
    t = "?api_key=AIzaSyD-EXAMPLE-key&lang=fr"
    cases.append(_case("API_KEY_adversarial_formatting_en_02", "en", "adversarial_formatting", t, [_span(t, "AIzaSyD-EXAMPLE-key")]))
    cases.append(_case("API_KEY_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        "A P I _ K E Y = (valeur supprimee du dump)"))

    # long_context
    t = ("Pour finaliser l'integration de paiement, ajoutez la variable d'environnement "
         "suivante a votre service avant le deploiement : STRIPE_SECRET_KEY pointant vers "
         "sk_live_4eC39HqLyjWDarjtT1zdp7dc. Cette cle ne doit jamais apparaitre cote client.")
    cases.append(_case("API_KEY_long_context_fr_01", "fr", "long_context", t, [_span(t, "sk_live_4eC39HqLyjWDarjtT1zdp7dc")]))
    cases.append(_case("API_KEY_long_context_en_01", "en", "long_context",
        "The integration guide describes how to request an API key, scope it to the "
        "minimum required permissions, store it in your secret manager, and rotate it "
        "regularly. Never commit an API key to the repository or share it over email."))

    return {"pii_type": "API_KEY", "detector_label": "api_key", "threshold": 0.50, "cases": cases}


def build_access_token() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Jeton d'acces GitHub : ghp_xxxxxxxxxxxxxxxxxxxx"
    cases.append(_case("ACCESS_TOKEN_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t, [_span(t, "ghp_xxxxxxxxxxxxxxxxxxxx")]))
    t = "Authorization header: Bearer ya29.a0AfH-EXAMPLE-token"
    cases.append(_case("ACCESS_TOKEN_canonical_with_clue_en_01", "en", "canonical_with_clue", t, [_span(t, "ya29.a0AfH-EXAMPLE-token")]))
    t = "Das Access-Token lautet eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
    cases.append(_case("ACCESS_TOKEN_canonical_with_clue_de_01", "de", "canonical_with_clue", t, [_span(t, "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")]))
    t = "Il token di accesso e ghp_xxxxxxxxxxxxxxxxxxxx"
    cases.append(_case("ACCESS_TOKEN_canonical_with_clue_it_01", "it", "canonical_with_clue", t, [_span(t, "ghp_xxxxxxxxxxxxxxxxxxxx")]))

    # canonical_no_clue
    t = "ghp_xxxxxxxxxxxxxxxxxxxx"
    cases.append(_case("ACCESS_TOKEN_canonical_no_clue_en_01", "en", "canonical_no_clue", t, [_span(t, "ghp_xxxxxxxxxxxxxxxxxxxx")]))
    t = "Colle eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9 dans l'en-tete."
    cases.append(_case("ACCESS_TOKEN_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t, [_span(t, "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")]))
    t = "Sende die Anfrage mit Bearer ya29.a0AfH-EXAMPLE-token im Header."
    cases.append(_case("ACCESS_TOKEN_canonical_no_clue_de_01", "de", "canonical_no_clue", t, [_span(t, "ya29.a0AfH-EXAMPLE-token")]))
    t = "Allega ghp_xxxxxxxxxxxxxxxxxxxx alla richiesta API."
    cases.append(_case("ACCESS_TOKEN_canonical_no_clue_it_01", "it", "canonical_no_clue", t, [_span(t, "ghp_xxxxxxxxxxxxxxxxxxxx")]))

    # look_alikes
    cases.append(_case("ACCESS_TOKEN_look_alikes_en_01", "en", "look_alikes",
        "The commit SHA is 9fceb02d2c83b4b5d6e8f7a1c4d3e2f1a0b9c8d7."))
    cases.append(_case("ACCESS_TOKEN_look_alikes_en_02", "en", "look_alikes",
        "The Mongo document _id is 507f1f77bcf86cd799439011."))
    cases.append(_case("ACCESS_TOKEN_look_alikes_fr_01", "fr", "look_alikes",
        "Le logo encode en base64 commence par iVBORw0KGgoAAAANSUhEUgAA."))
    cases.append(_case("ACCESS_TOKEN_look_alikes_de_01", "de", "look_alikes",
        "Der Branch heisst feature/bearer-token-refactor im Repo."))
    cases.append(_case("ACCESS_TOKEN_look_alikes_it_01", "it", "look_alikes",
        "Il parametro si chiama accessTokenTtl e vale 3600 secondi."))
    cases.append(_case("ACCESS_TOKEN_look_alikes_en_03", "en", "look_alikes",
        "Bearer is a payment provider mentioned in the partner list."))

    # explicit_negatives
    cases.append(_case("ACCESS_TOKEN_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Votre jeton d'acces a expire, veuillez vous reconnecter."))
    cases.append(_case("ACCESS_TOKEN_explicit_negatives_en_01", "en", "explicit_negatives",
        "A new access token will be issued after authentication."))
    cases.append(_case("ACCESS_TOKEN_explicit_negatives_de_01", "de", "explicit_negatives",
        "Das Access-Token wurde widerrufen."))
    cases.append(_case("ACCESS_TOKEN_explicit_negatives_it_01", "it", "explicit_negatives",
        "Il token di accesso e stato rinnovato automaticamente."))

    # adversarial_formatting
    t = "Authorization:Bearer ghp_xxxxxxxxxxxxxxxxxxxx"
    cases.append(_case("ACCESS_TOKEN_adversarial_formatting_en_01", "en", "adversarial_formatting", t, [_span(t, "ghp_xxxxxxxxxxxxxxxxxxxx")]))
    t = '{"token":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"}'
    cases.append(_case("ACCESS_TOKEN_adversarial_formatting_en_02", "en", "adversarial_formatting", t, [_span(t, "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")]))
    cases.append(_case("ACCESS_TOKEN_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        "B e a r e r : (jeton tronque dans les logs)"))

    # long_context
    t = ("Apres authentification reussie, le serveur OAuth renvoie une reponse JSON "
         "contenant le jeton d'acces a utiliser pour les appels suivants : "
         "ghp_xxxxxxxxxxxxxxxxxxxx. Ce jeton expire au bout d'une heure et doit etre "
         "rafraichi via le refresh token associe.")
    cases.append(_case("ACCESS_TOKEN_long_context_fr_01", "fr", "long_context", t, [_span(t, "ghp_xxxxxxxxxxxxxxxxxxxx")]))
    cases.append(_case("ACCESS_TOKEN_long_context_en_01", "en", "long_context",
        "The authentication flow works as follows: the client exchanges its credentials, "
        "receives an access token with a short lifetime, attaches it to every protected "
        "request, and refreshes it before expiry. Tokens are never logged in plain text."))

    return {"pii_type": "ACCESS_TOKEN", "detector_label": "access_token", "threshold": 0.50, "cases": cases}


def build_recovery_code() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Code de recuperation : ABCD-EFGH-1234"
    cases.append(_case("RECOVERY_CODE_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t, [_span(t, "ABCD-EFGH-1234")]))
    t = "Backup code: 8f3k-29dz-71qm"
    cases.append(_case("RECOVERY_CODE_canonical_with_clue_en_01", "en", "canonical_with_clue", t, [_span(t, "8f3k-29dz-71qm")]))
    t = "Ihr Wiederherstellungscode lautet RECOV-7782-3341"
    cases.append(_case("RECOVERY_CODE_canonical_with_clue_de_01", "de", "canonical_with_clue", t, [_span(t, "RECOV-7782-3341")]))
    t = "Il codice di recupero e ABCD-EFGH-1234"
    cases.append(_case("RECOVERY_CODE_canonical_with_clue_it_01", "it", "canonical_with_clue", t, [_span(t, "ABCD-EFGH-1234")]))

    # canonical_no_clue
    t = "ABCD-EFGH-1234"
    cases.append(_case("RECOVERY_CODE_canonical_no_clue_en_01", "en", "canonical_no_clue", t, [_span(t, "ABCD-EFGH-1234")]))
    t = "Conserve 8f3k-29dz-71qm en lieu sur hors ligne."
    cases.append(_case("RECOVERY_CODE_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t, [_span(t, "8f3k-29dz-71qm")]))
    t = "Bewahre RECOV-7782-3341 an einem sicheren Ort auf."
    cases.append(_case("RECOVERY_CODE_canonical_no_clue_de_01", "de", "canonical_no_clue", t, [_span(t, "RECOV-7782-3341")]))
    t = "Annota 8f3k-29dz-71qm prima di chiudere questa pagina."
    cases.append(_case("RECOVERY_CODE_canonical_no_clue_it_01", "it", "canonical_no_clue", t, [_span(t, "8f3k-29dz-71qm")]))

    # look_alikes
    cases.append(_case("RECOVERY_CODE_look_alikes_en_01", "en", "look_alikes",
        "Your software license number is XPRO-2024-EDIT-9981."))
    cases.append(_case("RECOVERY_CODE_look_alikes_fr_01", "fr", "look_alikes",
        "Le tag d'inventaire de l'ordinateur portable est ASSET-IT-04417."))
    cases.append(_case("RECOVERY_CODE_look_alikes_en_02", "en", "look_alikes",
        "Use promo code SUMMER-2024 at checkout for 20% off."))
    cases.append(_case("RECOVERY_CODE_look_alikes_de_01", "de", "look_alikes",
        "Die Bestellnummer lautet ORD-2024-77821 im Shop-System."))
    cases.append(_case("RECOVERY_CODE_look_alikes_it_01", "it", "look_alikes",
        "Il numero di tracking del pacco e TRK-9981-4471-IT."))
    cases.append(_case("RECOVERY_CODE_look_alikes_en_03", "en", "look_alikes",
        "The product SKU is SKU-AB12-CD34 in the catalog export."))

    # explicit_negatives
    cases.append(_case("RECOVERY_CODE_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Vos codes de recuperation ont ete regeneres avec succes."))
    cases.append(_case("RECOVERY_CODE_explicit_negatives_en_01", "en", "explicit_negatives",
        "Download your backup codes and store them in a safe place."))
    cases.append(_case("RECOVERY_CODE_explicit_negatives_de_01", "de", "explicit_negatives",
        "Bitte bewahren Sie Ihre Wiederherstellungscodes sicher auf."))
    cases.append(_case("RECOVERY_CODE_explicit_negatives_it_01", "it", "explicit_negatives",
        "Hai gia utilizzato tutti i tuoi codici di recupero."))

    # adversarial_formatting
    t = "code=ABCD-EFGH-1234;used=false"
    cases.append(_case("RECOVERY_CODE_adversarial_formatting_en_01", "en", "adversarial_formatting", t, [_span(t, "ABCD-EFGH-1234")]))
    t = '["8f3k-29dz-71qm","RECOV-7782-3341"]'
    cases.append(_case("RECOVERY_CODE_adversarial_formatting_en_02", "en", "adversarial_formatting", t,
        [_span(t, "8f3k-29dz-71qm"), _span(t, "RECOV-7782-3341")]))
    cases.append(_case("RECOVERY_CODE_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        "C O D E : (codes masques pour ce ticket)"))

    # long_context
    t = ("Lors de l'activation de la double authentification, le systeme vous a fourni une "
         "liste de codes de secours a usage unique. En cas de perte de votre telephone, "
         "utilisez l'un d'eux pour vous reconnecter, par exemple RECOV-7782-3341. "
         "Chaque code ne fonctionne qu'une seule fois.")
    cases.append(_case("RECOVERY_CODE_long_context_fr_01", "fr", "long_context", t, [_span(t, "RECOV-7782-3341")]))
    cases.append(_case("RECOVERY_CODE_long_context_en_01", "en", "long_context",
        "When you enable two-factor authentication we generate a set of one-time "
        "recovery codes. Print them, keep them offline, and never store them next to "
        "your password. Each recovery code can be used only once to regain access."))

    return {"pii_type": "RECOVERY_CODE", "detector_label": "recovery_code", "threshold": 0.50, "cases": cases}


BUILDERS: Dict[str, Any] = {
    "PASSWORD": build_password,
    "SECRET": build_secret,
    "API_KEY": build_api_key,
    "ACCESS_TOKEN": build_access_token,
    "RECOVERY_CODE": build_recovery_code,
}
