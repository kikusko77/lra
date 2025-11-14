package io.narayana.lra.testcontainers;

import com.google.gson.Gson;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class RegistryDatabase {

    private static final Gson gson = new Gson();

    public static PostgreSQLContainer<?> db =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("registry")
                    .withUsername("user")
                    .withPassword("pass");

    static {
        db.start();
        initSchema();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    // -------------------------------------------
    // 🔧 Create registry table
    // -------------------------------------------
    public static void initSchema() {
        String sql = """
            CREATE TABLE IF NOT EXISTS coordinators (
                id TEXT PRIMARY KEY,
                host TEXT NOT NULL,
                port INT NOT NULL,
                status TEXT NOT NULL DEFAULT 'ACTIVE'
            );
        """;
        try (Connection conn = connection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create schema", e);
        }
    }

    // -------------------------------------------
    // 🔧 Insert coordinator record
    // -------------------------------------------
    public static void insert(String id, String host, int port) {
        String sql = "INSERT INTO coordinators (id, host, port, status) VALUES (?, ?, ?, 'ACTIVE') " +
                "ON CONFLICT (id) DO UPDATE SET host = EXCLUDED.host, port = EXCLUDED.port, status='ACTIVE'";
        try (Connection conn = connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, id);
            ps.setString(2, host);
            ps.setInt(3, port);
            ps.executeUpdate();

        } catch (Exception e) {
            throw new RuntimeException("Failed to insert coordinator", e);
        }
    }

    // -------------------------------------------
    // 🔧 Return all active coordinators as JSON
    // -------------------------------------------
    public static String findAllAsJson() {
        String sql = "SELECT id, host, port FROM coordinators WHERE status = 'ACTIVE'";
        List<CoordinatorInfo> list = new ArrayList<>();

        try (Connection conn = connection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {

            while (rs.next()) {
                list.add(new CoordinatorInfo(
                        rs.getString("id"),
                        rs.getString("host"),
                        rs.getInt("port")
                ));
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to query coordinators", e);
        }

        return gson.toJson(list);
    }

    // Helper DTO
    public record CoordinatorInfo(String id, String host, int port) {}
}
