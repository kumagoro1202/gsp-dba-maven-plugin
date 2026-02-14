package jp.co.tis.gsp.tools.dba.util;

import java.sql.SQLException;

public class UncheckedSQLException extends RuntimeException {
    public UncheckedSQLException(SQLException cause) {
        super(cause);
    }
    public UncheckedSQLException(String message, SQLException cause) {
        super(message, cause);
    }
    @Override
    public synchronized SQLException getCause() {
        return (SQLException) super.getCause();
    }
}
