-- 执行前核对并清理历史重复订单；已有同名索引时不要再次执行。
-- SELECT user_id,voucher_id,COUNT(*) FROM tb_voucher_order GROUP BY user_id,voucher_id HAVING COUNT(*)>1;
ALTER TABLE tb_voucher_order ADD UNIQUE KEY uk_user_voucher(user_id,voucher_id);
