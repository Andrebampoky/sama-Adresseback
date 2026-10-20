# Progression du backend

## Première tranche — API de résolution publique

- Dépôt identifié par son remote `sama-Adresseback.git`; dépôt initial vide, sans fichier suivi ni modification locale à préserver.
- Application Spring Boot 3.5 / Java 17, API REST versionnée et documentation OpenAPI ajoutées.
- Migration Flyway `V1` crée les adresses, la contrainte unique du code, les coordonnées bornées et les états métier.
- Lecture publique limitée aux adresses `VALIDATED` ayant `publicly_visible=true` ; le consentement est faux par défaut.
- Contrôle de santé `GET /api/v1/health` vérifie la base SQL.
- Docker Compose de développement prépare PostgreSQL et l'API ; aucune donnée n'a été importée.
- `mvn test` / `mvn verify` : 6 tests réussis, zéro échec/erreur, dont migration Flyway et requêtes sur H2 en mode PostgreSQL.
- `docker compose config --quiet` valide ; image multi-stage construite ; exécution vérifiée avec PostgreSQL 16.13, migration v1 appliquée, `GET /api/v1/health` renvoie `{"status":"UP","database":"UP"}` et OpenAPI répond en HTTP 200.

## Deuxième tranche — comptes, création d'adresse, modération (19 octobre 2026)

État initial constaté : dépôt sans aucun commit, tous les fichiers non suivis ; copie de sauvegarde faite hors dépôt avant modification. Aucun commit ni push effectué.

- Spring Security + OAuth2 Resource Server : JWT HS256 signés par l'API, clé `APP_JWT_SECRET` (éphémère si absente, obligatoire en profil `prod`), rôle et état du compte relus en base à chaque requête.
- Migration `V2` (additive) : table `users`, propriétaire, horodatage du consentement, traçabilité de modération.
- `POST /auth/register`, `POST /auth/login`, `GET /auth/me` ; BCrypt ; limitation à 5 échecs / 15 min par e-mail ; réponse générique identique pour e-mail inconnu ou mauvais mot de passe.
- Aucun admin codé en dur : promotion d'un compte inscrit via `APP_BOOTSTRAP_ADMIN_EMAIL`.
- `POST/GET /me/addresses`, `GET /me/addresses/{id}` : validation serveur des champs, bornes des coordonnées + zone de service Sénégal, consentement obligatoire et explicite, statut initial `PENDING_VALIDATION`, code public généré côté serveur (`SecureRandom`, indépendant de l'identité).
- Unicité du code : vérification applicative + contrainte `UNIQUE` PostgreSQL, nouvel essai sur collision, suffixe allongé si nécessaire.
- `GET /admin/addresses`, `POST /admin/addresses/{id}/validate|reject` protégés par `ROLE_ADMIN`.
- Erreurs `application/problem+json` avec erreurs par champ ; CORS adapté à l'en-tête `Authorization`.

### Vérifications exécutées

- `mvn -B verify` : **32 tests, 0 échec, 0 erreur**, BUILD SUCCESS (dont 6 tests de matrice d'accès anonyme/utilisateur/admin, 6 tests d'authentification, 4 tests API adresses, 4 tests unitaires de collisions, 3 tests sur PostgreSQL 16 réel via Testcontainers).
- Test concurrent sur PostgreSQL réel : 12 créations simultanées avec seulement 3 codes possibles à 4 chiffres → 12 codes distincts ; 7 collisions rejetées par la contrainte PostgreSQL puis retentées (constaté sur deux exécutions).
- `docker compose config --quiet` valide ; `docker compose up --build -d` : image reconstruite, migration V2 appliquée sur la base locale existante (« now at version v2 »), `GET /api/v1/health` → `{"status":"UP","database":"UP"}`.
- Parcours `curl` sur l'API Docker : anonyme → 401 ; inscription ; création → `PENDING_VALIDATION` ; fiche publique → 404 ; utilisateur sur `/admin` → 403 ; promotion par `APP_BOOTSTRAP_ADMIN_EMAIL` (journal confirmé) ; validation admin → 200 ; fiche publique → 200 sans donnée de compte ; préflight CORS → 200 ; hachages `{bcrypt}` en base.
- Parcours navigateur réel (Chrome headless + frontend Vite) décrit dans le `docs/PROGRESS.md` du frontend.

Données présentes dans le volume Docker local après ces essais : comptes et adresses **fictifs** (`@exemple.test`, « Ville Exemple »).

## Troisième tranche — photo de façade (19 octobre 2026)

Modification demandée et autorisée explicitement pour permettre l'ajout de photo côté frontend. Aucun commit ni push.

- Routes photo propriétaire / admin / public (voir `docs/API.md`), règle métier : ajout ou remplacement uniquement pendant la vérification, retrait toujours permis, publication seulement si l'adresse est publique.
- Contrôle de signature binaire, limite de taille (5 Mo, 40 Mpx), réencodage JPEG sans métadonnées, réduction à 1600 px.
- Stockage local derrière l'interface `PhotoStorage` ; Dockerfile et Compose avec dossier/volume dédiés ; CORS : méthode PUT ajoutée.
- `mvn -B -o verify` : **36 tests, 0 échec** (dont 4 nouveaux tests photo : cycle complet, adresse privée, faux fichiers et autres comptes, suppression des métadonnées).
- Vérifié dans Chrome avec l'API réelle : photo 3000×2000 avec GPS EXIF → stockée en 1600×1067 sans EXIF ; fichier de 15 Mo → 413 ; photo publique 404 avant validation puis 200.

## Quatrième tranche — conformité au cahier des charges (19 octobre 2026)

Aucun commit ni push. Détail exigence par exigence : `docs/EXIGENCES.md`.

- Code fondé sur une grille géographique (≈ 110 m) + départage ; tests unitaires, intégration et PostgreSQL réel.
- Migration `V3` (additive) : option « nom dans l'annuaire », table des signalements, index de recherche.
- Annuaire public (code, propriétaire, quartier, ville, type) ; modification/archivage par le propriétaire ; signalements et traitement admin ; indicateurs et gestion des comptes ; SMS (Twilio / simulation) ; itinéraire OpenRouteService ; OpenAPI 3.0 exporté (`docs/openapi.json`) ; diagrammes UML.
- Défaut trouvé grâce au vrai PostgreSQL et corrigé : requête d'annuaire à paramètres nuls (`upper(bytea)`).
- `mvn verify` : 69 tests, 0 échec ; couverture lignes 93,3 % (unitaires seuls 61,4 %), seuil JaCoCo 60 % bloquant.

## À poursuivre

- Fiche publique enrichie, recherche par quartier/ville/type paginée, photos (stockage à définir).
- Modification et archivage par le propriétaire ; favoris ; signalements avec limitation de débit.
- Journal d'audit des actions sensibles ; gestion des comptes (désactivation, rôles) par l'admin ; console d'administration.
- Récupération de mot de passe et vérification d'e-mail (nécessitent un fournisseur d'e-mail).
- Limiteur de tentatives partagé si plusieurs instances.
- Mesure de couverture JaCoCo (cible 60 %) non encore configurée.

## Passage à Java 25 LTS (19 octobre 2026)

- Java 17 → **Java 25** (dernière version LTS) : `pom.xml` (`java.version`), `Dockerfile` (images `maven:3.9.16-eclipse-temurin-25` et `eclipse-temurin:25-jre`), CI (`setup-java` 25), README.
- Montées de version nécessaires au support de Java 25 : Spring Boot 3.5.5 → 3.5.16 (correctifs de la même branche) et JaCoCo 0.8.12 → 0.8.15 (les anciennes versions ne savent pas lire le bytecode Java 25).
- Vérifications : `mvn verify` sous Temurin 25.0.4.1 → 70 tests réussis, 0 échec, couverture des lignes 74,1 % (seuil 60 %) ; classes compilées en version 69 (Java 25) ; image Docker construite ; API relancée, `GET /api/v1/health` → `UP` avec base `UP`.

## Audit fonctionnel (20 octobre 2026)

Rapport complet : `~/Projets/SamaAdresse/RAPPORT_AUDIT_ET_CORRECTIONS_SAMAADRESSE.md`.

- Numéros sénégalais : `+221` exige 9 chiffres commençant par 7 (un numéro tronqué était accepté) ; `221…` sans « + » reconnu.
- Twilio : `SENT` seulement si la réponse contient un `sid` et un statut qui n'est pas en échec.
- `APP_PUBLIC_BASE_URL` vérifiée au démarrage (URL absolue http/https, avertissement si `localhost`).
- Photos : erreurs en `404 problem+json` même si le client demande du JSON (auparavant `406`).
- `docs/openapi.json` régénéré depuis l'API réelle (la route `PUT /me/addresses/{id}/location` y manquait).
- `mvn verify` (Java 25.0.4.1) : 74 tests, 0 échec, 0 ignoré ; couverture des lignes 92,8 % (seuil 60 %).
