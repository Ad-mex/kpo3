package com.kpo3.storage;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public class StorageServer {
    static String DB_URL;
    static String DB_USER;
    static String DB_PASS;
    static String PROCESSING_HOST;
    static String PROCESSING_PORT;
    static Path FILE_DIR;
    static int PORT;

    static HttpServer server;

    private static void initEnvironment(String[] args) throws IOException {
        String dbHost = System.getenv().get("DB_HOST");
        String dbPort = System.getenv().get("DB_PORT");
        String dbName = System.getenv().get("DB_NAME");
        PROCESSING_HOST = System.getenv().get("PROCESSING_HOST");
        PROCESSING_PORT = System.getenv().get("PROCESSING_PORT");
        DB_USER = System.getenv().get("DB_USER");
        DB_PASS = System.getenv().get("DB_PASS");
        DB_URL = "jdbc:postgresql://" + dbHost + ":" + dbPort + "/" + dbName;

        PORT = Integer.parseInt(System.getenv().get("PORT"));
        FILE_DIR = Paths.get("files");
        Files.createDirectories(FILE_DIR);

        server = HttpServer.create(new InetSocketAddress(PORT), 0);
    }

    private static void storeEndpoint(HttpExchange exchange) throws IOException {
        System.err.println("storeEndpoint triggered");
        System.err.flush();
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "Only POST allowed");
                return;
            }

            Map<String, String> q = queryToMap(exchange.getRequestURI().getRawQuery());
            String student = q.get("student");
            String workIdStr = q.get("workId");

            if (student == null || workIdStr == null) {
                send(exchange, 400, "Missing student or workId");
                return;
            }

            int workId;
            try {
                workId = Integer.parseInt(workIdStr);
            } catch (NumberFormatException e) {
                send(exchange, 400, "Invalid workId");
                return;
            }

            byte[] bytes = exchange.getRequestBody().readAllBytes();
            if (bytes.length == 0) {
                send(exchange, 400, "Empty body");
                return;
            }

            int submissionId;
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                String insert = "INSERT INTO submissions(student, work_id, file_path) VALUES (?,?,?) RETURNING id";
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    ps.setString(1, student);
                    ps.setInt(2, workId);
                    ps.setString(3, "temp");
                    ResultSet rs = ps.executeQuery();
                    rs.next();
                    submissionId = rs.getInt(1);
                }

                String filename = "submission_" + submissionId;
                Path fpath = FILE_DIR.resolve(filename);
                Files.write(fpath, bytes);

                String update = "UPDATE submissions SET file_path = ? WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(update)) {
                    ps.setString(1, fpath.toString());
                    ps.setInt(2, submissionId);
                    ps.executeUpdate();
                }
            } catch (SQLException ex) {
                System.err.println("DB error: " + ex.getMessage());
                send(exchange, 500, "Database error");
                return;
            }

            send(exchange, 200, Integer.toString(submissionId));
        } catch (Exception ex) {
            ex.printStackTrace();
            send(exchange, 500, "Internal server error: " + ex.getMessage());
        }
    }

    private static void fileEndpoint(HttpExchange exchange) throws IOException {
        System.err.println("fileEndpoint triggered");
        System.err.flush();
        try {
            Map<String, String> q = queryToMap(exchange.getRequestURI().getRawQuery());
            String idStr = q.get("id");
            if (idStr == null) {
                send(exchange, 400, "Missing id");
                return;
            }

            int id;
            try {
                id = Integer.parseInt(idStr);
            } catch (NumberFormatException e) {
                send(exchange, 400, "Invalid id");
                return;
            }

            Path filePath;
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                String sel = "SELECT file_path FROM submissions WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sel)) {
                    ps.setInt(1, id);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) {
                        send(exchange, 404, "Not found");
                        return;
                    }
                    filePath = Paths.get(rs.getString(1));
                }
            } catch (SQLException ex) {
                System.err.println("DB error: " + ex.getMessage());
                send(exchange, 500, "Database error");
                return;
            }

            if (!Files.exists(filePath)) {
                send(exchange, 404, "File not found");
                return;
            }

            byte[] data = Files.readAllBytes(filePath);
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }

        } catch (Exception ex) {
            ex.printStackTrace();
            send(exchange, 500, "Internal server error: " + ex.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        initEnvironment(args);

        server.createContext("/store", StorageServer::storeEndpoint);
        server.createContext("/file", StorageServer::fileEndpoint);

        server.start();
        System.out.println("Storage server started on " + PORT);
    }

    static void send(HttpExchange e, int code, String body) throws IOException {
        System.err.println("Storage send: " + code + " " + body);
        byte[] b = body.getBytes();
        e.sendResponseHeaders(code, b.length);
        try (OutputStream os = e.getResponseBody()) {
            os.write(b);
        }
    }

    static Map<String, String> queryToMap(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null)
            return map;
        String[] pairs = query.split("&");
        for (String p : pairs) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2)
                map.put(kv[0], kv[1]);
        }
        return map;
    }
}
