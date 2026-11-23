package tw.edu.cse.nsysu.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class DefenseStore implements AutoCloseable, DefenseService.Products, DefenseService.Audit {
    private final Connection connection;
    private final ObjectMapper mapper = new ObjectMapper();
    public record Product(int id, String name, String category, int priceCents) {}

    public DefenseStore(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("CREATE TABLE IF NOT EXISTS products("
                    + "id INTEGER PRIMARY KEY,name TEXT NOT NULL,category TEXT NOT NULL,"
                    + "price_cents INTEGER NOT NULL CHECK(price_cents>=0))");
            statement.execute("CREATE TABLE IF NOT EXISTS defense_audit("
                    + "request_id TEXT PRIMARY KEY,started_at TEXT NOT NULL,json TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS defense_reports("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,json TEXT NOT NULL)");
        }
        String[] names = {"Notebook", "Keyboard", "Monitor", "USB Cable", "Wireless Mouse",
                "Headphones", "Webcam", "USB Hub", "Laptop Stand", "Microphone",
                "Desk Lamp", "Mouse Pad", "SSD", "Router", "Printer", "Tablet",
                "Phone Case", "Charger", "Display Cable", "Speaker"};
        try (var insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO products(id,name,category,price_cents) VALUES(?,?,?,?)")) {
            for (int i = 0; i < names.length; i++) {
                insert.setInt(1, i + 1);
                insert.setString(2, names[i]);
                insert.setString(3, "Demo accessories");
                insert.setInt(4, (i + 1) * 10000);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    @Override
    public synchronized List<Product> search(String keyword) throws SQLException {
        List<Product> result = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT id,name,category,price_cents FROM products "
                + "WHERE instr(lower(name),lower(?))>0 OR CAST(price_cents AS TEXT)=? "
                + "ORDER BY id LIMIT 20")) {
            statement.setString(1, keyword);
            statement.setString(2, keyword);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(new Product(rows.getInt(1), rows.getString(2),
                        rows.getString(3), rows.getInt(4)));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized void save(DefenseService.Result result) throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO defense_audit(request_id,started_at,json) VALUES(?,?,?) "
                + "ON CONFLICT(request_id) DO UPDATE SET json=excluded.json")) {
            statement.setString(1, result.requestId);
            statement.setString(2, result.timestamp);
            statement.setString(3, mapper.writeValueAsString(result));
            statement.executeUpdate();
        }
    }

    public synchronized List<DefenseService.Result> recent(int limit) throws Exception {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Audit limit must be 1..500.");
        List<DefenseService.Result> results = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT json FROM defense_audit ORDER BY rowid DESC LIMIT ?")) {
            statement.setInt(1, limit);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) results.add(mapper.readValue(rows.getString(1), DefenseService.Result.class));
            }
        }
        return results;
    }

    public synchronized void saveReport(Object report) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO defense_reports(json) VALUES(?)")) {
            statement.setString(1, mapper.writeValueAsString(report));
            statement.executeUpdate();
        }
    }

    public synchronized com.fasterxml.jackson.databind.JsonNode latestReport() throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT json FROM defense_reports ORDER BY id DESC LIMIT 1")) {
            return rows.next() ? mapper.readTree(rows.getString(1)) : mapper.createObjectNode().put("status", "missing");
        }
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
}
