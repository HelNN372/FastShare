package app.fastshare;

import android.os.Build;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import javax.net.ssl.*;

final class InternetRoom {
    static final int DEFAULT_PORT = 47443;
    private static final byte[] MAGIC = "FSRELAY2".getBytes(StandardCharsets.US_ASCII);

    static final class Endpoint {
        final String host;
        final int port;

        Endpoint(String input) throws IOException {
            String value = input.trim();
            try {
                URI uri = new URI("tls://" + value);
                if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                        || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                        || uri.getPort() == 0 || uri.getPort() > 65535) throw new URISyntaxException(value, "Invalid address");
                host = uri.getHost();
                port = uri.getPort() < 0 ? DEFAULT_PORT : uri.getPort();
            } catch (URISyntaxException error) {
                throw new IOException("Укажи домен сервера и порт, например relay.example.net:47443.");
            }
        }
    }

    static String newCode() {
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder code = new StringBuilder();
        for (byte value : random) code.append(String.format(Locale.ROOT, "%02X", value & 255));
        return code.toString();
    }

    static byte[] codeBytes(String value) throws IOException {
        String code = value.replaceAll("[\\s-]", "");
        if (!code.matches("[a-fA-F0-9]{32}")) throw new IOException("Код комнаты должен содержать 32 шестнадцатеричных символа.");
        byte[] bytes = new byte[16];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(code.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    static SSLSocket connect(Socket raw, Endpoint endpoint, byte[] room) throws Exception {
        raw.connect(new InetSocketAddress(endpoint.host, endpoint.port), 12000);
        raw.setSoTimeout(15000);
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket tls = (SSLSocket) factory.createSocket(raw, endpoint.host, endpoint.port, true);
        List<String> protocols = new ArrayList<>();
        for (String protocol : tls.getSupportedProtocols()) {
            if (protocol.equals("TLSv1.2") || protocol.equals("TLSv1.3")) protocols.add(protocol);
        }
        tls.setEnabledProtocols(protocols.toArray(new String[0]));
        if (Build.VERSION.SDK_INT >= 24) {
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
        }
        tls.startHandshake();
        if (Build.VERSION.SDK_INT < 24
                && !HttpsURLConnection.getDefaultHostnameVerifier().verify(endpoint.host, tls.getSession())) {
            throw new SSLPeerUnverifiedException("Имя сервера не совпадает с сертификатом.");
        }
        tls.setSoTimeout(320000);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(MAGIC);
        OutputStream output = tls.getOutputStream();
        output.write(MAGIC);
        output.write(digest.digest(room));
        output.flush();
        int response = tls.getInputStream().read();
        if (response != 1) throw new IOException(response == 2 ? "Комната занята или сервер перегружен."
            : "Второй телефон не подключился к комнате.");
        tls.setSoTimeout(20000);
        return tls;
    }

    private InternetRoom() {}
}
