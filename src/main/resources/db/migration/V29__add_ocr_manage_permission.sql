INSERT IGNORE INTO permission (permission_code, permission_name, permission_type, created_by, updated_by)
VALUES ('ocr:manage', 'OCR Manage', 'API', 1, 1);

INSERT IGNORE INTO role_permission (role_id, permission_id, created_by, updated_by)
SELECT roles.role_id, p.id, 1, 1
FROM permission p
CROSS JOIN (SELECT 1 AS role_id UNION ALL SELECT 2 UNION ALL SELECT 3) roles
WHERE p.permission_code = 'ocr:manage' AND p.deleted = 0;
