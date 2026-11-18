package redes.p2p.net;

import redes.p2p.model.MessageType;
import redes.p2p.model.ProtocolMessage;
import redes.p2p.protocol.ProtocolCodec;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Serviço de Descoberta de Nós e Presença na Rede Local via UDP (Broadcast + Multicast).
 *
 * Opera na Camada de Transporte sem conexão (UDP):
 * - Envia periodicamente quadros BEACON anunciando <IP>:<PORTA_TCP> do Servent local.
 * - Escuta quadros BEACON e BYE_UDP de outros nós na mesma máquina (múltiplos terminais)
 *   ou em dispositivos distintos na mesma LAN/Wi-Fi.
 */
public final class UdpDiscoveryService implements AutoCloseable {

    public static final String DEFAULT_MULTICAST_GROUP = "230.0.0.1";
    public static final int DEFAULT_UDP_PORT = 4446;
    public static final long DEFAULT_BEACON_INTERVAL_MS = 2500L;

    private final String localNodeId;
    private volatile String localNickname;
    private final String localIp;
    private final int localTcpPort;
    private final int udpPort;
    private final long beaconIntervalMs;
    private final BiConsumer<ProtocolMessage, String> onUdpMessage;
    private final NodeListener listener;

    private MulticastSocket receiveSocket;
    private DatagramSocket sendSocket;
    private InetAddress multicastAddress;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public UdpDiscoveryService(
            String localNodeId,
            String localNickname,
            String localIp,
            int localTcpPort,
            int udpPort,
            long beaconIntervalMs,
            BiConsumer<ProtocolMessage, String> onUdpMessage,
            NodeListener listener
    ) {
        this.localNodeId = localNodeId;
        this.localNickname = localNickname;
        this.localIp = localIp;
        this.localTcpPort = localTcpPort;
        this.udpPort = udpPort;
        this.beaconIntervalMs = beaconIntervalMs;
        this.onUdpMessage = onUdpMessage;
        this.listener = listener;
    }

    public void setLocalNickname(String localNickname) {
        this.localNickname = localNickname;
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        // MulticastSocket permite que múltiplos processos na mesma máquina façam bind na mesma porta UDP (SO_REUSEADDR)
        this.receiveSocket = new MulticastSocket(null);
        this.receiveSocket.setReuseAddress(true);
        this.receiveSocket.setBroadcast(true);
        this.receiveSocket.bind(new InetSocketAddress(udpPort));

        this.sendSocket = new DatagramSocket();
        this.sendSocket.setBroadcast(true);

        this.multicastAddress = InetAddress.getByName(DEFAULT_MULTICAST_GROUP);
        joinMulticastGroupsQuietly();

        // Virtual Thread para escuta contínua de datagramas UDP
        Thread.ofVirtual().name("udp-discovery-listener-" + localTcpPort).start(this::listenLoop);

        // Virtual Thread para envio periódico de Beacons UDP
        Thread.ofVirtual().name("udp-beacon-emitter-" + localTcpPort).start(this::beaconLoop);
    }

    /**
     * Dispara imediatamente um Beacon UDP na rede (útil na inicialização ou mudança de nick).
     */
    public void sendBeaconNow() {
        if (!running.get()) {
            return;
        }
        String payload = localIp + ":" + localTcpPort;
        ProtocolMessage beacon = ProtocolMessage.create(
                MessageType.BEACON,
                localNodeId,
                localNickname,
                ProtocolMessage.TARGET_ALL,
                payload
        );
        broadcastUdpMessage(beacon);
    }

    /**
     * Envia um aviso rápido via UDP informando que este nó está saindo voluntariamente.
     */
    public void sendByeUdp() {
        if (!running.get()) {
            return;
        }
        String payload = localIp + ":" + localTcpPort;
        ProtocolMessage bye = ProtocolMessage.create(
                MessageType.BYE_UDP,
                localNodeId,
                localNickname,
                ProtocolMessage.TARGET_ALL,
                payload
        );
        broadcastUdpMessage(bye);
    }

    private void beaconLoop() {
        while (running.get()) {
            try {
                sendBeaconNow();
                Thread.sleep(beaconIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
            }
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[4096];
        while (running.get() && !receiveSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                receiveSocket.receive(packet);

                String raw = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                if (raw.isEmpty()) {
                    continue;
                }

                ProtocolMessage msg = ProtocolCodec.decode(raw);
                // Ignora os próprios datagramas enviados por este nó
                if (localNodeId.equals(msg.senderId())) {
                    continue;
                }

                String packetSourceIp = packet.getAddress().getHostAddress();
                if (listener != null) {
                    listener.onProtocolFrameTrace("RX", "UDP", packetSourceIp + ":" + packet.getPort(), raw);
                }

                onUdpMessage.accept(msg, packetSourceIp);
            } catch (IOException e) {
                if (running.get()) {
                    if (listener != null) {
                        listener.onSystemLog("UDP", "Aviso na leitura UDP: " + e.getMessage());
                    }
                }
            } catch (Exception ignored) {
                // Ignora datagramas estranhos na rede local que não pertençam ao protocolo P2P/1.0
            }
        }
    }

    private void broadcastUdpMessage(ProtocolMessage message) {
        byte[] data = ProtocolCodec.encodeBytes(message, false);
        String raw = new String(data, StandardCharsets.UTF_8);

        for (InetAddress targetAddr : collectBroadcastAndMulticastTargets()) {
            try {
                DatagramPacket packet = new DatagramPacket(data, data.length, targetAddr, udpPort);
                sendSocket.send(packet);
                if (listener != null && message.type() != MessageType.BEACON) {
                    listener.onProtocolFrameTrace("TX", "UDP", targetAddr.getHostAddress() + ":" + udpPort, raw);
                }
            } catch (IOException ignored) {
            }
        }
    }

    private Set<InetAddress> collectBroadcastAndMulticastTargets() {
        Set<InetAddress> targets = new LinkedHashSet<>();
        try {
            if (multicastAddress != null) {
                targets.add(multicastAddress);
            }
            targets.add(InetAddress.getByName("255.255.255.255"));
            targets.add(InetAddress.getByName("127.255.255.255"));

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface nif : Collections.list(interfaces)) {
                    if (!nif.isUp()) {
                        continue;
                    }
                    for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                        InetAddress bcast = ia.getBroadcast();
                        if (bcast != null) {
                            targets.add(bcast);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return targets;
    }

    private void joinMulticastGroupsQuietly() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface nif : Collections.list(interfaces)) {
                    if (nif.isUp() && nif.supportsMulticast()) {
                        try {
                            receiveSocket.joinGroup(new InetSocketAddress(multicastAddress, udpPort), nif);
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Descobre o endereço IPv4 local preferencial da máquina na LAN (ou 127.0.0.1 em modo isolado).
     */
    public static String detectLocalLanIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface nif : Collections.list(interfaces)) {
                    if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                        continue;
                    }
                    for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            if (receiveSocket != null) {
                receiveSocket.close();
            }
            if (sendSocket != null) {
                sendSocket.close();
            }
        }
    }
}
