-- V1 分层数据库
--
-- 所有库都带上 IF NOT EXISTS，保证脚本可重复执行。
-- 采集/治理脚本不要在这里建表：表结构一律由后续版本化脚本管理。
CREATE DATABASE IF NOT EXISTS ods COMMENT '贴源层：采集原始报文与事件';
CREATE DATABASE IF NOT EXISTS dwd COMMENT '明细层：清洗后的维度与事实';
CREATE DATABASE IF NOT EXISTS dws COMMENT '汇总层：主题宽表与周期性汇总';
CREATE DATABASE IF NOT EXISTS ads COMMENT '应用层：面向报表与接口的最终结果';
CREATE DATABASE IF NOT EXISTS rt COMMENT '实时层：实时链路明细（短 TTL）';
CREATE DATABASE IF NOT EXISTS governance COMMENT '治理层：迁移历史、元数据快照、质量与血缘';
