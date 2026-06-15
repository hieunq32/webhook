INSERT INTO page_admin_account_permission (page_admin_account_id, permission)
SELECT permission_row.page_admin_account_id,
       TRIM(split_permission.permission_token) AS permission
FROM page_admin_account_permission permission_row
CROSS JOIN LATERAL regexp_split_to_table(permission_row.permission, '\s*,\s*') AS split_permission(permission_token)
WHERE POSITION(',' IN permission_row.permission) > 0
ON CONFLICT (page_admin_account_id, permission) DO NOTHING;

DELETE FROM page_admin_account_permission
WHERE POSITION(',' IN permission) > 0;
