-- Troisième version du schéma : annuaire public et signalements.
-- Migration additive : aucune donnée existante n'est modifiée ou supprimée.

-- Le nom du propriétaire n'apparaît dans l'annuaire que s'il l'accepte explicitement.
ALTER TABLE addresses ADD COLUMN owner_name_public BOOLEAN NOT NULL DEFAULT FALSE;

-- Index des critères de l'annuaire (statut/visibilité, type, quartier).
CREATE INDEX idx_addresses_public_search ON addresses (status, publicly_visible, place_type);
CREATE INDEX idx_addresses_district ON addresses (district);

-- Signalement d'une adresse publique par un utilisateur connecté, traité par un administrateur.
CREATE TABLE address_reports (
    id UUID PRIMARY KEY,
    address_id UUID NOT NULL,
    reporter_id UUID NOT NULL,
    reason VARCHAR(32) NOT NULL,
    comment VARCHAR(500),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolved_by UUID,
    CONSTRAINT fk_reports_address FOREIGN KEY (address_id) REFERENCES addresses (id) ON DELETE CASCADE,
    CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_reports_resolver FOREIGN KEY (resolved_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_reports_reason CHECK (reason IN ('WRONG_LOCATION', 'NOT_EXISTING', 'INAPPROPRIATE', 'DUPLICATE', 'OTHER')),
    CONSTRAINT ck_reports_status CHECK (status IN ('OPEN', 'DISMISSED', 'ACTION_TAKEN'))
);

CREATE INDEX idx_reports_status ON address_reports (status, created_at);
CREATE INDEX idx_reports_address ON address_reports (address_id, reporter_id, status);
