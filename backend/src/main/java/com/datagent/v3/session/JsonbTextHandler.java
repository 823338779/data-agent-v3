package com.datagent.v3.session;

import java.sql.*;
import org.apache.ibatis.type.*;

/** 将完整 JSON 原文以 PostgreSQL jsonb 需要的 JDBC 类型绑定。 */
public class JsonbTextHandler extends StringTypeHandler {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, String value, JdbcType type) throws SQLException {
        statement.setObject(index, value, Types.OTHER);
    }
}
