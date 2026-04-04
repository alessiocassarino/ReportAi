-- Create AUTHORITY table
CREATE TABLE IF NOT EXISTS authority (
    id UUID PRIMARY KEY NOT NULL,
    name VARCHAR(100) UNIQUE NOT NULL,
    description TEXT
);

