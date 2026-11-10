package app.fastshare;

import java.io.*;
import java.net.*;
import java.util.*;

final class LanDiscovery implements Closeable {
    private static final int PORT = 45874;
    private static final int MAGIC = 0x46533231;
    private static final long EXPIRES = 5500;

    interface Listener {
        void devices(LanDiscovery discovery, List<Device> devices);
        void incoming(LanDiscovery discovery, Socket socket);
        void stopped(LanDiscovery discovery, String error);
    }

    static final class Device {
        final String id, name;
        final InetAddress address;
        final int port;
        final long seen;

        Device(String id, String name, InetAddress address, int port) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.port = port;
            this.seen = System.nanoTime() / 1000000;
        }
    }

    private final String id = UUID.randomUUID().toString();
    private final Listener listener;
    private final DatagramSocket udp;
    private final ServerSocket tcp;
    private final byte[] beacon;

    LanDiscovery(String name, Listener listener) throws IOException {
        this.listener = listener;
        tcp = new ServerSocket(0);
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(null);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(PORT));
            socket.setSoTimeout(450);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeInt(MAGIC);
            data.writeUTF(id);
            data.writeUTF(PeerLink.safeName(name));
            data.writeShort(tcp.getLocalPort());
            beacon = bytes.toByteArray();
            udp = socket;
        } catch (IOException | RuntimeException error) {
            if (socket != null) socket.close();
            tcp.close();
            throw error;
        }
    }

    void start() {
        new Thread(this::scan, "fastshare-discovery").start();
        new Thread(this::listen, "fastshare-lan-listener").start();
    }

    private void scan() {
        Map<String, Device> devices = new LinkedHashMap<>();
        byte[] buffer = new byte[512];
        long lastBeacon = -10000;
        try {
            while (!udp.isClosed()) {
                long now = System.nanoTime() / 1000000;
                if (now - lastBeacon >= 1500) {
                    broadcast();
                    lastBeacon = now;
                    boolean changed = false;
                    for (Iterator<Device> it = devices.values().iterator(); it.hasNext();) {
                        if (now - it.next().seen > EXPIRES) {
                            it.remove();
                            changed = true;
                        }
                    }
                    if (changed) listener.devices(this, new ArrayList<>(devices.values()));
                }
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    udp.receive(packet);
                } catch (SocketTimeoutException ignored) {
                    continue;
                }
                Device device = decode(packet);
                if (device != null && (devices.size() < 100 || devices.containsKey(device.id))) {
                    Device previous = devices.put(device.id, device);
                    if (previous == null || !previous.name.equals(device.name)
                            || !previous.address.equals(device.address) || previous.port != device.port) {
                        listener.devices(this, new ArrayList<>(devices.values()));
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            fail("Поиск устройств остановился. Проверь подключение к Wi‑Fi.");
        }
    }

    private void broadcast() throws IOException {
        Set<InetAddress> destinations = new HashSet<>();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback() || network.isPointToPoint()) continue;
            for (InterfaceAddress address : network.getInterfaceAddresses()) {
                if (address.getBroadcast() != null) destinations.add(address.getBroadcast());
            }
        }
        destinations.add(InetAddress.getByName("255.255.255.255"));
        for (InetAddress address : destinations) {
            try { udp.send(new DatagramPacket(beacon, beacon.length, address, PORT)); }
            catch (IOException ignored) {}
        }
    }

    private Device decode(DatagramPacket packet) {
        if (!(packet.getAddress() instanceof Inet4Address) || !local(packet.getAddress())) return null;
        try {
            DataInputStream data = new DataInputStream(new ByteArrayInputStream(packet.getData(), 0, packet.getLength()));
            if (data.readInt() != MAGIC) return null;
            String peerId = data.readUTF();
            String name = data.readUTF();
            int port = data.readUnsignedShort();
            if (peerId.equals(id) || peerId.length() != 36 || port == 0 || data.available() != 0) return null;
            return new Device(peerId, PeerLink.safeName(name), packet.getAddress(), port);
        } catch (IOException ignored) {
            return null;
        }
    }

    private void listen() {
        try {
            while (!tcp.isClosed()) {
                Socket socket = tcp.accept();
                if (local(socket.getInetAddress())) listener.incoming(this, socket);
                else socket.close();
            }
        } catch (IOException error) {
            fail("Не удалось принимать подключения по локальной сети.");
        }
    }

    private static boolean local(InetAddress address) {
        return address.isSiteLocalAddress() || address.isLinkLocalAddress();
    }

    private void fail(String message) {
        if (udp.isClosed()) return;
        try { close(); } catch (IOException ignored) {}
        listener.stopped(this, message);
    }

    @Override public void close() throws IOException {
        udp.close();
        tcp.close();
    }
}
