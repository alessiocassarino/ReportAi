-- Create ROLE table
CREATE TABLE IF NOT EXISTS role (
    id UUID PRIMARY KEY NOT NULL,
    name VARCHAR(50) UNIQUE NOT NULL,
    description TEXT
);

