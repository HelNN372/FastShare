package app.fastshare;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

final class TransferServer implements Closeable {
    interface Writer { void write(OutputStream out) throws IOException; }

    static final class Item {
        final String name;
        final long length;
        final Writer writer;

        Item(String name, long length, Writer writer) {
            this.name = name;
            this.length = length;
            this.writer = writer;
        }
    }

    private final ServerSocket listener;
    private final List<Item> items;
    private final String route;
    private final Set<Socket> clients = new HashSet<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
        4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(4));

    TransferServer(InetAddress address, List<Item> selected) throws IOException {
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        StringBuilder path = new StringBuilder("/");
        for (byte value : secret) path.append(String.format(Locale.ROOT, "%02x", value & 255));
        route = path.append('/').toString();
        items = new ArrayList<>(selected);
        listener = new ServerSocket();
        try {
            listener.bind(new InetSocketAddress(address, 0));
        } catch (IOException error) {
            listener.close();
            throw error;
        }
        new Thread(this::listen, "transfer-listener").start();
    }

    String url() {
        return "http://" + listener.getInetAddress().getHostAddress() + ":" + listener.getLocalPort() + route;
    }

    private void listen() {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                synchronized (clients) {
                    if (listener.isClosed()) {
                        socket.close();
                        return;
                    }
                    clients.add(socket);
                    try {
                        workers.execute(() -> serve(socket));
                    } catch (RejectedExecutionException busy) {
                        clients.remove(socket);
                        socket.close();
                    }
                }
            } catch (IOException error) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(8000);
            InputStream in = new BufferedInputStream(connection.getInputStream());
            OutputStream out = new BufferedOutputStream(connection.getOutputStream(), 256 * 1024);
            String request = readLine(in);
            if (request == null) return;
            String[] parts = request.split(" ", 3);
            if (parts.length != 3) return;
            int headerBytes = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                headerBytes += line.length();
                if (headerBytes > 16384) return;
            }
            if (line == null) return;
            boolean head = parts[0].equals("HEAD");
            if (!head && !parts[0].equals("GET")) {
                send(out, "405 Method Not Allowed", "text/plain", 0, "");
            } else if (parts[1].equals(route)) {
                byte[] body = page().getBytes(StandardCharsets.UTF_8);
                send(out, "200 OK", "text/html; charset=utf-8", body.length, "");
                if (!head) out.write(body);
            } else {
                Item item = findItem(parts[1]);
                if (item == null) {
                    send(out, "404 Not Found", "text/plain", 0, "");
                } else {
                    String encoded = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
                        .replace("*", "%2A");
                    send(out, "200 OK", "application/octet-stream", item.length,
                        "Content-Disposition: attachment; filename*=UTF-8''" + encoded + "\r\n");
                    if (!head) {
                        out.flush();
                        if (item.length >= 0) {
                            item.writer.write(out);
                        } else {
                            writeChunked(item, out);
                        }
                    }
                }
            }
            out.flush();
        } catch (IOException ignored) {
            // Closing the connection also terminates interrupted or incomplete downloads.
        } finally {
            synchronized (clients) {
                clients.remove(socket);
            }
        }
    }

    private static void writeChunked(Item item, OutputStream out) throws IOException {
        OutputStream chunks = new OutputStream() {
            @Override public void write(int value) throws IOException {
                write(new byte[]{(byte) value}, 0, 1);
            }

            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return;
                out.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(bytes, offset, length);
                out.write('\r');
                out.write('\n');
            }
        };
        BufferedOutputStream body = new BufferedOutputStream(chunks, 128 * 1024);
        item.writer.write(body);
        body.flush();
        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private Item findItem(String path) {
        if (!path.startsWith(route)) return null;
        String index = path.substring(route.length());
        if (!index.matches("[0-9]{1,8}")) return null;
        int number = Integer.parseInt(index);
        return number < items.size() ? items.get(number) : null;
    }

    private String page() {
        StringBuilder html = new StringBuilder("<!doctype html><html lang='ru'><meta charset='utf-8'>")
            .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
            .append("<meta name='referrer' content='no-referrer'><title>Быстрый перенос</title>")
            .append("<style>body{font:17px system-ui;margin:32px auto;padding:0 20px;max-width:40rem;color:#14213d;background:#f3f7ff}")
            .append("a{display:block;margin:10px 0;padding:14px;background:white;border:1px solid #cbd9ee;border-radius:12px;overflow-wrap:anywhere;color:#174d9a}</style>")
            .append("<h1>Быстрый перенос</h1><p>Нажми на файл, чтобы скачать его.</p>");
        for (int i = 0; i < items.size(); i++) {
            html.append("<a href='").append(i).append("'>").append(escape(items.get(i).name)).append("</a>");
        }
        return html.toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int next; (next = in.read()) != -1;) {
            if (next == '\n') return line.toString();
            if (next != '\r') line.append((char) next);
            if (line.length() > 8192) throw new IOException("HTTP line too long");
        }
        return null;
    }

    private static void send(OutputStream out, String status, String type, long length, String extra)
            throws IOException {
        String headers = "HTTP/1.1 " + status + "\r\nConnection: close\r\n"
            + "Content-Type: " + type + "\r\nCache-Control: no-store\r\n"
            + "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"
            + (length >= 0 ? "Content-Length: " + length + "\r\n" : "Transfer-Encoding: chunked\r\n")
            + extra + "\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
    }

    @Override public void close() throws IOException {
        try {
            listener.close();
        } finally {
            synchronized (clients) {
                for (Socket socket : clients) {
                    try { socket.close(); } catch (IOException ignored) {}
                }
                clients.clear();
                workers.shutdownNow();
            }
        }
    }
}
