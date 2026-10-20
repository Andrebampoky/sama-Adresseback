# Sécurité — état et limites

## Authentification

- **Mots de passe** : hachés avec BCrypt (`DelegatingPasswordEncoder`, préfixe `{bcrypt}` pour permettre un changement d'algorithme futur). Jamais stockés, journalisés ni renvoyés en clair. Longueur 10 à 72 caractères (au-delà de 72 octets BCrypt tronquerait silencieusement : on refuse).
- **Jetons** : JWT signés HS256 par l'API (Spring Security OAuth2 Resource Server / Nimbus). Contenu minimal : émetteur `samaadresse-api`, sujet = identifiant interne du compte, dates d'émission et d'expiration. Durée par défaut : 1 h (`APP_JWT_VALIDITY`, format ISO 8601, ex. `PT30M`).
- **Clé de signature** : `APP_JWT_SECRET` (Base64, ≥ 32 octets), à générer avec `openssl rand -base64 48`. Aucune clé n'est versionnée. Si elle est absente, une clé aléatoire éphémère est générée au démarrage (avertissement dans les journaux ; sessions perdues à chaque redémarrage). Le profil `prod` exige la variable (`APP_JWT_REQUIRE_SECRET=true`) et refuse de démarrer sans elle.
- **Rôle et état relus à chaque requête** : le rôle n'est pas inscrit dans le jeton ; `AccountJwtConverter` recharge le compte. Une promotion, une rétrogradation ou une désactivation s'applique immédiatement.
- **Force brute** : 5 échecs en 15 minutes par e-mail → `429`. Réponse identique pour un e-mail inconnu ou un mauvais mot de passe, avec une comparaison BCrypt factice pour égaliser le temps de réponse.
- **Énumération** : l'inscription répond `409` si l'e-mail existe déjà. C'est un compromis courant pour l'expérience utilisateur ; une vérification par e-mail le supprimerait (non implémentée).

## Contrôle d'accès

- Défini côté serveur dans `SecurityConfig` : routes publiques explicites, `/api/v1/admin/**` réservé à `ROLE_ADMIN`, tout le reste authentifié. Masquer un bouton dans React n'est pas une protection et n'est pas utilisé comme tel.
- Les adresses d'un propriétaire sont filtrées par son identifiant en base (`findByIdAndOwnerId`) : un autre compte reçoit `404`.
- `publicCode`, `status` et propriétaire ne sont jamais acceptés depuis le client.
- Couverture : `AccessControlIntegrationTest` (anonyme / utilisateur / admin, jetons forgés `alg: none` et signatures altérées, promotion et désactivation en direct, CORS).

## Compte administrateur

Aucun compte ni mot de passe administrateur n'est créé par le code ou les migrations. Procédure :

1. la personne s'inscrit normalement par l'API ou le site ;
2. l'exploitant démarre l'API avec `APP_BOOTSTRAP_ADMIN_EMAIL=<son e-mail>` ;
3. au démarrage, `AdminBootstrap` promeut ce compte existant en `ADMIN` (journal : « Compte promu administrateur… ») ; si l'e-mail est inconnu, rien n'est fait.

Retirer ensuite la variable n'annule pas la promotion. Pour rétrograder : `UPDATE users SET role = 'USER' WHERE email = '…';`.

## CSRF, CORS, transport

- Pas de cookie de session : le jeton est transmis par l'en-tête `Authorization`, qu'un site tiers ne peut pas faire ajouter automatiquement par le navigateur. La protection CSRF est donc désactivée volontairement.
- CORS : uniquement les origines de `APP_CORS_ALLOWED_ORIGINS` (séparées par des virgules), méthodes `GET, POST, PATCH, DELETE, OPTIONS`, en-têtes `Accept, Content-Type, Authorization`, sans identifiants. Une origine inconnue reçoit `403` sur la requête préliminaire.
- HTTPS n'est pas géré par l'application : à assurer par le proxy ou l'hébergeur en production.

## Données personnelles

- La fiche publique ne contient ni e-mail, ni nom de compte, ni identifiant du propriétaire.
- Publication seulement si le propriétaire a consenti explicitement (`publicationConsent`, horodaté dans `publication_consent_at`) **et** si l'adresse a été validée.
- Les données d'exemple et de test sont fictives (`@exemple.test`, « Ville Exemple »).

## Limites connues

- Le limiteur de tentatives est en mémoire (une seule instance, remis à zéro au redémarrage).
- Pas de révocation individuelle d'un jeton avant expiration (hors désactivation du compte).
- Pas encore de vérification d'e-mail ni de récupération de mot de passe.
- Pas encore de journal d'audit dédié : seules la date et l'auteur de la décision de modération sont conservés.
- Aucune interface ni route pour désactiver un compte : à faire avec la console d'administration.
