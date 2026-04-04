-- Insert default authorities
INSERT INTO authority (id, name, description) VALUES
(gen_random_uuid(), 'READ_CONTRACTS', 'Can read contracts'),
(gen_random_uuid(), 'CREATE_CONTRACTS', 'Can create contracts'),
(gen_random_uuid(), 'UPDATE_CONTRACTS', 'Can update contracts'),
(gen_random_uuid(), 'DELETE_CONTRACTS', 'Can delete contracts'),
(gen_random_uuid(), 'READ_ESTIMATES', 'Can read estimates'),
(gen_random_uuid(), 'CREATE_ESTIMATES', 'Can create estimates'),
(gen_random_uuid(), 'UPDATE_ESTIMATES', 'Can update estimates'),
(gen_random_uuid(), 'DELETE_ESTIMATES', 'Can delete estimates'),
(gen_random_uuid(), 'MANAGE_USERS', 'Can manage users'),
(gen_random_uuid(), 'MANAGE_ROLES', 'Can manage roles')
ON CONFLICT DO NOTHING;

-- Insert default roles
INSERT INTO role (id, name, description) VALUES
(gen_random_uuid(), 'USER', 'Standard user role'),
(gen_random_uuid(), 'ANALYST', 'Analyst role'),
(gen_random_uuid(), 'ADMIN', 'Administrator role')
ON CONFLICT (name) DO NOTHING;

