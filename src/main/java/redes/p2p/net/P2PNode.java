package redes.p2p.net;

import redes.p2p.model.MessageType;
import redes.p2p.model.PeerInfo;
import redes.p2p.model.ProtocolMessage;
import redes.p2p.protocol.ProtocolCodec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Núcleo do Nó P2P (Servent = Server + Client).
 *
 * Responsabilidades:
 * 1. Executar um ServerSocket TCP concorrente (baseado em Virtual Threads do Java 21) para receber conexões de pares.
 * 2. Executar o serviço de descoberta UDP (Broadcast/Multicast) para anunciar presença e descobrir vizinhos na LAN.
 * 3. Estabelecer e manter canais TCP persistentes Full-Duplex em malha (mesh) com os vizinhos descobertos.
 * 4. Roteamento de mensagens em grupo (MSG_ALL), privadas (MSG_PRIV) e arquivos (FILE_SEND).
 * 5. Detecção imediata de desconexão abrupta (TCP FIN/RST) e limpeza da Tabela de Vizinhos por timeout.
 */
public final class P2PNode implements AutoCloseable {

    public static final int DEFAULT_BASE_TCP_PORT = 5001;
    public static final long PEER_TIMEOUT_MS = 10_000L;
    public static final int MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB

    private final String nodeId;
    private volatile String nickname;
    private final String localIp;
    private final int tcpPort;
    private final int udpPort;
    private final long beaconIntervalMs;
    private final NodeListener listener;
    private final Path downloadDir;

    private ServerSocket serverSocket;
    private UdpDiscoveryService udpDiscovery;

    // Tabela de Vizinhos (nodeId -> PeerInfo)
    private final Map<String, PeerInfo> knownPeers = new ConcurrentHashMap<>();
    // Conexões TCP Persistentes Ativas (nodeId -> PeerConnection)
    private final Map<String, PeerConnection> activeConnections = new ConcurrentHashMap<>();
    // Controle de tentativas de conexão em andamento para evitar corrida
    private final Set<String> connectingEndpoints = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean running = new AtomicBoolean(false);

    public P2PNode(
            String nickname,
            int requestedTcpPort,
            int udpPort,
            long beaconIntervalMs,
            NodeListener listener
    ) throws IOException {
        this.nodeId = "N-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        this.nickname = (nickname == null || nickname.isBlank()) ? "Peer-" + this.nodeId : nickname.trim();
        this.localIp = UdpDiscoveryService.detectLocalLanIp();
        this.serverSocket = bindServerSocket(requestedTcpPort);
        this.tcpPort = this.serverSocket.getLocalPort();
        this.udpPort = udpPort;
        this.beaconIntervalMs = beaconIntervalMs;
        this.listener = listener != null ? listener : new NodeListener() {};
        this.downloadDir = Paths.get("downloads_p2p", this.nickname + "_" + this.tcpPort);
    }

    /**
     * Inicia o Servent: servidor TCP, descoberta UDP e monitor de tolerância a falhas.
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        // 1. Virtual Thread para aceitar conexões TCP de entrada (papel Servidor do Servent)
        Thread.ofVirtual().name("tcp-server-acceptor-" + tcpPort).start(this::acceptTcpLoop);

        // 2. Serviço de descoberta automática e presença via UDP
        this.udpDiscovery = new UdpDiscoveryService(
                nodeId,
                nickname,
                localIp,
                tcpPort,
                udpPort,
                beaconIntervalMs,
                this::handleUdpMessage,
                listener
        );
        this.udpDiscovery.start();

        // 3. Virtual Thread para verificação periódica de saúde e limpeza de vizinhos inativos
        Thread.ofVirtual().name("peer-watchdog-" + tcpPort).start(this::watchdogLoop);

        listener.onSystemLog("INICIO", String.format(
                "Servent '%s' [%s] ativo em TCP %s:%d | Descoberta UDP na porta %d",
                nickname, nodeId, localIp, tcpPort, udpPort));
    }

    /**
     * Conecta manualmente via TCP direto a um par informando <IP>:<PORTA_TCP>.
     * Útil tanto para demonstração da Prévia quanto para redes onde UDP broadcast é filtrado.
     */
    public boolean connectToPeerEndpoint(String ip, int remoteTcpPort) {
        if (remoteTcpPort == this.tcpPort && (ip.equals(this.localIp) || ip.equals("127.0.0.1") || ip.equals("localhost"))) {
            listener.onSystemLog("AVISO", "Ignorando tentativa de conexão consigo mesmo.");
            return false;
        }
        String endpointKey = ip + ":" + remoteTcpPort;
        if (!connectingEndpoints.add(endpointKey)) {
            return false;
        }

        Thread.ofVirtual().name("tcp-dialer-" + endpointKey).start(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(ip, remoteTcpPort), 3000);
                PeerConnection conn = new PeerConnection(socket, null);
                conn.startReaderVirtualThread(this::handleTcpMessage, this::handleConnectionClosed, listener);

                // Envia HELLO informando nosso endereço IP e porta TCP de escuta
                ProtocolMessage hello = ProtocolMessage.create(
                        MessageType.HELLO,
                        nodeId,
                        nickname,
                        ProtocolMessage.TARGET_ALL,
                        localIp + ":" + tcpPort
                );
                conn.send(hello, listener);
            } catch (IOException e) {
                listener.onSystemLog("TCP", "Não foi possível conectar a " + endpointKey + ": " + e.getMessage());
            } finally {
                connectingEndpoints.remove(endpointKey);
            }
        });
        return true;
    }

    /**
     * Envia uma mensagem em grupo (MSG_ALL) para todos os nós conectados na malha TCP.
     */
    public int sendGroupMessage(String text) {
        ProtocolMessage msg = ProtocolMessage.create(
                MessageType.MSG_ALL,
                nodeId,
                nickname,
                ProtocolMessage.TARGET_ALL,
                text
        );
        int sentCount = 0;
        for (PeerConnection conn : activeConnections.values()) {
            if (conn.send(msg, listener)) {
                sentCount++;
            }
        }
        return sentCount;
    }

    /**
     * Envia uma mensagem privada (MSG_PRIV) exclusivamente pelo canal TCP do nó destinatário.
     */
    public boolean sendPrivateMessage(String targetNickOrId, String text) {
        PeerInfo targetPeer = findPeer(targetNickOrId);
        if (targetPeer == null) {
            return false;
        }
        PeerConnection conn = activeConnections.get(targetPeer.nodeId());
        if (conn == null || conn.isClosed()) {
            return false;
        }
        ProtocolMessage msg = ProtocolMessage.create(
                MessageType.MSG_PRIV,
                nodeId,
                nickname,
                targetPeer.nickname(),
                text
        );
        return conn.send(msg, listener);
    }

    /**
     * Envia um pequeno arquivo pelo canal TCP (para um nó específico ou para "ALL").
     */
    public int sendFile(String targetNickOrIdOrAll, Path filePath) throws IOException {
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new IOException("Arquivo não encontrado: " + filePath);
        }
        long size = Files.size(filePath);
        if (size > MAX_FILE_SIZE_BYTES) {
            throw new IOException("Arquivo excede o limite de " + (MAX_FILE_SIZE_BYTES / (1024 * 1024)) + " MB.");
        }
        byte[] bytes = Files.readAllBytes(filePath);
        String filePayload = ProtocolCodec.encodeFilePayload(filePath.getFileName().toString(), bytes);

        if ("ALL".equalsIgnoreCase(targetNickOrIdOrAll) || "*".equals(targetNickOrIdOrAll)) {
            ProtocolMessage msg = ProtocolMessage.create(
                    MessageType.FILE_SEND,
                    nodeId,
                    nickname,
                    ProtocolMessage.TARGET_ALL,
                    filePayload
            );
            int count = 0;
            for (PeerConnection conn : activeConnections.values()) {
                if (conn.send(msg, listener)) {
                    count++;
                }
            }
            return count;
        } else {
            PeerInfo targetPeer = findPeer(targetNickOrIdOrAll);
            if (targetPeer == null) {
                return 0;
            }
            PeerConnection conn = activeConnections.get(targetPeer.nodeId());
            if (conn == null || conn.isClosed()) {
                return 0;
            }
            ProtocolMessage msg = ProtocolMessage.create(
                    MessageType.FILE_SEND,
                    nodeId,
                    nickname,
                    targetPeer.nickname(),
                    filePayload
            );
            return conn.send(msg, listener) ? 1 : 0;
        }
    }

    /**
     * Procura um vizinho na tabela pelo apelido (case-insensitive), pelo ID ou por endpoint.
     */
    public PeerInfo findPeer(String nickOrId) {
        if (nickOrId == null || nickOrId.isBlank()) {
            return null;
        }
        String query = nickOrId.trim();
        PeerInfo byId = knownPeers.get(query);
        if (byId != null) {
            return byId;
        }
        for (PeerInfo peer : knownPeers.values()) {
            if (peer.nickname().equalsIgnoreCase(query)
                    || peer.nodeId().equalsIgnoreCase(query)
                    || peer.endpoint().equalsIgnoreCase(query)) {
                return peer;
            }
        }
        return null;
    }

    public List<PeerInfo> getNeighborsSnapshot() {
        List<PeerInfo> list = new ArrayList<>(knownPeers.values());
        list.sort(Comparator.comparing(PeerInfo::nickname, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PeerInfo::nodeId));
        return list;
    }

    public int getActiveTcpConnectionCount() {
        return activeConnections.size();
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String newNickname) {
        if (newNickname == null || newNickname.isBlank()) {
            return;
        }
        this.nickname = newNickname.trim();
        if (udpDiscovery != null) {
            udpDiscovery.setLocalNickname(this.nickname);
            udpDiscovery.sendBeaconNow();
        }
    }

    public String getLocalIp() {
        return localIp;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public void triggerBeaconNow() {
        if (udpDiscovery != null) {
            udpDiscovery.sendBeaconNow();
        }
    }

    // =========================================================================
    // Tratamento de pacotes UDP (Descoberta e Presença)
    // =========================================================================

    private void handleUdpMessage(ProtocolMessage msg, String packetSourceIp) {
        if (!running.get() || nodeId.equals(msg.senderId())) {
            return;
        }

        switch (msg.type()) {
            case BEACON -> {
                Endpoint ep = parseEndpoint(msg.payload(), packetSourceIp);
                if (ep == null) {
                    return;
                }

                final boolean[] isNew = {false};
                PeerInfo peer = knownPeers.compute(msg.senderId(), (id, existing) -> {
                    if (existing == null) {
                        isNew[0] = true;
                        return new PeerInfo(id, msg.senderNick(), ep.ip(), ep.port());
                    } else {
                        existing.setNickname(msg.senderNick());
                        existing.setIpAddress(ep.ip());
                        existing.setTcpPort(ep.port());
                        existing.touch();
                        return existing;
                    }
                });

                if (isNew[0]) {
                    listener.onPeerDiscovered(peer);
                }

                // Se ainda não há conexão TCP persistente com este vizinho, estabelece a malha
                PeerConnection existingConn = activeConnections.get(peer.nodeId());
                if (existingConn == null || existingConn.isClosed()) {
                    // Regra determinística de desempate por nodeId para evitar conexões cruzadas simultâneas:
                    // O nó com menor ID inicia imediatamente; o nó com maior ID aguarda 1.2s como fallback.
                    if (this.nodeId.compareTo(peer.nodeId()) < 0) {
                        connectToPeerEndpoint(peer.ipAddress(), peer.tcpPort());
                    } else {
                        Thread.ofVirtual().name("delayed-dial-" + peer.nodeId()).start(() -> {
                            try {
                                Thread.sleep(1200);
                                PeerConnection c = activeConnections.get(peer.nodeId());
                                if (running.get() && (c == null || c.isClosed())) {
                                    connectToPeerEndpoint(peer.ipAddress(), peer.tcpPort());
                                }
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                        });
                    }
                }
            }
            case BYE_UDP -> {
                removePeerAndCloseConnection(msg.senderId(), "Saída voluntária anunciada via UDP (BYE_UDP)");
            }
            default -> {
            }
        }
    }

    // =========================================================================
    // Servidor TCP e Tratamento de Quadros TCP
    // =========================================================================

    private void acceptTcpLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                PeerConnection conn = new PeerConnection(clientSocket, null);
                conn.startReaderVirtualThread(this::handleTcpMessage, this::handleConnectionClosed, listener);
            } catch (IOException e) {
                if (running.get()) {
                    listener.onSystemLog("TCP", "Erro ao aceitar conexão TCP: " + e.getMessage());
                }
            }
        }
    }

    private void handleTcpMessage(PeerConnection conn, ProtocolMessage msg) {
        if (!running.get() || nodeId.equals(msg.senderId())) {
            return;
        }

        switch (msg.type()) {
            case HELLO -> {
                PeerInfo peer = registerOrUpdatePeerFromHandshake(conn, msg, true);
                ProtocolMessage ack = ProtocolMessage.create(
                        MessageType.HELLO_ACK,
                        nodeId,
                        nickname,
                        peer.nickname(),
                        localIp + ":" + tcpPort
                );
                conn.send(ack, listener);
            }
            case HELLO_ACK -> {
                registerOrUpdatePeerFromHandshake(conn, msg, false);
            }
            case MSG_ALL -> {
                updatePeerMetadataFromMessage(msg);
                listener.onGroupMessage(msg);
            }
            case MSG_PRIV -> {
                updatePeerMetadataFromMessage(msg);
                listener.onPrivateMessage(msg);
            }
            case FILE_SEND -> {
                updatePeerMetadataFromMessage(msg);
                handleIncomingFile(msg);
            }
            case PING -> {
                ProtocolMessage pong = ProtocolMessage.create(
                        MessageType.PONG,
                        nodeId,
                        nickname,
                        msg.senderNick(),
                        "OK"
                );
                conn.send(pong, listener);
            }
            case PONG -> {
                updatePeerMetadataFromMessage(msg);
            }
            case BYE -> {
                removePeerAndCloseConnection(msg.senderId(), "Encerramento gracioso da sessão TCP (BYE)");
            }
            default -> {
            }
        }
    }

    private PeerInfo registerOrUpdatePeerFromHandshake(PeerConnection conn, ProtocolMessage msg, boolean inbound) {
        String fallbackIp = conn.remoteAddressString().split(":")[0];
        Endpoint ep = parseEndpoint(msg.payload(), fallbackIp);
        String ip = ep != null ? ep.ip() : fallbackIp;
        int port = ep != null ? ep.port() : 0;

        PeerInfo peer = knownPeers.compute(msg.senderId(), (id, existing) -> {
            if (existing == null) {
                return new PeerInfo(id, msg.senderNick(), ip, port);
            }
            existing.setNickname(msg.senderNick());
            existing.setIpAddress(ip);
            if (port > 0) {
                existing.setTcpPort(port);
            }
            existing.touch();
            return existing;
        });

        conn.setPeerInfo(peer);

        // Garante apenas uma conexão TCP ativa por par usando desempate simétrico caso ocorra abertura simultânea
        final boolean[] newlyConnected = {false};
        activeConnections.compute(peer.nodeId(), (id, existingConn) -> {
            if (existingConn == null || existingConn.isClosed()) {
                newlyConnected[0] = true;
                return conn;
            }
            if (existingConn == conn) {
                return conn;
            }
            boolean preferNew = (this.nodeId.compareTo(peer.nodeId()) < 0 && !inbound)
                    || (this.nodeId.compareTo(peer.nodeId()) > 0 && inbound);
            if (preferNew) {
                existingConn.close();
                return conn;
            } else {
                conn.close();
                return existingConn;
            }
        });

        peer.setTcpConnected(true);
        if (newlyConnected[0]) {
            listener.onPeerConnected(peer, inbound);
        }
        return peer;
    }

    private void updatePeerMetadataFromMessage(ProtocolMessage msg) {
        PeerInfo peer = knownPeers.get(msg.senderId());
        if (peer != null) {
            peer.setNickname(msg.senderNick());
            peer.touch();
        }
    }

    private void handleIncomingFile(ProtocolMessage msg) {
        try {
            ProtocolCodec.FilePayload filePayload = ProtocolCodec.decodeFilePayload(msg.payload());
            Files.createDirectories(downloadDir);
            Path targetPath = downloadDir.resolve(filePayload.fileName());
            Files.write(targetPath, filePayload.data());
            listener.onFileReceived(msg, filePayload.fileName(), targetPath.toAbsolutePath(), filePayload.data().length);
        } catch (Exception e) {
            listener.onSystemLog("ARQUIVO", "Falha ao processar arquivo recebido de " + msg.senderNick() + ": " + e.getMessage());
        }
    }

    private void handleConnectionClosed(PeerConnection closedConn, String reason) {
        PeerInfo peer = closedConn.peerInfo();
        if (peer == null) {
            return;
        }
        boolean removed = activeConnections.remove(peer.nodeId(), closedConn);
        if (removed) {
            peer.setTcpConnected(false);
            knownPeers.remove(peer.nodeId());
            listener.onPeerDisconnected(peer, reason);
        }
    }

    private void removePeerAndCloseConnection(String peerNodeId, String reason) {
        PeerConnection conn = activeConnections.remove(peerNodeId);
        if (conn != null) {
            conn.close();
        }
        PeerInfo removedPeer = knownPeers.remove(peerNodeId);
        if (removedPeer != null) {
            removedPeer.setTcpConnected(false);
            listener.onPeerDisconnected(removedPeer, reason);
        }
    }

    private void watchdogLoop() {
        while (running.get()) {
            try {
                Thread.sleep(2500);
                long now = System.currentTimeMillis();
                for (PeerInfo peer : new ArrayList<>(knownPeers.values())) {
                    PeerConnection conn = activeConnections.get(peer.nodeId());
                    boolean tcpAlive = conn != null && !conn.isClosed();
                    if (!tcpAlive && (now - peer.lastSeenMillis() > PEER_TIMEOUT_MS)) {
                        removePeerAndCloseConnection(peer.nodeId(), "Timeout de presença UDP expirado (> " + (PEER_TIMEOUT_MS / 1000) + "s)");
                    } else if (tcpAlive && (now - peer.lastSeenMillis() > PEER_TIMEOUT_MS)) {
                        // Envia PING sobre TCP caso não tenha recebido beacons recentes
                        ProtocolMessage ping = ProtocolMessage.create(
                                MessageType.PING,
                                nodeId,
                                nickname,
                                peer.nickname(),
                                "KEEPALIVE"
                        );
                        if (!conn.send(ping, listener)) {
                            removePeerAndCloseConnection(peer.nodeId(), "Falha no envio de heartbeat TCP (PING)");
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
            }
        }
    }

    private static ServerSocket bindServerSocket(int requestedPort) throws IOException {
        if (requestedPort > 0) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(requestedPort));
                return ss;
            } catch (IOException e) {
                // Se a porta solicitada for a padrão (5001) e já estiver em uso por outro terminal,
                // busca automaticamente a próxima porta livre (5002, 5003, ...)
                if (requestedPort != DEFAULT_BASE_TCP_PORT) {
                    throw e;
                }
            }
        }
        for (int port = DEFAULT_BASE_TCP_PORT; port < DEFAULT_BASE_TCP_PORT + 100; port++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                return ss;
            } catch (IOException ignored) {
            }
        }
        return new ServerSocket(0);
    }

    private record Endpoint(String ip, int port) {}

    private static Endpoint parseEndpoint(String payload, String fallbackIp) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        int colon = payload.lastIndexOf(':');
        if (colon <= 0 || colon == payload.length() - 1) {
            return null;
        }
        try {
            String ipPart = payload.substring(0, colon).trim();
            int portPart = Integer.parseInt(payload.substring(colon + 1).trim());
            String resolvedIp = (ipPart.isEmpty() || "0.0.0.0".equals(ipPart)) ? fallbackIp : ipPart;
            return new Endpoint(resolvedIp, portPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Collection<PeerInfo> getKnownPeers() {
        return knownPeers.values();
    }

    /**
     * Simula a queda abrupta do nó (equivalente a Ctrl+C, kill -9 ou perda de conexão),
     * fechando os sockets TCP/UDP imediatamente SEM enviar mensagens BYE ou BYE_UDP.
     */
    public void simulateAbruptCrash() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (udpDiscovery != null) {
            udpDiscovery.close();
        }
        for (PeerConnection conn : activeConnections.values()) {
            conn.close();
        }
        activeConnections.clear();
        knownPeers.clear();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        // Envia BYE sobre todas as conexões TCP abertas e BYE_UDP na LAN
        ProtocolMessage byeTcp = ProtocolMessage.create(
                MessageType.BYE,
                nodeId,
                nickname,
                ProtocolMessage.TARGET_ALL,
                "Desconectando"
        );
        for (PeerConnection conn : activeConnections.values()) {
            conn.send(byeTcp, listener);
            conn.close();
        }
        activeConnections.clear();
        knownPeers.clear();

        if (udpDiscovery != null) {
            udpDiscovery.sendByeUdp();
            udpDiscovery.close();
        }

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
