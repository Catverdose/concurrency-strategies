SET SESSION cte_max_recursion_depth = 10000;

INSERT IGNORE INTO app_user (user_id, login_id, name, email)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 10000
)
SELECT
    n,
    CONCAT('load-user-', n),
    CONCAT('Load User ', n),
    CONCAT('load-user-', n, '@example.com')
FROM seq;
