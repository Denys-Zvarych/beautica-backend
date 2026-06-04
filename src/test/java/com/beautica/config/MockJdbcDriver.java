package com.beautica.config;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * In-JVM, no-network JDBC driver used <strong>only</strong> by {@link FlywayDataSourceConfigTest}
 * so HikariCP's eager startup probe succeeds without a real database.
 *
 * <p>The production {@link FlywayDataSourceConfig} builds its pool with {@code new HikariDataSource(cfg)},
 * which opens and validates one connection in the constructor. Rather than depend on Postgres/Docker,
 * this driver answers the {@code jdbc:mock-flyway:} URL scheme with a {@link Proxy}-backed
 * {@link Connection} that reports healthy ({@code isValid}, {@code getAutoCommit}, metadata, etc.),
 * letting the real production factory run end to end with zero I/O.
 *
 * <p>It is registered with {@link DriverManager} in a static initializer; HikariCP loads it via the
 * explicit {@code driver-class-name} the test supplies.
 */
public class MockJdbcDriver implements Driver {

    private static final String URL_PREFIX = "jdbc:mock-flyway:";

    static {
        try {
            DriverManager.registerDriver(new MockJdbcDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) {
        if (!acceptsURL(url)) {
            return null; // contract: return null for URLs this driver does not handle
        }
        return newStubConnection();
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(MockJdbcDriver.class.getName());
    }

    // ── stub Connection / DatabaseMetaData / Statement via dynamic proxy ──────────────────────
    // Only the handful of methods HikariCP calls during pool init + validation are given real
    // answers; everything else returns a JDBC-sane default so we never have to hand-implement the
    // entire Connection surface.

    private static Connection newStubConnection() {
        return (Connection) Proxy.newProxyInstance(
                MockJdbcDriver.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionHandler());
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private boolean autoCommit = true;
        private boolean closed = false;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "isValid" -> !closed;                 // HikariCP health check
                case "isClosed" -> closed;
                case "close" -> { closed = true; yield null; }
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> { autoCommit = (boolean) args[0]; yield null; }
                case "getMetaData" -> newMetaData((Connection) proxy);
                case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                case "isReadOnly" -> false;
                case "getNetworkTimeout" -> 0;
                case "getCatalog", "getSchema" -> null;
                case "createStatement" -> newStatement();
                case "unwrap" -> proxy;
                case "isWrapperFor" -> false;
                case "toString" -> "MockJdbcConnection";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                // setReadOnly / setCatalog / setNetworkTimeout / clearWarnings / commit /
                // rollback / setTransactionIsolation / etc. — accepted as no-ops.
                default -> defaultFor(method.getReturnType());
            };
        }
    }

    private static DatabaseMetaData newMetaData(Connection owner) {
        return (DatabaseMetaData) Proxy.newProxyInstance(
                MockJdbcDriver.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> owner;
                    case "getDriverName" -> "mock-flyway";
                    case "getDriverVersion", "getDatabaseProductVersion" -> "1.0";
                    case "getDatabaseProductName" -> "MockDB";
                    case "getJDBCMajorVersion", "getDatabaseMajorVersion" -> 1;
                    case "getJDBCMinorVersion", "getDatabaseMinorVersion" -> 0;
                    case "getURL" -> URL_PREFIX + "//in-jvm/dummy";
                    case "toString" -> "MockJdbcMetaData";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultFor(method.getReturnType());
                });
    }

    private static Statement newStatement() {
        return (Statement) Proxy.newProxyInstance(
                MockJdbcDriver.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> true;
                    case "isClosed" -> false;
                    case "toString" -> "MockJdbcStatement";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultFor(method.getReturnType());
                });
    }

    /** JDBC-sane default for an unhandled method based on its return type. */
    private static Object defaultFor(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        if (returnType == int.class || returnType == short.class || returnType == byte.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == float.class) {
            return 0f;
        }
        return (char) 0; // char.class
    }
}
