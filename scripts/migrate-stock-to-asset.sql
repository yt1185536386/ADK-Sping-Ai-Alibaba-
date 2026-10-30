-- ============================================================
-- 数据迁移：为现有 wms_stock 中的散装库存补生成 wms_asset 记录
-- 
-- 背景：系统原设计为 wms_stock（散装账）与 wms_asset（资产账）双轨并行，
-- 但入库只写 stock，盘点只查 asset，导致"有库存但盘点看不见"。
-- 本脚本将现有散装库存全部登记为「在库」状态的资产，实现统一资产管理。
--
-- 执行方式：在 MySQL 中执行
-- 注意：执行前请备份数据库
-- ============================================================

-- 1. 创建临时序列表（用于生成资产编号）
DROP TEMPORARY TABLE IF EXISTS temp_asset_seq;
CREATE TEMPORARY TABLE temp_asset_seq (
    seq BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT,
    warehouse_id BIGINT,
    qty INT
);

-- 2. 为每个库存记录生成对应数量的资产编号占位
-- 例如：stock 中有 item_id=1, warehouse_id=1, quantity=50，则生成 50 条记录
INSERT INTO temp_asset_seq (item_id, warehouse_id, qty)
SELECT 
    s.item_id,
    s.warehouse_id,
    t.n
FROM wms_stock s
CROSS JOIN (
    -- 生成 1-1000 的数字序列（足够覆盖单条库存记录的数量）
    SELECT 
        (hundreds.n * 100 + tens.n * 10 + ones.n + 1) AS n
    FROM 
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) ones
        CROSS JOIN (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) tens
        CROSS JOIN (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) hundreds
) t
WHERE t.n <= s.quantity;

-- 3. 生成资产编号并插入 wms_asset
-- 资产编号格式：AST-YYYYMMDD-XXXX（与现有格式一致）
DELIMITER $$

DROP PROCEDURE IF EXISTS migrate_stock_to_asset $$
CREATE PROCEDURE migrate_stock_to_asset()
BEGIN
    DECLARE done INT DEFAULT FALSE;
    DECLARE v_item_id BIGINT;
    DECLARE v_warehouse_id BIGINT;
    DECLARE v_seq BIGINT;
    DECLARE v_asset_no VARCHAR(50);
    DECLARE v_date_str VARCHAR(8);
    DECLARE cur CURSOR FOR SELECT item_id, warehouse_id, seq FROM temp_asset_seq ORDER BY item_id, warehouse_id, seq;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
    
    SET v_date_str = DATE_FORMAT(NOW(), '%Y%m%d');
    
    OPEN cur;
    
    read_loop: LOOP
        FETCH cur INTO v_item_id, v_warehouse_id, v_seq;
        IF done THEN
            LEAVE read_loop;
        END IF;
        
        -- 生成资产编号：AST-YYYYMMDD-XXXX（按日期+顺序）
        SET v_asset_no = CONCAT('AST-', v_date_str, '-', LPAD(v_seq, 4, '0'));
        
        -- 检查是否已存在（防止重复执行）
        IF NOT EXISTS (SELECT 1 FROM wms_asset WHERE asset_no = v_asset_no) THEN
            INSERT INTO wms_asset (asset_no, item_id, warehouse_id, status, created_at)
            VALUES (v_asset_no, v_item_id, v_warehouse_id, '在库', NOW());
        END IF;
    END LOOP;
    
    CLOSE cur;
END $$

DELIMITER ;

-- 4. 执行迁移
CALL migrate_stock_to_asset();

-- 5. 清理临时表和存储过程
DROP TEMPORARY TABLE IF EXISTS temp_asset_seq;
DROP PROCEDURE IF EXISTS migrate_stock_to_asset;

-- 6. 验证迁移结果
SELECT 
    'wms_stock 总件数' AS metric,
    COALESCE(SUM(quantity), 0) AS count
FROM wms_stock
UNION ALL
SELECT 
    'wms_asset 在库件数' AS metric,
    COUNT(*) AS count
FROM wms_asset
WHERE status = '在库';

-- 7. 查看各仓库资产分布
SELECT 
    w.code AS warehouse_code,
    w.name AS warehouse_name,
    i.sku,
    i.name AS item_name,
    COUNT(a.id) AS asset_count
FROM wms_asset a
JOIN wms_warehouse w ON a.warehouse_id = w.id
JOIN wms_item i ON a.item_id = i.id
WHERE a.status = '在库'
GROUP BY w.code, w.name, i.sku, i.name
ORDER BY w.code, i.sku;
