package com.tmdbwh.common.clickhouse;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 结果集行映射函数。
 *
 * @param <T> 目标类型
 */
@FunctionalInterface
public interface RowMapper<T> {

    /** 把结果集当前行映射为对象（不要在实现中调用 rs.next()）。 */
    T map(ResultSet rs) throws SQLException;
}
