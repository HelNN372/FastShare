package app.fastshare;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class PeerLink implements Closeable {
    private static final int HELLO = 1, TRUST = 2, OFFER = 3, ACCEPT = 4, REJECT = 5;
    private static final int DATA = 6, FILE_END = 7, COMPLETE = 8;
    static final int MAX_FILES = 128;
    private static final int CHUNK = 128 * 1024;
    private static final byte[] MAGIC = "FASTSHARE2".getBytes(StandardCharsets.US_ASCII);

    interface Source {
        InputStream open() throws IOException;
    }

    interface Target {
        void write(byte[] bytes) throws IOException;
        void commit() throws IOException;
        void abort();
    }

    interface Listener {
        void verify(PeerLink link, String peer, String code);
        void ready(PeerLink link, String peer);
        void offer(PeerLink link, List<FileInfo> files);
        Target open(FileInfo file) throws IOException;
        void progress(PeerLink link, boolean sending, String name, long bytes, long size);
        void finished(PeerLink link, boolean sending, boolean success, String detail);
        void closed(PeerLink link, String detail);
    }

    static class FileInfo {
        final String name, mime;
        final long size;

        FileInfo(String name, String mime, long size) {
            this.name = safeName(name);
            this.mime = mime != null && mime.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")
                && mime.length() <= 120 ? mime : "application/octet-stream";
            this.size = Math.max(-1, size);
        }
    }

    static final class FileSource extends FileInfo {
        final Source source;

        FileSource(String name, String mime, long size, Source source) {
            super(name, mime, size);
            this.source = source;
        }
    }

    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final Listener listener;
    private final SecretKeySpec sendKey, receiveKey;
    private final String verificationCode;
    private final boolean online;
    private final Object state = new Object();
    private long sentFrames, receivedFrames;
    private List<FileSource> outgoing;
    private boolean outgoingAccepted;
    private List<FileInfo> incoming;
    private boolean incomingAccepted;
    private int fileIndex;
    private Target target;
    private long fileBytes, lastProgress;
    private boolean localTrust, remoteTrust, ready;
    private String peer;
    private volatile String closingReason = "Соединение закрыто.";

    PeerLink(Socket socket, String name, byte[] room, Listener listener)
            throws IOException, GeneralSecurityException {
        this.socket = socket;
        this.listener = listener;
        this.online = room != null;
        input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), CHUNK));
        output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), CHUNK));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        byte[] publicKey = pair.getPublic().getEncoded();
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        output.write(MAGIC);
        output.write(hash(MAGIC, salt, publicKey));
        output.flush();

        byte[] magic = new byte[MAGIC.length];
        input.readFully(magic);
        require(Arrays.equals(magic, MAGIC));
        byte[] commitment = new byte[32];
        input.readFully(commitment);
        output.write(salt);
        output.writeShort(publicKey.length);
        output.write(publicKey);
        output.flush();
        byte[] peerSalt = new byte[32];
        input.readFully(peerSalt);
        int keyLength = input.readUnsignedShort();
        require(keyLength >= 64 && keyLength <= 256);
        byte[] peerKey = new byte[keyLength];
        input.readFully(peerKey);
        require(MessageDigest.isEqual(commitment, hash(MAGIC, peerSalt, peerKey)));
        int order = compare(publicKey, peerKey);
        require(order != 0);
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(pair.getPrivate());
        agreement.doPhase(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(peerKey)), true);
        byte[] shared = agreement.generateSecret();
        byte[] first = order < 0 ? publicKey : peerKey;
        byte[] second = order < 0 ? peerKey : publicKey;
        byte[] firstSalt = order < 0 ? salt : peerSalt;
        byte[] secondSalt = order < 0 ? peerSalt : salt;
        byte[] secret = hash(MAGIC, shared, first, second, firstSalt, secondSalt,
            room == null ? new byte[0] : room);
        sendKey = new SecretKeySpec(hash(secret, new byte[]{(byte) (order < 0 ? 1 : 2)}), "AES");
        receiveKey = new SecretKeySpec(hash(secret, new byte[]{(byte) (order < 0 ? 2 : 1)}), "AES");
        byte[] check = hash(secret, new byte[]{3});
        int number = ((check[0] & 127) << 24) | ((check[1] & 255) << 16)
            | ((check[2] & 255) << 8) | (check[3] & 255);
        verificationCode = String.format(Locale.ROOT, "%06d", number % 1000000);
        write(HELLO, text(safeName(name)));
    }

    void start() {
        new Thread(this::readLoop, "fastshare-reader").start();
    }

    void trust() {
        new Thread(() -> {
            try {
                synchronized (state) {
                    write(TRUST, new byte[0]);
                    localTrust = true;
                    checkReady();
                }
            } catch (IOException error) {
                disconnect(error);
            }
        }, "fastshare-trust").start();
    }

    void send(List<FileSource> files) {
        if (files.isEmpty() || files.size() > MAX_FILES) return;
        synchronized (state) {
            if (!ready || outgoing != null || socket.isClosed()) return;
            outgoing = new ArrayList<>(files);
            outgoingAccepted = false;
        }
        new Thread(() -> {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream metadata = new DataOutputStream(bytes);
                metadata.writeShort(files.size());
                for (FileSource file : files) {
                    metadata.writeUTF(file.name);
                    metadata.writeUTF(file.mime);
                    metadata.writeLong(file.size);
                }
                write(OFFER, bytes.toByteArray());
            } catch (IOException error) {
                disconnect(error);
            }
        }, "fastshare-offer").start();
    }

    void answer(boolean accepted) {
        new Thread(() -> {
            try {
                synchronized (state) {
                    if (incoming == null || incomingAccepted) return;
                    if (accepted) {
                        incomingAccepted = true;
                        fileIndex = 0;
                        fileBytes = 0;
                        lastProgress = 0;
                    } else {
                        incoming = null;
                    }
                }
                write(accepted ? ACCEPT : REJECT, new byte[0]);
            } catch (IOException error) {
                disconnect(error);
            }
        }, "fastshare-answer").start();
    }

    private void readLoop() {
        try {
            while (!socket.isClosed()) read();
        } catch (IOException | GeneralSecurityException | RuntimeException error) {
            if (!socket.isClosed()) {
                closingReason = error instanceof EOFException ? "Собеседник отключился."
                    : error instanceof GeneralSecurityException ? "Не удалось проверить шифрование соединения."
                    : "Передача прервана: " + detail(error);
            }
        } finally {
            if (target != null) target.abort();
            closeQuietly();
            listener.closed(this, closingReason);
        }
    }

    private void read() throws IOException, GeneralSecurityException {
        int length = input.readInt();
        require(length >= 17 && length <= CHUNK + 17);
        byte[] ciphertext = new byte[length];
        input.readFully(ciphertext);
        byte[] frame = crypt(Cipher.DECRYPT_MODE, receiveKey, receivedFrames++, ciphertext);
        int type = frame[0] & 255;
        DataInputStream metadata = new DataInputStream(new ByteArrayInputStream(frame, 1, frame.length - 1));
        if (type == HELLO) {
            require(peer == null);
            peer = safeName(metadata.readUTF());
            require(metadata.available() == 0);
            if (online) trust();
            else listener.verify(this, peer, verificationCode);
            return;
        }
        if (type == TRUST) {
            require(peer != null && frame.length == 1);
            synchronized (state) { remoteTrust = true; }
            checkReady();
            return;
        }
        synchronized (state) { require(ready); }
        if (type != OFFER && type != DATA) require(frame.length == 1);
        switch (type) {
            case OFFER:
                int count = metadata.readUnsignedShort();
                require(count > 0 && count <= MAX_FILES);
                List<FileInfo> files = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    files.add(new FileInfo(metadata.readUTF(), metadata.readUTF(), metadata.readLong()));
                }
                require(metadata.available() == 0);
                synchronized (state) {
                    require(incoming == null);
                    incoming = files;
                    incomingAccepted = false;
                }
                listener.offer(this, Collections.unmodifiableList(files));
                break;
            case ACCEPT:
                List<FileSource> accepted;
                synchronized (state) {
                    require(outgoing != null && !outgoingAccepted);
                    outgoingAccepted = true;
                    accepted = outgoing;
                }
                new Thread(() -> sendFiles(accepted), "fastshare-sender").start();
                break;
            case REJECT:
                synchronized (state) {
                    require(outgoing != null && !outgoingAccepted);
                    outgoing = null;
                }
                listener.finished(this, true, false, "Собеседник отклонил передачу.");
                break;
            case DATA:
                require(frame.length > 1);
                FileInfo current = currentFile();
                long nextSize = fileBytes + frame.length - 1;
                require(current.size < 0 || nextSize <= current.size);
                if (target == null) target = listener.open(current);
                target.write(Arrays.copyOfRange(frame, 1, frame.length));
                fileBytes = nextSize;
                long now = System.nanoTime();
                if (now - lastProgress >= 200_000_000L) {
                    lastProgress = now;
                    listener.progress(this, false, current.name, fileBytes, current.size);
                }
                break;
            case FILE_END:
                FileInfo file = currentFile();
                require(file.size < 0 || file.size == fileBytes);
                if (target == null) target = listener.open(file);
                target.commit();
                target = null;
                listener.progress(this, false, file.name, fileBytes, file.size);
                fileBytes = 0;
                boolean allSaved;
                synchronized (state) {
                    allSaved = ++fileIndex == incoming.size();
                    if (allSaved) {
                        incoming = null;
                        incomingAccepted = false;
                    }
                }
                if (allSaved) {
                    write(COMPLETE, new byte[0]);
                    listener.finished(this, false, true, "Файлы сохранены.");
                }
                break;
            case COMPLETE:
                synchronized (state) {
                    require(outgoing != null && outgoingAccepted);
                    outgoing = null;
                    outgoingAccepted = false;
                }
                listener.finished(this, true, true, "Файлы доставлены.");
                break;
            default:
                throw new IOException("Неизвестная команда.");
        }
    }

    private FileInfo currentFile() throws IOException {
        synchronized (state) {
            require(incoming != null && incomingAccepted && fileIndex < incoming.size());
            return incoming.get(fileIndex);
        }
    }

    private void sendFiles(List<FileSource> files) {
        byte[] buffer = new byte[CHUNK];
        try {
            for (FileSource file : files) {
                long bytes = 0, last = 0;
                try (InputStream source = file.source.open()) {
                    for (int count; (count = source.read(buffer)) != -1;) {
                        if (count == 0) continue;
                        bytes += count;
                        require(file.size < 0 || bytes <= file.size);
                        write(DATA, buffer, count);
                        long now = System.nanoTime();
                        if (now - last >= 200_000_000L) {
                            listener.progress(this, true, file.name, bytes, file.size);
                            last = now;
                        }
                    }
                }
                require(file.size < 0 || bytes == file.size);
                listener.progress(this, true, file.name, bytes, file.size);
                write(FILE_END, new byte[0]);
            }
        } catch (IOException | RuntimeException error) {
            disconnect(error);
        }
    }

    private void checkReady() {
        synchronized (state) {
            if (ready || !localTrust || !remoteTrust) return;
            ready = true;
        }
        listener.ready(this, peer);
    }

    private void write(int type, byte[] bytes) throws IOException {
        write(type, bytes, bytes.length);
    }

    private synchronized void write(int type, byte[] bytes, int length) throws IOException {
        require(length <= CHUNK);
        byte[] frame = new byte[length + 1];
        frame[0] = (byte) type;
        System.arraycopy(bytes, 0, frame, 1, length);
        try {
            byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, sendKey, sentFrames++, frame);
            output.writeInt(ciphertext.length);
            output.write(ciphertext);
            output.flush();
        } catch (GeneralSecurityException error) {
            throw new IOException(error);
        }
    }

    private static byte[] crypt(int mode, SecretKeySpec key, long sequence, byte[] data)
            throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) nonce[11 - i] = (byte) (sequence >>> (i * 8));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        return cipher.doFinal(data);
    }

    private static byte[] hash(byte[]... values) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (byte[] value : values) digest.update(value);
        return digest.digest();
    }

    private static int compare(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) return Integer.compare(a[i] & 255, b[i] & 255);
        }
        return Integer.compare(a.length, b.length);
    }

    private static byte[] text(String value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeUTF(value);
        return bytes.toByteArray();
    }

    static String safeName(String value) {
        String name = value == null ? "" : value.replaceAll("[\\\\/\\p{Cntrl}\\p{Cf}]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return "Файл";
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private static void require(boolean valid) throws IOException {
        if (!valid) throw new IOException("Нарушен протокол или размер файла изменился.");
    }

    private static String detail(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void disconnect(Throwable error) {
        closingReason = "Передача прервана: " + detail(error);
        closeQuietly();
    }

    private void closeQuietly() {
        try { close(); } catch (IOException ignored) {}
    }

    @Override public void close() throws IOException {
        socket.close();
    }
}
