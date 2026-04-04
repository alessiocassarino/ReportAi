-- Create APP_USER table
CREATE TABLE IF NOT EXISTS app_user (
    id UUID PRIMARY KEY NOT NULL,
    email VARCHAR(120) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN DEFAULT TRUE NOT NULL,
    account_locked BOOLEAN DEFAULT FALSE NOT NULL,
    account_expired BOOLEAN DEFAULT FALSE NOT NULL,
    credentials_expired BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_login TIMESTAMP,
    updated_at TIMESTAMP
);

-- Create index on email
CREATE INDEX IF NOT EXISTS idx_app_user_email ON app_user(email);

