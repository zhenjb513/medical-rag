-- demo 档的业务库种子数据（容器首次启动时由 mysql 镜像自动执行）。
--
-- 表结构只按 InventoryService 实际用到的那两条 SQL 来：
--   SELECT stock FROM consumables WHERE name = ?
--   SELECT COUNT(*) FROM consumables
-- 第三个名字（骨科缝合包）故意不塞：问它就能看到「未登记」那一态，
-- 而不是被旧代码吞成库存 0、模型据此答「缺货，建议联系采购」。
-- 名称要和页面上那个「🔧 查库存」快捷按钮对得上（index.html:40 问的是胃镜活检钳）。
--
-- 数据是合成的示例库存，跟真实医院无关。

-- 显式 USE：官方 mysql 镜像的 init 脚本默认库取 MYSQL_DATABASE，写死一次免得哪天改环境变量就建到别处去。
USE `mkbAI`;

CREATE TABLE IF NOT EXISTS consumables (
    name  VARCHAR(128) NOT NULL PRIMARY KEY,
    stock INT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO consumables (name, stock) VALUES
    ('胃镜活检钳', 12),
    ('一次性使用无菌注射器', 500),
    ('中心静脉导管', 3)
ON DUPLICATE KEY UPDATE stock = VALUES(stock);