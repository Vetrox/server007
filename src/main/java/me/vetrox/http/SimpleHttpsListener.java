package me.vetrox.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.KeyStore;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.apache.commons.lang3.StringUtils;

public class SimpleHttpsListener {

    private static final TimeScheduler scheduler = new TimeScheduler(200);
    private static final int CHUNKED_BUFFER_SIZE = 8192;
    private static int httpsPort = 9007;
    private static int httpPort = 6007;

    private static boolean isParsableToInt(String input) {
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        // Load keystore
        char[] password = "changeit".toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(new FileInputStream(args[0]), password);

        if (args.length > 1 && isParsableToInt(args[1])) {
            httpPort = Integer.parseInt(args[1]);
        }
        if (args.length > 2 && isParsableToInt(args[2])) {
            httpsPort = Integer.parseInt(args[2]);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, password);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        // Create HTTPS server
        HttpsServer server = HttpsServer.create(new InetSocketAddress(httpsPort), 0);
        server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(sslContext));
        server.createContext("/", new CustomHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("HTTPS server started on port " + httpsPort);

        // Create HTTP server
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.createContext("/", new CustomHandler());
        httpServer.setExecutor(null); // creates a default executor
        httpServer.start();
        System.out.println("HTTP server started on port " + httpPort);
    }

    static class CustomHandler implements HttpHandler {

        public void handle(HttpExchange exchange) {
            Date executionTime = new Date();
            scheduler.submit(executionTime, () -> handleInternal(exchange));
        }

        private void handleInternal(HttpExchange exchange) {
            try {
                String protocol = exchange.getProtocol().toLowerCase();
                System.out.println("[Request " + (exchange instanceof HttpsExchange tls ? tls.getSSLSession().getProtocol() + " " : "") + protocol + "] From " + exchange.getRemoteAddress());
                System.out.println("[Request] " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
                System.out.println("[Request] " + exchange.getRequestHeaders().entrySet());

                String spec = getSpec(exchange);
                if (spec == null) {
                    return;
                }

                URL url = new URL(spec);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(exchange.getRequestMethod());

                // Copy headers
                for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
                    for (String value : header.getValue()) {
                        conn.addRequestProperty(header.getKey(), value);
                    }
                }

                // If there's a request body, forward it
                if (exchange.getRequestMethod().equalsIgnoreCase("POST") ||
                    exchange.getRequestMethod().equalsIgnoreCase("PUT") ||
                    exchange.getRequestMethod().equalsIgnoreCase("PATCH")) {
                    conn.setDoOutput(true);
                    try (InputStream is = exchange.getRequestBody();
                        OutputStream os = conn.getOutputStream()) {
                        is.transferTo(os);
                    }
                }

                // Get response
                int responseCode = waitForResponse(conn);

                String responseProtocol = conn.getHeaderField(null); // e.g., "HTTP/1.1 200 OK"
                if (responseProtocol == null) {
                    responseProtocol = "HTTP/1.1 " + responseCode;
                }
                String remoteHost = url.getHost() + (url.getPort() != -1 ? ":" + url.getPort() : "");
                System.out.println("[Response " + protocol + "] From " + remoteHost);
                System.out.println("[Response] " + responseProtocol);
                System.out.println("[Response] " + conn.getHeaderFields());

                InputStream remoteResponseStream = (responseCode >= 400)
                    ? conn.getErrorStream()
                    : conn.getInputStream();

                // Copy response headers
                Headers responseHeaders = exchange.getResponseHeaders();
                Map<String, List<String>> remoteHeaders = conn.getHeaderFields();
                if (remoteHeaders != null) {
                    for (Map.Entry<String, List<String>> header : remoteHeaders.entrySet()) {
                        if (header.getKey() != null && header.getValue() != null) {
                            responseHeaders.put(header.getKey(), header.getValue());
                        }
                    }
                }

                // Decide on content length or chunked streaming
                long contentLength = -1;
                String contentLengthHeader = conn.getHeaderField("Content-Length");
                if (contentLengthHeader != null) {
                    try {
                        contentLength = Long.parseLong(contentLengthHeader);
                    } catch (NumberFormatException ignored) {
                    }
                }

                // Send response headers accordingly
                if (contentLength >= 0) {
                    exchange.sendResponseHeaders(responseCode, contentLength);
                } else {
                    exchange.sendResponseHeaders(responseCode, 0); // enables chunked streaming
                }

                // Stream the response
                System.out.println("[Response Content Begin]");
                if (remoteResponseStream != null) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        byte[] buffer = new byte[CHUNKED_BUFFER_SIZE];
                        int bytesRead;
                        while ((bytesRead = remoteResponseStream.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                            System.out.print(new String(buffer, 0, bytesRead, java.nio.charset.StandardCharsets.UTF_8));
                        }
                        System.out.println(); // Newline after response body
                        System.out.println("[Response Content End]");
                    } finally {
                        remoteResponseStream.close();
                    }
                } else {
                    exchange.getResponseBody().close();
                }

            } catch (Exception e) {
                e.printStackTrace();
                try {
                    String error = "Internal Server Error";
                    exchange.sendResponseHeaders(500, error.length());
                    exchange.getResponseBody().write(error.getBytes());
                    exchange.getResponseBody().close();
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            }
        }

        private int waitForResponse(HttpURLConnection conn) throws IOException {
            return conn.getResponseCode();
        }

        private String getSpec(HttpExchange exchange) {
            String host = exchange.getRequestHeaders().getFirst("X-proxy007-host");
            if (StringUtils.isBlank(host)) {
                System.out.println("[ERROR] host wasn't set");
                return null;
            }
            String spec = (exchange instanceof HttpsExchange ? "https" : "http") + "://" + host;

            String port = exchange.getRequestHeaders().getFirst("X-proxy007-port");
            if (!StringUtils.isBlank(port) && isParsableToInt(port)) {
                spec += ":" + port;
            }
            spec += exchange.getRequestURI();
            return spec;
        }

    }
}

