# SamaAdresse — API backend

API REST Java / Spring Boot avec PostgreSQL et migrations Flyway. Le backend est un dépôt autonome ; le frontend React est conservé dans son dépôt distinct.

## Prérequis

- Java 25 (LTS) ;
- Maven 3.8 ou plus récent ;
- Docker avec Docker Compose pour démarrer PostgreSQL et l'API ensemble.

## Démarrer l'API et PostgreSQL avec Docker

Depuis la racine de ce dépôt :

```bash
cp .env.example .env
docker compose up --build
```

L'API répond sur <http://localhost:8080>, la base PostgreSQL sur le port `5432` et Swagger UI sur <http://localhost:8080/swagger-ui.html>. Le conteneur attend que PostgreSQL soit en bonne santé avant de démarrer ; Flyway applique les migrations au lancement.

Pour arrêter les services sans effacer les données :

```bash
docker compose down
```

La commande `docker compose down -v` supprimerait aussi le volume PostgreSQL local ; ne l'utilisez pas si vous souhaitez conserver les données.

## Démarrer l'API avec Maven

Démarrez d'abord uniquement PostgreSQL avec `docker compose up -d db`, puis :

```bash
mvn spring-boot:run
```

## Variables d'environnement

| Variable | Défaut (développement) | Rôle |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/samaadresse` | Base PostgreSQL |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `samaadresse_dev` / `change-me-local` | Identifiants SQL **de développement uniquement** |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Origines autorisées, séparées par des virgules |
| `APP_JWT_SECRET` | vide | Clé de signature Base64 (≥ 32 octets) : `openssl rand -base64 48`. Vide : clé éphémère, sessions perdues au redémarrage |
| `APP_JWT_REQUIRE_SECRET` | `false` (`true` avec le profil `prod`) | Refuse de démarrer sans `APP_JWT_SECRET` |
| `APP_JWT_VALIDITY` | `PT1H` | Durée de vie des jetons (ISO 8601) |
| `APP_BOOTSTRAP_ADMIN_EMAIL` | vide | E-mail d'un compte **déjà inscrit** à promouvoir ADMIN au démarrage |
| `SPRING_PROFILES_ACTIVE` | aucun | `prod` : secret JWT obligatoire, Swagger désactivé |
| `APP_PUBLIC_BASE_URL` | `http://localhost:5173` | URL du frontend, utilisée dans les liens envoyés par SMS |
| `APP_SMS_PROVIDER` | vide | `twilio`, `simulation` (n'envoie rien, résultat `SIMULATED`) ou vide (envoi désactivé : 503) |
| `TWILIO_ACCOUNT_SID` / `TWILIO_AUTH_TOKEN` / `TWILIO_FROM` | vides | Identifiants Twilio (identifiants de test pour le sandbox) |
| `ORS_API_KEY` | vide | Clé OpenRouteService pour l'itinéraire. Vide : repli sur OSRM (voir ci-dessous) |
| `APP_ROUTING_FALLBACK_ENABLED` / `APP_ROUTING_OSRM_BASE_URL` | `true` / `https://routing.openstreetmap.de` | Repli sans clé sur un serveur OSRM communautaire (FOSSGIS, données OpenStreetMap) ; adapté au développement et aux démonstrations, pas à un fort trafic. `false` : 503 sans clé ORS |
| `APP_PHOTOS_DIRECTORY` | `./data/photos` | Dossier des photos de façade (ignoré par Git ; volume Docker `samaadresse-photos`) |

Ne placez jamais de secret réel dans un fichier versionné ; `.env` est ignoré par Git.

## Créer un administrateur

Aucun compte administrateur n'est fourni. Inscrivez-vous normalement (site ou `POST /api/v1/auth/register`), puis redémarrez l'API avec votre e-mail :

```bash
APP_BOOTSTRAP_ADMIN_EMAIL=votre-email@exemple.test docker compose up -d api
```

Le journal affiche « Compte promu administrateur via APP_BOOTSTRAP_ADMIN_EMAIL. ». Détails dans `docs/SECURITE.md`.

## Contrat et commandes de vérification

Routes principales (contrat complet : `docs/API.md` ; fichier OpenAPI 3.0 exportable : `docs/openapi.json`, importable dans Postman) :

- public : `GET /api/v1/health`, `GET /api/v1/addresses` (annuaire : code, propriétaire, quartier, ville, type), `GET /api/v1/addresses/{code}` (+ `/photo`), `GET /api/v1/routes` (itinéraire OpenRouteService) ;
- comptes : `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `GET /api/v1/auth/me` ;
- propriétaire : `GET/POST /api/v1/me/addresses`, `GET/PUT /api/v1/me/addresses/{id}`, `POST …/archive`, `PUT/GET/DELETE …/photo`, `POST …/sms`, `POST /api/v1/addresses/{code}/reports` ;
- administrateur : `GET /api/v1/admin/addresses`, `POST …/{id}/validate|reject`, `GET /api/v1/admin/reports`, `POST …/{id}/dismiss|archive-address`, `GET /api/v1/admin/stats`, `GET /api/v1/admin/users`, `POST …/{id}/disable|enable` ;
- `GET /swagger-ui.html` : documentation interactive.

Code d'adresse : `VIL-QUA-NNNN` où NNNN est la case d'une grille géographique de 0,001° (≈ 110 m) ; un chiffre de départage est ajouté si la case est déjà occupée (détails : `docs/API.md`, diagrammes : `docs/UML.md`, matrice des exigences : `docs/EXIGENCES.md`, déploiement : `docs/DEPLOIEMENT.md`).

```bash
mvn test      # tests unitaires et d'intégration
mvn verify    # tests + empaquetage + couverture JaCoCo (échec sous 60 % de lignes couvertes)
# Rapport de couverture : target/site/jacoco/index.html
```

Les tests d'intégration utilisent H2 en mode PostgreSQL, sauf `PostgresAddressCodeIntegrationTest` qui démarre un vrai PostgreSQL 16 via Testcontainers (Docker requis ; le test est ignoré si Docker est absent). La première exécution télécharge l'image `postgres:16-alpine` si elle n'est pas présente.

## Sécurité et limites actuelles

Comptes avec mots de passe BCrypt, jetons JWT courts, contrôle d'accès serveur USER/ADMIN, consentement explicite à la publication et modération avant toute publication. Voir `docs/SECURITE.md` pour le détail et les limites (limiteur en mémoire, pas de récupération de mot de passe, pas de journal d'audit, pas de photos).

Les données de test et d'exemple sont fictives. N'importez pas de données réelles sans HTTPS, secret JWT géré et politique de conservation définie.

## Branches Git (à mettre en place avec votre accord)

Le dépôt n'a encore aucun commit. Stratégie prévue par le cahier des charges : `main` (version livrée), `develop` (intégration), `feature/<sujet>` (une fonctionnalité, fusionnée dans `develop` par pull request).

## Dépannage

- `function upper(bytea) does not exist` : ne se produit plus (l'annuaire utilise l'API Criteria) ; il venait de paramètres nuls que PostgreSQL ne sait pas typer, invisibles avec H2. D'où le test sur un vrai PostgreSQL.
- Le frontend reçoit une erreur CORS : ajoutez son origine exacte dans `APP_CORS_ALLOWED_ORIGINS`.
- SMS ou itinéraire renvoient 503 : fournisseur ou clé non configurés (comportement voulu).
