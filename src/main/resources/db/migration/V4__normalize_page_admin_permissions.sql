WITH RECURSIVE split_permissions (page_admin_account_id, permission_token, remaining_permissions) AS (
    SELECT page_admin_account_id,
           TRIM(SUBSTRING(permission FROM 1 FOR POSITION(',' IN permission) - 1)) AS permission_token,
           TRIM(SUBSTRING(permission FROM POSITION(',' IN permission) + 1)) AS remaining_permissions
    FROM page_admin_account_permission
    WHERE POSITION(',' IN permission) > 0

    UNION ALL

    SELECT page_admin_account_id,
           TRIM(
               CASE
                   WHEN POSITION(',' IN remaining_permissions) > 0
                       THEN SUBSTRING(remaining_permissions FROM 1 FOR POSITION(',' IN remaining_permissions) - 1)
                   ELSE remaining_permissions
               END
           ) AS permission_token,
           TRIM(
               CASE
                   WHEN POSITION(',' IN remaining_permissions) > 0
                       THEN SUBSTRING(remaining_permissions FROM POSITION(',' IN remaining_permissions) + 1)
                   ELSE ''
               END
           ) AS remaining_permissions
    FROM split_permissions
    WHERE remaining_permissions <> ''
)
INSERT INTO page_admin_account_permission (page_admin_account_id, permission)
SELECT DISTINCT split_permission.page_admin_account_id,
       split_permission.permission_token
FROM split_permissions split_permission
WHERE split_permission.permission_token <> ''
  AND NOT EXISTS (
      SELECT 1
      FROM page_admin_account_permission existing_permission
      WHERE existing_permission.page_admin_account_id = split_permission.page_admin_account_id
        AND existing_permission.permission = split_permission.permission_token
  );

DELETE FROM page_admin_account_permission
WHERE POSITION(',' IN permission) > 0;
