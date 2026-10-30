-- =====================================================
-- 生产环境数据库初始化脚本
-- 执行方式：以 root 登录 MySQL 后执行本脚本
-- =====================================================

-- 1. 创建专用数据库账号（最小权限原则）
-- 密码必须替换为强密码，且不要提交到代码库
CREATE USER IF NOT EXISTS 'hr_agent'@'localhost' IDENTIFIED BY 'CHANGE_THIS_STRONG_PASSWORD';
CREATE USER IF NOT EXISTS 'hr_agent'@'127.0.0.1' IDENTIFIED BY 'CHANGE_THIS_STRONG_PASSWORD';

-- 2. 授予最小必要权限
-- 只授予业务表的操作权限，不授予 DROP/TRUNCATE/ALTER 等危险权限
GRANT SELECT, INSERT, UPDATE, DELETE ON hr_spring_ai_adk_agent.* TO 'hr_agent'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON hr_spring_ai_adk_agent.* TO 'hr_agent'@'127.0.0.1';

-- 3. 禁止从远程主机连接（仅限本机）
-- 如果应用和数据库分离部署，需单独授权特定 IP

-- 4. 刷新权限
FLUSH PRIVILEGES;

-- 5. 验证
SELECT user, host FROM mysql.user WHERE user = 'hr_agent';
SHOW GRANTS FOR 'hr_agent'@'localhost';

-- =====================================================
-- 重要提醒：
-- 1. 执行完本脚本后，立即修改 application.yml 中的 DB_PASSWORD 环境变量
-- 2. 如果 root 或业务账号曾使用过出现在任何文档/脚本/聊天记录里的旧密码，
--    视为已泄露，必须在 MySQL 中立即修改：
--    ALTER USER 'root'@'localhost' IDENTIFIED BY '新的强密码';
-- 3. 生产环境建议启用 SSL：
--    - 修改 application.yml: DB_USE_SSL=true
--    - MySQL 配置 require_ssl=ON
-- =====================================================
