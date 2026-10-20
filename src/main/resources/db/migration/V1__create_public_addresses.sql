CREATE TABLE addresses (
    id UUID PRIMARY KEY,
    public_code VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL,
    place_type VARCHAR(24) NOT NULL,
    district VARCHAR(100) NOT NULL,
    city VARCHAR(100) NOT NULL,
    description TEXT,
    latitude NUMERIC(9, 6) NOT NULL,
    longitude NUMERIC(10, 6) NOT NULL,
    photo_url TEXT,
    status VARCHAR(32) NOT NULL,
    publicly_visible BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_addresses_public_code UNIQUE (public_code),
    CONSTRAINT ck_addresses_place_type CHECK (place_type IN ('HOUSE', 'BUSINESS', 'INSTITUTION', 'OTHER')),
    CONSTRAINT ck_addresses_status CHECK (status IN ('PENDING_VALIDATION', 'VALIDATED', 'REJECTED', 'ARCHIVED')),
    CONSTRAINT ck_addresses_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_addresses_longitude CHECK (longitude BETWEEN -180 AND 180)
);

CREATE INDEX idx_addresses_status ON addresses (status);
