# Base de données

PostgreSQL 16, schéma géré par Flyway (`src/main/resources/db/migration`). Hibernate est en mode `validate` : il vérifie le schéma mais ne le modifie jamais.

## V1 — `addresses`

| Champ | Rôle |
|---|---|
| `id` | Identifiant interne UUID, indépendant du code partageable. |
| `public_code` | Code d'adresse, contrainte `UNIQUE uk_addresses_public_code`. |
| `title`, `place_type` | Nom et catégorie du lieu (`HOUSE`, `BUSINESS`, `INSTITUTION`, `OTHER`). |
| `district`, `city` | Saisis par le propriétaire, jamais déduits du GPS. |
| `description`, `photo_url` | Facultatifs. `photo_url` n'est pas encore alimenté par l'API. |
| `latitude`, `longitude` | `NUMERIC(9,6)` / `NUMERIC(10,6)`, bornés par contraintes `CHECK`. |
| `status` | `PENDING_VALIDATION`, `VALIDATED`, `REJECTED`, `ARCHIVED`. |
| `publicly_visible` | Consentement à la publication ; `FALSE` par défaut. |
| `created_at` | Date de création UTC. |

## V2 — comptes et propriété (`V2__users_and_address_ownership.sql`)

Table `users` :

| Champ | Rôle |
|---|---|
| `id` | UUID interne (sujet du jeton). |
| `email` | En minuscules, `UNIQUE uk_users_email`. |
| `password_hash` | Empreinte BCrypt préfixée `{bcrypt}`. |
| `display_name` | Nom affiché. |
| `role` | `USER` (défaut) ou `ADMIN`, contrainte `CHECK`. |
| `enabled` | Compte actif ; un compte désactivé est refusé à chaque requête. |
| `created_at` | Date d'inscription. |

Colonnes ajoutées à `addresses` : `owner_id` (FK `users`, `ON DELETE RESTRICT`), `publication_consent_at`, `updated_at`, `moderated_at`, `moderated_by` (FK `users`, `ON DELETE SET NULL`), index `(owner_id, created_at)`.

`owner_id` est nullable pour rester compatible avec d'éventuelles lignes créées avant V2 ; toute adresse créée par l'API a un propriétaire.

La migration V2 est purement additive (aucune suppression ni modification de données). Elle a été appliquée sans incident sur la base Docker locale existante en V1.

## Transitions de statut

```
PENDING_VALIDATION ──validate──▶ VALIDATED
        └─────────reject──────▶ REJECTED
```

`ARCHIVED` est prévu dans le schéma mais aucune route ne l'utilise encore.
