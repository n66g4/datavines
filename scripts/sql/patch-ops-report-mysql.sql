-- Patch: ops report tables (run on existing MariaDB meta DB)
CREATE TABLE IF NOT EXISTS `dv_ops_report_profile` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL COMMENT '报表定义名称',
  `workspace_id` bigint(20) NOT NULL COMMENT '工作空间ID',
  `business_type` varchar(64) DEFAULT NULL COMMENT '业务类型/业务标签',
  `datasource_ids` text NOT NULL COMMENT '下级部门数据源ID列表 JSON',
  `config_json` mediumtext COMMENT '表映射等配置 JSON',
  `schedule_cron` varchar(128) DEFAULT NULL COMMENT '定时 cron',
  `create_by` bigint(20) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint(20) DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ops_report_profile_ws` (`workspace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统计报表定义';

CREATE TABLE IF NOT EXISTS `dv_ops_report_run` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `profile_id` bigint(20) NOT NULL,
  `workspace_id` bigint(20) NOT NULL,
  `stat_date` date DEFAULT NULL,
  `range_start` datetime DEFAULT NULL,
  `range_end` datetime DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `ledger_path` varchar(1024) DEFAULT NULL,
  `checklist_zip_path` varchar(1024) DEFAULT NULL COMMENT '下级部门整改清单zip路径',
  `message` text,
  `create_by` bigint(20) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finish_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ops_report_run_profile` (`profile_id`),
  KEY `idx_ops_report_run_ws` (`workspace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统计报表运行记录';
