-- Comptes utilisateurs. Le mot de passe n'est jamais stocké en clair :
-- password_hash contient une empreinte BCrypt préfixée ({bcrypt}...).
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- L'application enregistre l'e-mail en minuscules : l'unicité est donc insensible à la casse.
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);

-- Propriétaire, consentement et traçabilité de modération.
-- owner_id reste nullable pour ne pas casser d'éventuelles lignes créées avant les comptes ;
-- toute adresse créée par l'API reçoit un propriétaire.
ALTER TABLE addresses ADD COLUMN owner_id UUID;
ALTER TABLE addresses ADD COLUMN publication_consent_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE addresses ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE addresses ADD COLUMN moderated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE addresses ADD COLUMN moderated_by UUID;

ALTER TABLE addresses ADD CONSTRAINT fk_addresses_owner
    FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE addresses ADD CONSTRAINT fk_addresses_moderator
    FOREIGN KEY (moderated_by) REFERENCES users (id) ON DELETE SET NULL;

CREATE INDEX idx_addresses_owner ON addresses (owner_id, created_at);
