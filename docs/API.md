# Contrat REST v1

Base locale : `http://localhost:8080/api/v1`. Swagger UI : <http://localhost:8080/swagger-ui.html> ; OpenAPI JSON : <http://localhost:8080/v3/api-docs> (désactivés avec le profil `prod`).

## Conventions

- Corps JSON en UTF-8. Dates au format ISO 8601 UTC.
- Authentification : en-tête `Authorization: Bearer <accessToken>` (jeton obtenu à l'inscription ou à la connexion). Aucun cookie n'est utilisé.
- Erreurs au format `application/problem+json` :

```json
{ "status": 400, "detail": "Certains champs sont invalides.", "errors": { "latitude": "La latitude doit être comprise entre -90 et 90." } }
```

`errors` n'est présent que pour les erreurs de validation (une entrée par champ fautif).

| Code | Signification |
|---|---|
| 400 | Données invalides (voir `errors`) |
| 401 | Non authentifié, jeton absent, invalide, expiré, ou compte désactivé |
| 403 | Authentifié mais rôle insuffisant (routes `/admin/**`) |
| 404 | Ressource absente **ou** appartenant à un autre compte (on ne confirme pas son existence) |
| 409 | Conflit : e-mail déjà inscrit, décision de modération déjà prise |
| 429 | Trop d'échecs de connexion pour cet e-mail |
| 503 | Aucun code unique n'a pu être attribué après tous les essais (très improbable) |

## Matrice d'accès

| Route | Anonyme | USER | ADMIN |
|---|---|---|---|
| `GET /health`, `GET /addresses/{code}` | ✅ | ✅ | ✅ |
| `POST /auth/register`, `POST /auth/login` | ✅ | ✅ | ✅ |
| `GET /auth/me` | 401 | ✅ | ✅ |
| `GET/POST /me/addresses`, `GET /me/addresses/{id}` | 401 | ✅ (ses adresses uniquement) | ✅ (ses adresses uniquement) |
| `/admin/**` | 401 | 403 | ✅ |

Ces règles sont appliquées par Spring Security côté serveur (`SecurityConfig`) et couvertes par `AccessControlIntegrationTest`.

## Santé

`GET /health` exécute `SELECT 1` sur PostgreSQL → `{"status":"UP","database":"UP"}`.

## Authentification

### `POST /auth/register` → 201

```json
{ "email": "personne@exemple.test", "password": "au-moins-10-caracteres-1", "displayName": "Prénom" }
```

- e-mail valide, 254 caractères max, enregistré en minuscules (unicité insensible à la casse) ;
- mot de passe : 10 à 72 caractères (72 octets UTF-8 au plus, limite de BCrypt), au moins une lettre et un chiffre ;
- nom affiché : 2 à 80 caractères ;
- tout champ supplémentaire (par exemple `role`) est ignoré : le compte créé est toujours `USER`.

Réponse (identique pour la connexion) :

```json
{
  "accessToken": "eyJ…",
  "tokenType": "Bearer",
  "expiresAt": "2026-10-19T09:04:53Z",
  "user": { "id": "…", "email": "personne@exemple.test", "displayName": "Prénom", "role": "USER", "createdAt": "…" }
}
```

### `POST /auth/login` → 200

`{ "email": "…", "password": "…" }`. Réponse `401` avec le message unique « E-mail ou mot de passe incorrect. », que l'e-mail existe ou non. Après 5 échecs en 15 minutes pour un même e-mail : `429` jusqu'à la fin de la fenêtre.

### `GET /auth/me` → 200

Renvoie l'objet `user` ci-dessus.

### Déconnexion

L'API est sans état : la déconnexion consiste à oublier le jeton côté client. Un jeton reste techniquement valide jusqu'à son expiration (1 h par défaut), sauf si le compte est désactivé (vérifié à chaque requête).

## Mes adresses (authentifié)

### `POST /me/addresses` → 201 (+ en-tête `Location`)

```json
{
  "title": "Lieu de démonstration",
  "placeType": "HOUSE",
  "district": "Quartier Exemple",
  "city": "Ville Exemple",
  "description": "Repères facultatifs",
  "latitude": 14.5,
  "longitude": -15.5,
  "publicationConsent": true
}
```

| Champ | Règle |
|---|---|
| `title` | obligatoire, 2 à 160 caractères |
| `placeType` | `HOUSE`, `BUSINESS`, `INSTITUTION` ou `OTHER` |
| `district`, `city` | obligatoires, 2 à 100 caractères |
| `description` | facultatif, 1000 caractères max |
| `latitude` / `longitude` | obligatoires, bornes terrestres puis zone de service (Sénégal approximatif : lat 12.0 à 16.8, lng -17.6 à -11.3, configurable) ; arrondies à 6 décimales |
| `publicationConsent` | **obligatoire** (`true` ou `false`) : choix explicite de publication |

Le serveur ignore tout `publicCode`, `status`, `ownerId` envoyé et attribue :

- `status` = `PENDING_VALIDATION` ;
- le propriétaire = compte authentifié ;
- le code public (voir ci-dessous).

Réponse `OwnerAddressResponse` :

```json
{
  "id": "…", "publicCode": "VIL-QUA-2810", "title": "…", "placeType": "HOUSE",
  "district": "…", "city": "…", "description": null, "latitude": 14.5, "longitude": -15.5,
  "status": "PENDING_VALIDATION", "publicationConsent": true, "publiclyAccessible": false,
  "createdAt": "…", "updatedAt": "…", "moderatedAt": null
}
```

`publiclyAccessible` = `publicationConsent && status == VALIDATED` (même règle que la consultation publique).

### `GET /me/addresses?page=0&size=20` → 200

Adresses du compte connecté, plus récentes d'abord. `size` est limité à 50.

```json
{ "items": [ … ], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
```

### `GET /me/addresses/{id}` → 200 / 404

404 si l'adresse n'existe pas ou appartient à un autre compte.

## Annuaire public

`GET /addresses?q=&code=&owner=&district=&city=&type=&page=0&size=20` (anonyme). Seules les adresses `VALIDATED` et publiées. Critères facultatifs, combinables, sans casse, en « contient » (`code` : début du code). `owner` ne trouve que les propriétaires ayant accepté (`ownerNamePublic`). `size` ≤ 50 ; critère de plus de 80 caractères → 400. Réponse : page de `AddressPublicResponse` (avec `ownerName` ou `null`).

## Gestion par le propriétaire

- `PUT /me/addresses/{id}` `{title, placeType, description, publicationConsent, ownerNamePublic}` : position, ville et quartier non modifiables (ils déterminent le code). Si le contenu change sur une adresse validée ou rejetée, elle repasse en `PENDING_VALIDATION`. Archivée → 409.
- `PUT /me/addresses/{id}/location` `{latitude, longitude}` : corrige la position **tant que l'adresse est en `PENDING_VALIDATION`** (409 sinon, 400 hors zone de service). Le code est recalculé pour la nouvelle case (conservé si la case ne change pas).
- `POST /me/addresses/{id}/archive` : définitif, la fiche publique disparaît.
- `POST /me/addresses/{id}/sms` `{phone}` : adresse publique uniquement (409 sinon), 5 par heure (429), 503 sans fournisseur ; réponse `{status: SENT|SIMULATED, provider, to (masqué), link}`.
  - Numéro : `77 123 45 67`, `+221 77 123 45 67`, `00221…` ou `221…` → `+221771234567`. Un numéro sénégalais doit compter **exactement 9 chiffres et commencer par 7** (mobile) ; un fixe (33…) ou un numéro tronqué est refusé (400, `errors.phone`). Les numéros étrangers complets (`+33…`) sont acceptés (E.164).
  - `SENT` = message **accepté par le fournisseur** (Twilio a renvoyé un identifiant `sid` et un statut qui n'est pas `failed`/`undelivered`/`canceled`) ; la remise effective au téléphone n'est pas suivie. `SIMULATED` = **aucun SMS envoyé** (mode `APP_SMS_PROVIDER=simulation`).
  - `link` = `APP_PUBLIC_BASE_URL` + `/a/{code}`. `APP_PUBLIC_BASE_URL` doit être une URL absolue http(s) : sinon l'API refuse de démarrer ; une valeur `localhost` est acceptée mais signalée dans les journaux (le lien ne s'ouvrira pas sur un téléphone).

## Signalements

- `POST /addresses/{code}/reports` (connecté) `{reason: WRONG_LOCATION|NOT_EXISTING|INAPPROPRIATE|DUPLICATE|OTHER, comment}` → 201 ; 400 pour sa propre adresse ; 409 si un signalement du même compte est déjà ouvert.
- `GET /admin/reports?status=OPEN|DISMISSED|ACTION_TAKEN`, `POST /admin/reports/{id}/dismiss`, `POST /admin/reports/{id}/archive-address` (archive l'adresse et clôt ses signalements ouverts).

## Itinéraire

`GET /routes?fromLat=&fromLng=&toCode=&profile=foot-walking|driving-car|cycling-regular` (anonyme, 30/min par IP). Réponse `{profile, distanceMeters, durationSeconds, coordinates: [[lat, lng], …], provider: openrouteservice|osrm}`. Avec `ORS_API_KEY` : OpenRouteService ; sans clé : repli OSRM sans clé (`routing.openstreetmap.de`) ; 503 si aucun des deux n'est disponible (repli désactivé), 429 quota, 422 aucun itinéraire, 502/504 service indisponible.

## Administration : indicateurs et comptes

`GET /admin/stats` (compteurs exacts par statut et type, adresses publiques, signalements ouverts, comptes, 12 derniers mois), `GET /admin/users`, `POST /admin/users/{id}/disable|enable` (pas sur soi-même ni sur un administrateur).

## Code public d'adresse

Format `VIL-QUA-NNNN[S]` :

- `VIL` / `QUA` : 3 premiers caractères alphanumériques de la ville et du quartier, sans accents (`SN` si moins de 2) ;
- `NNNN` : case d'une **grille géographique** SamaAdresse : cases de 0,001° × 0,001° (≈ 110 m × 108 m au Sénégal) regroupées en blocs de 100 × 100 ; `NNNN = ligne × 100 + colonne` dans le bloc (coordonnées arrondies à 6 décimales, découpage « vers le bas ») ;
- `S` : départage si la case est déjà occupée pour ce préfixe : 1 chiffre (essais 1 à 9), puis 2 chiffres (10 à 99) ; au-delà, 503.

Le navigateur calcule hors ligne le même code (essai 0) comme **code provisoire** ; le serveur attribue le code définitif. Unicité : vérification applicative **et** contrainte `UNIQUE uk_addresses_public_code` ; une création concurrente rejetée par la base passe à l'essai suivant (testé sur PostgreSQL réel). Le code ne dépend jamais de l'identité du propriétaire. Les codes créés avant cette version (suffixe aléatoire) restent valides.

## Consultation publique

`GET /addresses/{code}` (anonyme) — code insensible à la casse, format `[A-Z0-9]{2,6}(-[A-Z0-9]{2,6})?-[0-9]{4,8}`.

```json
{ "publicCode": "VIL-QUA-2810", "title": "…", "placeType": "HOUSE", "district": "…", "city": "…",
  "description": null, "latitude": 14.5, "longitude": -15.5, "photoUrl": null }
```

`400` : format invalide. `404` : code absent, adresse non validée, ou publication non consentie. Aucune donnée de compte n'est renvoyée.

## Photo de façade

| Route | Accès | Effet |
|---|---|---|
| `PUT /me/addresses/{id}/photo` (multipart, champ `photo`) | propriétaire | Ajoute ou remplace la photo. **Seulement si l'adresse est `PENDING_VALIDATION`** (sinon `409`) : la photo est vérifiée avec l'adresse. |
| `DELETE /me/addresses/{id}/photo` | propriétaire | Retire la photo (toujours permis). |
| `GET /me/addresses/{id}/photo` | propriétaire | Photo (image/jpeg). |
| `GET /admin/addresses/{id}/photo` | ADMIN | Photo, pour la modération. |
| `GET /addresses/{code}/photo` | anonyme | Photo d'une adresse **publique** (validée + consentement), sinon `404`. |

Les erreurs des routes photo sont toujours renvoyées en `404 application/problem+json`, quel que soit l'en-tête `Accept` du client (auparavant `406` si le client demandait du JSON).

- 5 Mo au maximum (`413` avec `errors.photo` au-delà) ; JPEG ou PNG reconnus par leur signature binaire (le type déclaré est ignoré), 40 mégapixels au plus ; sinon `400` avec `errors.photo`.
- L'image est décodée puis **réencodée en JPEG** (1600 px de côté au maximum) : métadonnées EXIF supprimées, dont la position GPS de l'appareil.
- Réponses `image/jpeg` avec `X-Content-Type-Options: nosniff` ; cache public 10 min pour la route publique, `no-store` sinon.
- `OwnerAddressResponse.hasPhoto` indique la présence d'une photo ; `AddressPublicResponse.photoUrl` vaut `/api/v1/addresses/{code}/photo` ou `null`.
- Stockage : `PhotoStorage` ; implémentation locale dans `APP_PHOTOS_DIRECTORY` (défaut `./data/photos`, ignoré par Git ; volume `samaadresse-photos` dans Docker). Non répliqué : en production, fournir une implémentation persistante (stockage objet).

## Administration (rôle ADMIN)

| Route | Effet |
|---|---|
| `GET /admin/addresses?status=PENDING_VALIDATION&page=0&size=20` | Liste par statut, plus anciennes d'abord |
| `POST /admin/addresses/{id}/validate` | `PENDING_VALIDATION` → `VALIDATED` |
| `POST /admin/addresses/{id}/reject` | `PENDING_VALIDATION` → `REJECTED` |

Une adresse déjà modérée renvoie `409`. La date et l'auteur de la décision sont enregistrés (`moderated_at`, `moderated_by`).
