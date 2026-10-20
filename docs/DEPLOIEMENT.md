# Déploiement sur un cloud gratuit (guide — rien n'a été déployé)

Ce guide prépare le déploiement demandé par le cahier des charges. **Aucun déploiement n'a été lancé** : il nécessite vos comptes et votre accord.

## Architecture proposée

| Élément | Service gratuit suggéré | Remarque |
|---|---|---|
| Base PostgreSQL | Render PostgreSQL (ou Neon) | Les migrations Flyway s'appliquent au démarrage de l'API |
| API Spring Boot | Render « Web Service » à partir du `Dockerfile` | L'offre gratuite se met en veille : premier appel lent |
| Photos de façade | Disque persistant Render (payant) ou stockage objet | Sans disque persistant, les photos sont perdues à chaque redéploiement : à signaler lors de la démonstration |
| Frontend PWA | Netlify ou Vercel (site statique `dist/`) | HTTPS obligatoire pour le Service Worker et la géolocalisation |

## 1. API (Render)

1. Créez la base PostgreSQL ; notez l'URL JDBC, l'utilisateur et le mot de passe.
2. Créez un *Web Service* relié au dépôt `sama-Adresseback`, environnement **Docker**.
3. Variables d'environnement (jamais dans Git) :

| Variable | Valeur |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` (secret JWT obligatoire, Swagger désactivé ; retirez-le temporairement pour une démonstration Swagger) |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | valeurs de la base |
| `APP_JWT_SECRET` | `openssl rand -base64 48` |
| `APP_CORS_ALLOWED_ORIGINS` | URL exacte du frontend, ex. `https://samaadresse.netlify.app` |
| `APP_PUBLIC_BASE_URL` | la même URL (liens envoyés par SMS) |
| `APP_SMS_PROVIDER` + `TWILIO_*` | `twilio` avec vos identifiants de test, ou vide |
| `ORS_API_KEY` | clé gratuite openrouteservice.org, ou vide |
| `APP_BOOTSTRAP_ADMIN_EMAIL` | e-mail d'un compte déjà inscrit à promouvoir administrateur |

4. Vérification : `GET https://<api>/api/v1/health` → `{"status":"UP","database":"UP"}`.

## 2. Frontend (Netlify)

1. Site relié au dépôt `sama-AdresseWeb` ; commande `npm ci && npm run build` ; dossier publié `dist`.
2. Variable `VITE_API_BASE_URL=https://<api>` (publique, sans secret).
3. Règle de réécriture pour les routes de l'application : fichier `public/_redirects` contenant `/* /index.html 200` (à ajouter au moment du déploiement).
4. Vérifier dans Chrome : DevTools → Application → Manifest (installable) et Service Worker actif.

## 3. Contrôles après déploiement

- Inscription, création d'adresse, validation par l'administrateur, fiche publique, QR code scanné avec un téléphone.
- Fiche consultée puis mode avion : la fiche reste affichée avec la mention « copie hors ligne ».
- SMS : en mode test Twilio, utiliser les numéros « magiques » de la documentation Twilio ; le résultat doit être `SENT`.
