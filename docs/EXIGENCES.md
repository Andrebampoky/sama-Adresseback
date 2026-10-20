# Matrice exigences → implémentation → preuves

Source : brief « Projet 12 / 14 — SamaAdresse » (`references/brief-projet-original.pdf`).
Statuts : **Réalisée et vérifiée** (comportement démontré par un test automatique ou une vérification concrète), **Partielle**, **Bloquée** (dépend d'une clé ou d'un accès externe).

| # | Exigence du brief | Implémentation (backend / frontend) | Preuves | Statut |
|---|---|---|---|---|
| A1 | Capture GPS automatique ou saisie manuelle | Frontend `NewAddressPage` (géolocalisation après accord, carte cliquable, saisie) ; backend : bornes + zone Sénégal | `AccountFlow.test.tsx` (zone, saisie, erreurs serveur) ; parcours Chrome (position simulée, saisie) | Réalisée et vérifiée |
| A2 | Photo de façade | `PUT/DELETE/GET /me/addresses/{id}/photo`, `PhotoProcessor` (signature JPEG/PNG, 5 Mo, 40 Mpx, réencodage sans EXIF) | `AddressPhotoApiIntegrationTest`, `PhotoUnitTest` ; Chrome : 3000×2000 + GPS EXIF → 1600×1067 sans EXIF, 15 Mo → 413 | Réalisée et vérifiée |
| A3 | Description textuelle, type de lieu | `CreateAddressRequest` validé, `PlaceType` | `OwnerAddressApiIntegrationTest.rejectsInvalidFieldsAndCoordinates` | Réalisée et vérifiée |
| A4 | Code court unique (ex. ZIG-KBG-4271) basé sur le GPS et une grille | `AddressCodeGenerator` : VIL-QUA + case de grille 0,001° (≈ 110 m), départage 1-2 chiffres ; contrainte `UNIQUE` + nouvel essai | `AddressCodeGeneratorTest`, `OwnerAddressServiceTest`, `PostgresAddressCodeIntegrationTest` (12 créations simultanées, même case, PostgreSQL réel) ; API réelle : `ZIG-KAN-6826` puis `ZIG-KAN-68261`… | Réalisée et vérifiée |
| A5 | Génération < 3 s sans connexion stable | Code **provisoire** calculé dans le navigateur (`provisionalCode`, même algorithme) ; adresse gardée en brouillon IndexedDB et synchronisée au retour du réseau ; code définitif confirmé par le serveur | `addressCode.test.ts` (mêmes valeurs que Java) ; Chrome hors ligne : brouillon `ZIG-BOU-8030` puis synchronisation automatique | Réalisée et vérifiée (code définitif seulement en ligne, par conception) |
| B1 | Unicité garantie, collisions gérées | `uk_addresses_public_code` + transaction par essai, détection du nom de contrainte | `PostgresAddressCodeIntegrationTest`, `OwnerAddressServiceTest.retriesWhenDatabaseRejectsConcurrentDuplicate` | Réalisée et vérifiée |
| C1 | QR code téléchargeable et imprimable | Frontend `SharePage` (PNG, SVG, impression), URL `/a/{code}` | `PublicAndAdmin.test.tsx` (contenu exact du QR) ; Chrome | Réalisée et vérifiée |
| C2 | Lien de partage, copie du code | Fiche publique et page de partage | Tests frontend, Chrome | Réalisée et vérifiée |
| C3 | SMS automatique avec le lien (SendFlash/Twilio sandbox) | `POST /me/addresses/{id}/sms`, `TwilioSmsProvider`, mode `simulation` (jamais présenté comme envoyé), 503 si non configuré, 5 SMS/h | `TwilioSmsProviderTest` (faux serveur Twilio), `SmsAndRouteIntegrationTest`, `UnconfiguredIntegrationsTest` ; frontend `OwnerAndPublicActions.test.tsx` | **Partielle** : code testé ; envoi réel non vérifié faute d'identifiants Twilio. SendFlash non implémenté (documentation de l'API non disponible) |
| D1 | Fiche publique : carte, photo, description | `GET /addresses/{code}`, `GET /addresses/{code}/photo` | `AddressApiIntegrationTest`, `AddressPhotoApiIntegrationTest`, Chrome | Réalisée et vérifiée |
| D2 | Itinéraire depuis la position (OpenRouteService) | `GET /routes` (clé côté serveur), tracé Leaflet, repli OpenStreetMap | `SmsAndRouteIntegrationTest` (faux ORS : 200, 429, 404, 500), `AuthAndControllersUnitTest` ; frontend : service indisponible sans plantage | Réalisée et vérifiée avec le repli OSRM sans clé (API réelle : 980 m, 13 min à pied, tracé affiché dans Chrome) ; **appel OpenRouteService réel à vérifier** dès qu'une clé `ORS_API_KEY` est fournie |
| E1 | Annuaire : code, propriétaire, quartier, type | `GET /addresses?q&code&owner&district&city&type` (Criteria JPA, pagination ≤ 50), nom du propriétaire seulement s'il l'autorise | `DirectoryAndOwnerApiIntegrationTest`, `PostgresAddressCodeIntegrationTest.publicDirectorySearchWorksOnPostgres` ; Chrome | Réalisée et vérifiée |
| F1 | Fiche consultée disponible hors ligne (Service Worker + Cache API) | `public/sw.js` : fiches publiques (30 max), application, tuiles ; jamais `/me`, `/admin`, ni requêtes avec jeton | Chrome (réseau coupé pour la page ET le Service Worker) : fiche vue → affichée avec la mention « copie hors ligne » ; fiche jamais vue → message clair | Réalisée et vérifiée |
| F2 | PWA installable (manifest + SW + icône) | `manifest.webmanifest`, icônes 192/512/maskable | Chrome DevTools `Page.getInstallabilityErrors` : aucune erreur | Réalisée et vérifiée |
| G1 | API publique : code → GPS + photo, Swagger | `GET /addresses/{code}` (+ `photoUrl`), Swagger UI, `docs/openapi.json` (OpenAPI **3.0.1**) | `OpenApiContractTest` | Réalisée et vérifiée |
| H1 | Rôles Admin / Propriétaire / Public | Spring Security, rôles `USER` et `ADMIN` relus en base à chaque requête | `AccessControlIntegrationTest`, tests 401/403/404 dans chaque suite | Réalisée et vérifiée |
| H2 | Admin : validation des adresses signalées | `POST /addresses/{code}/reports`, `GET /admin/reports`, `dismiss`, `archive-address` ; modération `validate/reject` | `ReportAndAdminApiIntegrationTest`, `DomainAndServicesUnitTest` ; Chrome (signalement → écarté) | Réalisée et vérifiée |
| H3 | Propriétaire : gestion de ses adresses | `PUT /me/addresses/{id}` (retour en vérification si le contenu change), `POST …/archive`, propriété vérifiée (404 pour autrui) | `DirectoryAndOwnerApiIntegrationTest`, `OwnerAddressServiceTest` | Réalisée et vérifiée |
| T1 | JWT sécurisé | HS256, émetteur vérifié, expiration, compte désactivé refusé immédiatement | `AuthApiIntegrationTest`, `AccessControlIntegrationTest`, `ReportAndAdminApiIntegrationTest` (désactivation) | Réalisée et vérifiée |
| T2 | Mobile-first, responsive ≥ 360 px | Barre d'onglets mobile, mises en page fluides | Captures Chrome 375/390/768/1366/1440 px, contrôle du débordement horizontal | Réalisée et vérifiée |
| L1 | Tests unitaires ≥ 60 % + intégration | JaCoCo (seuil 60 % bloquant), Vitest + couverture V8 (seuil 60 %) | Voir « Mesures » ci-dessous | Réalisée et vérifiée |
| L2 | README, UML, OpenAPI | `README.md`, `docs/UML.md`, `docs/openapi.json` | — | Réalisée |
| L3 | Déploiement cloud gratuit | Guide `docs/DEPLOIEMENT.md` (Render + Netlify) | Non déployé : nécessite votre accord et vos comptes | **Bloquée** (autorisation) |
| L4 | Branches feature / develop / main | Stratégie décrite dans le README | Dépôts encore sans commit : aucune branche créée sans votre accord | **Bloquée** (autorisation) |
| O1 | Option plaque physique + Wave sandbox | — | — | Non commencée (optionnelle) |

## Mesures (19 octobre 2026)

| Mesure | Commande | Résultat |
|---|---|---|
| Tests backend | `mvn verify` (Java 25.0.4.1, 20 octobre 2026) | 74 tests, 0 échec, 0 ignoré (dont PostgreSQL 16 réel via Testcontainers) |
| Couverture backend, tous tests | rapport JaCoCo | lignes 92,8 % · branches 83,8 % |
| Couverture backend, tests unitaires seuls | `mvn test jacoco:report -Dtest=<classes unitaires>` | lignes 61,4 % · branches 68,4 % (mesure du 19 octobre, non refaite le 20) |
| Tests frontend | `npm test` | 74 tests, 0 échec |
| Couverture frontend | `npm run coverage` | lignes 76,0 % · branches 78,1 % |
