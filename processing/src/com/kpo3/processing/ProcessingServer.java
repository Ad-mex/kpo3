package com.kpo3.processing;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.net.*;
import java.io.*;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;

public class ProcessingServer {
    static String DB_URL;
    static String DB_USER;
    static String DB_PASS;
    static String STORAGE_HOST;
    static String STORAGE_PORT;
    static int PORT;

    static HttpServer server;

    private static void initEnvironment(String[] args) throws IOException {
        String dbHost = System.getenv().get("DB_HOST");
        String dbPort = System.getenv().get("DB_PORT");
        String dbName = System.getenv().get("DB_NAME");
        DB_USER = System.getenv().get("DB_USER");
        DB_PASS = System.getenv().get("DB_PASS");
        DB_URL = "jdbc:postgresql://" + dbHost + ":" + dbPort + "/" + dbName;

        STORAGE_HOST = System.getenv().get("STORAGE_HOST");
        STORAGE_PORT = System.getenv().get("STORAGE_PORT");

        PORT = Integer.parseInt(System.getenv().get("PORT"));

        server = HttpServer.create(new InetSocketAddress(PORT), 0);
    }

    private static void analyzeEndpoint(HttpExchange exchange) throws IOException {
        System.err.println("analyzeEndpoint triggered");
        System.err.flush();
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "Only POST allowed");
                return;
            }

            Map<String, String> q = queryToMap(exchange.getRequestURI().getRawQuery());
            String submissionIdStr = q.get("submissionId");
            if (submissionIdStr == null) {
                send(exchange, 400, "Missing submissionId");
                return;
            }

            int submissionId = Integer.parseInt(submissionIdStr);
            System.err.println("Processing submissionId=" + submissionId);

            byte[] fileBytes;
            try {
                fileBytes = fetchFileFromStorage(submissionId);
            } catch (Exception ex) {
                System.err.println("Failed to fetch file: " + ex.getMessage());
                send(exchange, 500, "Failed to fetch file");
                return;
            }

            String hash;
            try {
                hash = sha256(fileBytes);
            } catch (Exception ex) {
                System.err.println("Failed to compute hash: " + ex.getMessage());
                send(exchange, 500, "Failed to compute hash");
                return;
            }

            boolean plagiat = false;

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {

                String selStudent = "SELECT student FROM submissions WHERE id = ?";
                String studentId;
                try (PreparedStatement ps = conn.prepareStatement(selStudent)) {
                    ps.setInt(1, submissionId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) {
                        send(exchange, 500, "Unknown submissionId");
                        return;
                    }
                    studentId = rs.getString(1);
                }

                String sel = "SELECT submission_id FROM reports r " +
                        "JOIN submissions s ON r.submission_id = s.id " +
                        "WHERE r.hash = ? AND s.student <> ?";
                boolean existsOtherStudent = false;

                try (PreparedStatement ps = conn.prepareStatement(sel)) {
                    ps.setString(1, hash);
                    ps.setString(2, studentId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        existsOtherStudent = true;
                    }
                }

                plagiat = existsOtherStudent;

                if (plagiat) {
                    String update = "UPDATE reports r " +
                            "SET plagiat = TRUE " +
                            "FROM submissions s " +
                            "WHERE r.submission_id = s.id AND r.hash = ? AND s.student <> ?";
                    try (PreparedStatement ps = conn.prepareStatement(update)) {
                        ps.setString(1, hash);
                        ps.setString(2, studentId);
                        ps.executeUpdate();
                    }
                }

                String insert = "INSERT INTO reports(submission_id, hash, plagiat) VALUES (?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    ps.setInt(1, submissionId);
                    ps.setString(2, hash);
                    ps.setBoolean(3, plagiat);
                    ps.executeUpdate();
                }

            } catch (SQLException ex) {
                System.err.println("DB error: " + ex.getMessage());
                send(exchange, 500, "Database error");
                return;
            }

            send(exchange, 200, "Report saved");

        } catch (Exception ex) {
            ex.printStackTrace();
            send(exchange, 500, "Internal server error: " + ex.getMessage());
        }
    }

    private static void workReportEndpoint(HttpExchange exchange) throws IOException {
        System.err.println("workReportEndpoint triggered");
        System.err.flush();
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "Only GET allowed");
                return;
            }

            Map<String, String> q = queryToMap(exchange.getRequestURI().getRawQuery());
            String workIdStr = q.get("workId");
            if (workIdStr == null) {
                send(exchange, 400, "Missing workId");
                return;
            }
            int workId = Integer.parseInt(workIdStr);

            List<String> reports = new ArrayList<>();

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {

                String sel = "SELECT r.submission_id, r.plagiat, s.student " +
                        "FROM reports r " +
                        "JOIN submissions s ON r.submission_id = s.id " +
                        "WHERE s.work_id = ?";

                try (PreparedStatement ps = conn.prepareStatement(sel)) {
                    ps.setInt(1, workId);
                    ResultSet rs = ps.executeQuery();

                    while (rs.next()) {
                        int submissionId = rs.getInt("submission_id");
                        boolean plagiat = rs.getBoolean("plagiat");
                        String student = rs.getString("student");

                        reports.add("{\"submissionId\":" + submissionId +
                                ",\"student\":\"" + student + "\"" +
                                ",\"plagiat\":" + plagiat +
                                "}");
                    }
                }

            } catch (SQLException ex) {
                System.err.println("DB error: " + ex.getMessage());
                send(exchange, 500, "Database error");
                return;
            }

            String respJson = "{\"reports\":[" + String.join(",", reports) + "]}";
            send(exchange, 200, respJson);

        } catch (Exception ex) {
            ex.printStackTrace();
            send(exchange, 500, "Internal server error: " + ex.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            initEnvironment(args);
        } catch (Exception ex) {
            System.err.println("Failed to initialize processing environment: " + ex.getMessage());
            ex.printStackTrace();
            System.exit(1);
        }

        server.createContext("/analyze", ProcessingServer::analyzeEndpoint);
        server.createContext("/work_reports", ProcessingServer::workReportEndpoint);

        server.start();
        System.err.println("Processing server started on " + PORT);
    }

    static byte[] fetchFileFromStorage(int submissionId) throws Exception {
        URL url = new URL("http://" + STORAGE_HOST + ":" + STORAGE_PORT + "/file?id=" + submissionId);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        int code = c.getResponseCode();
        if (code != 200) {
            try (InputStream err = c.getErrorStream()) {
                String msg = (err != null) ? new String(err.readAllBytes()) : "";
                throw new RuntimeException("Failed fetch file, code=" + code + ", " + msg);
            }
        }
        try (InputStream in = c.getInputStream()) {
            return in.readAllBytes();
        }
    }

    static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : h)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static void send(HttpExchange e, int code, String body) throws IOException {
        System.err.println("Processing send: " + code + " " + body);
        byte[] b = body.getBytes();
        e.sendResponseHeaders(code, b.length);
        e.getResponseBody().write(b);
        e.close();
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
