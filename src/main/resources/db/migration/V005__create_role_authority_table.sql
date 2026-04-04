-- Create ROLE_AUTHORITY join table
CREATE TABLE IF NOT EXISTS role_authority (
    role_id UUID NOT NULL,
    authority_id UUID NOT NULL,
    PRIMARY KEY (role_id, authority_id),
    FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE,
    FOREIGN KEY (authority_id) REFERENCES authority(id) ON DELETE CASCADE
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_role_authority_role ON role_authority(role_id);
CREATE INDEX IF NOT EXISTS idx_role_authority_authority ON role_authority(authority_id);

