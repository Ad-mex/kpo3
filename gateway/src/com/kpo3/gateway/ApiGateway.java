package com.kpo3.gateway;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.net.*;
import java.io.*;

public class ApiGateway {
    static String STORAGE_HOST;
    static String STORAGE_PORT;
    static String PROCESSING_HOST;
    static String PROCESSING_PORT;
    static int PORT;
    private static HttpServer server;

    private static void initEnvironment(String[] args) throws Exception {
        STORAGE_HOST = System.getenv().get("STORAGE_HOST");
        STORAGE_PORT = System.getenv().get("STORAGE_PORT");
        PROCESSING_HOST = System.getenv().get("PROCESSING_HOST");
        PROCESSING_PORT = System.getenv().get("PROCESSING_PORT");
        PORT = Integer.parseInt(System.getenv().get("PORT"));

        server = HttpServer.create(new InetSocketAddress(PORT), 0);
    }

    private static void uploadEndpoint(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "Only POST allowed");
                return;
            }

            String query = exchange.getRequestURI().getRawQuery();
            if (query == null || !query.contains("student=") || !query.contains("workId=")) {
                send(exchange, 400, "Missing query parameters: student and workId required");
                return;
            }

            System.err.println(query);

            URL storageUrl = new URL("http://" + STORAGE_HOST + ":" + STORAGE_PORT + "/store?" + query);
            HttpURLConnection storageConn = (HttpURLConnection) storageUrl.openConnection();
            storageConn.setRequestMethod("POST");
            storageConn.setDoOutput(true);
            storageConn.setConnectTimeout(5000);
            storageConn.setReadTimeout(10000);
            storageConn.setRequestProperty("Content-Type", "application/octet-stream");

            try (OutputStream os = storageConn.getOutputStream(); InputStream is = exchange.getRequestBody()) {
                is.transferTo(os);
            }

            int storageCode = storageConn.getResponseCode();
            byte[] storageRespBytes = (storageCode >= 400) ? storageConn.getErrorStream().readAllBytes()
                    : storageConn.getInputStream().readAllBytes();
            String storageResp = new String(storageRespBytes, "UTF-8");

            System.err.println("Storage response code: " + storageCode + ", submissionId: " + storageResp);

            int submissionId = Integer.parseInt(storageResp);

            URL procUrl = new URL(
                    "http://" + PROCESSING_HOST + ":" + PROCESSING_PORT + "/analyze?submissionId=" + submissionId);
            HttpURLConnection procConn = (HttpURLConnection) procUrl.openConnection();
            procConn.setRequestMethod("POST");
            procConn.setDoOutput(true);
            procConn.setConnectTimeout(5000);
            procConn.setReadTimeout(10000);

            int procCode = procConn.getResponseCode();
            System.err.println("Triggered analysis for submission " + submissionId + ", code=" + procCode);

            try (InputStream is = procConn.getInputStream()) {
                if (is != null)
                    is.readAllBytes();
            }

            send(exchange, storageCode, "Submission id: " + submissionId);
        } catch (Exception ex) {
            ex.printStackTrace();
            send(exchange, 500, "Internal server error: " + ex.getMessage());
        }
    }

    private static void worksEndpoint(HttpExchange exchange) throws IOException {
        System.err.println("worksEndpoint triggered");
        System.err.flush();
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "Only GET allowed");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");
            if (parts.length < 4 || !"reports".equals(parts[3])) {
                send(exchange, 404, "Not found. Use /works/{workId}/reports");
                return;
            }
            String workId = parts[2];

            URL url = new URL("http://" + PROCESSING_HOST + ":" + PROCESSING_PORT + "/work_reports?workId=" + workId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            int code = conn.getResponseCode();
            InputStream respStream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            byte[] respBytes = (respStream != null) ? respStream.readAllBytes() : new byte[0];
            String resp = new String(respBytes, "UTF-8");

            send(exchange, code, resp);

        } catch (Exception ex) {
            ex.printStackTrace();
            send(exchange, 500, "Internal server error: " + ex.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        initEnvironment(args);
        server.createContext("/upload", ApiGateway::uploadEndpoint);
        server.createContext("/works", ApiGateway::worksEndpoint);
        server.start();
        System.err.println("API Gateway started on " + PORT);
    }

    static void send(HttpExchange e, int code, String body) throws IOException {
        System.err.println("ApiGateway send: " + code + " " + body);
        byte[] b = body.getBytes("UTF-8");
        e.sendResponseHeaders(code, b.length);
        try (OutputStream os = e.getResponseBody()) {
            os.write(b);
        }
    }
}
