# Diagrammes UML — SamaAdresse

Diagrammes au format [Mermaid](https://mermaid.js.org/) : GitHub les affiche directement ; on peut aussi les exporter en image avec <https://mermaid.live>.

## 1. Cas d'utilisation

Mermaid n'a pas de diagramme de cas d'utilisation natif : les acteurs sont à gauche, les cas d'utilisation (ovales) à droite. Un utilisateur connecté (propriétaire) peut aussi faire tout ce que fait le public ; l'administrateur aussi tout ce que fait un propriétaire.

```mermaid
flowchart LR
    Public([Public / visiteur])
    Proprio([Propriétaire connecté])
    Admin([Administrateur])
    ORS[[OpenRouteService]]
    SMS[[Fournisseur SMS : Twilio]]

    subgraph SamaAdresse
        UC1((Consulter une fiche par code ou QR))
        UC2((Rechercher dans l'annuaire : code, propriétaire, quartier, type))
        UC3((Calculer un itinéraire))
        UC4((Consulter hors ligne une fiche déjà vue))
        UC5((S'inscrire / se connecter))
        UC6((Créer une adresse : GPS ou saisie, photo, description))
        UC7((Modifier, publier/masquer, archiver ses adresses))
        UC8((Partager : lien, code, QR, SMS))
        UC9((Signaler une adresse))
        UC10((Valider / rejeter les adresses))
        UC11((Traiter les signalements))
        UC12((Consulter les indicateurs, gérer les comptes))
    end

    Public --- UC1 & UC2 & UC3 & UC4 & UC5
    Proprio --- UC6 & UC7 & UC8 & UC9
    Admin --- UC10 & UC11 & UC12
    UC3 -.-> ORS
    UC8 -.-> SMS
```

## 2. Classes (domaine et services principaux)

```mermaid
classDiagram
    class UserAccount {
        UUID id
        String email
        String passwordHash
        String displayName
        Role role
        boolean enabled
        Instant createdAt
        promoteToAdmin()
        setEnabled(boolean)
    }
    class Role {
        <<enumeration>>
        USER
        ADMIN
    }
    class Address {
        UUID id
        String publicCode
        String title
        PlaceType placeType
        String district
        String city
        String description
        BigDecimal latitude
        BigDecimal longitude
        String photoKey
        AddressStatus status
        boolean publiclyVisible
        boolean ownerNamePublic
        Instant createdAt / updatedAt / moderatedAt
        moderate(decision, moderatorId)
        editContent(title, type, description) bool
        changeVisibility(consent, ownerNamePublic)
        archive(byUserId)
        attachPhoto(key) / removePhoto()
        isPubliclyAccessible() bool
    }
    class AddressStatus {
        <<enumeration>>
        PENDING_VALIDATION
        VALIDATED
        REJECTED
        ARCHIVED
    }
    class PlaceType {
        <<enumeration>>
        HOUSE
        BUSINESS
        INSTITUTION
        OTHER
    }
    class AddressReport {
        UUID id
        ReportReason reason
        String comment
        ReportStatus status
        Instant createdAt / resolvedAt
        resolve(outcome, adminId)
    }
    class AddressCodeGenerator {
        candidate(city, district, lat, lng, attempt) String
        gridCell(lat, lng) String
    }
    class OwnerAddressService {
        create(ownerId, NewAddress) Address
        update(...) bool
        archive(ownerId, id) Address
    }
    class AddressService {
        findValidatedPublicAddress(code) Address
        searchDirectory(query, page, size) Page
    }
    class ReportService
    class AddressPhotoService
    class PhotoStorage {
        <<interface>>
        store(bytes) String
        load(key) Optional
        delete(key)
    }
    class SmsProvider {
        <<interface>>
        send(to, body) SmsResult
    }

    UserAccount "1" --> "*" Address : possède
    UserAccount --> Role
    Address --> AddressStatus
    Address --> PlaceType
    AddressReport "*" --> "1" Address : concerne
    AddressReport "*" --> "1" UserAccount : signalé par
    OwnerAddressService ..> AddressCodeGenerator
    OwnerAddressService ..> Address
    AddressService ..> Address
    ReportService ..> AddressReport
    AddressPhotoService ..> PhotoStorage
    PhotoStorage <|.. LocalPhotoStorage
    SmsProvider <|.. TwilioSmsProvider
    SmsProvider <|.. SimulatedSmsProvider
```

## 3. Séquence : créer une adresse (GPS, code unique, photo)

```mermaid
sequenceDiagram
    actor P as Propriétaire
    participant N as Navigateur (PWA)
    participant API as API Spring Boot
    participant G as AddressCodeGenerator
    participant DB as PostgreSQL
    participant S as PhotoStorage

    P->>N: « Utiliser ma position » (autorisation)
    N->>N: GPS → lat/lng ; code provisoire VIL-QUA-NNNN calculé localement (hors ligne possible)
    P->>N: informations, photo, consentement
    alt connexion indisponible
        N->>N: brouillon local « en attente de synchronisation »
    end
    N->>API: POST /api/v1/me/addresses (JWT)
    API->>API: validation (champs, zone Sénégal)
    loop essai 0, 1, 2… (au plus 100)
        API->>G: candidate(ville, quartier, lat, lng, essai)
        API->>DB: existsByPublicCode ?
        API->>DB: INSERT (transaction dédiée)
        alt contrainte UNIQUE violée (création concurrente)
            DB-->>API: uk_addresses_public_code
        else succès
            DB-->>API: adresse PENDING_VALIDATION
        end
    end
    API-->>N: 201 + code définitif
    N->>API: PUT /me/addresses/{id}/photo (multipart)
    API->>API: signature JPEG/PNG, ≤ 5 Mo, réencodage sans EXIF
    API->>S: store(jpeg)
    API-->>N: 200 hasPhoto=true
```

## 4. Séquence : consulter une fiche publique et l'itinéraire

```mermaid
sequenceDiagram
    actor V as Visiteur
    participant N as Navigateur
    participant SW as Service Worker
    participant API as API
    participant ORS as OpenRouteService

    V->>N: scanne le QR → /a/ZIG-KAN-6826
    N->>SW: GET /api/v1/addresses/ZIG-KAN-6826
    alt en ligne
        SW->>API: requête réseau
        API-->>SW: 200 (adresse validée et publique) ou 404
        SW->>SW: copie en cache (données publiques uniquement)
    else hors ligne
        SW-->>N: dernière copie en cache (ou erreur hors ligne)
    end
    V->>N: « Itinéraire depuis ma position » (autorisation GPS)
    N->>API: GET /api/v1/routes?fromLat&fromLng&toCode
    API->>ORS: /v2/directions (clé côté serveur)
    ORS-->>API: GeoJSON
    API-->>N: tracé + distance + durée (ou 503/429/502 explicites)
```

## 5. Séquence : signalement traité par un administrateur

```mermaid
sequenceDiagram
    actor U as Utilisateur connecté
    actor A as Administrateur
    participant API as API
    participant DB as Base

    U->>API: POST /api/v1/addresses/{code}/reports {reason, comment}
    API->>DB: adresse publique ? signalement déjà ouvert ?
    API-->>U: 201 OPEN (409 si doublon, 400 si sa propre adresse)
    A->>API: GET /api/v1/admin/reports?status=OPEN
    alt signalement non fondé
        A->>API: POST /admin/reports/{id}/dismiss
    else signalement fondé
        A->>API: POST /admin/reports/{id}/archive-address
        API->>DB: adresse ARCHIVED + signalements ouverts → ACTION_TAKEN
    end
```
