package redes.p2p.model;

/**
 * Representa um nó vizinho (Peer/Servent) na Tabela de Vizinhos.
 */
public final class PeerInfo {
    private final String nodeId;
    private volatile String nickname;
    private volatile String ipAddress;
    private volatile int tcpPort;
    private volatile long lastSeenMillis;
    private volatile boolean tcpConnected;

    public PeerInfo(String nodeId, String nickname, String ipAddress, int tcpPort) {
        this.nodeId = nodeId;
        this.nickname = nickname;
        this.ipAddress = ipAddress;
        this.tcpPort = tcpPort;
        this.lastSeenMillis = System.currentTimeMillis();
        this.tcpConnected = false;
    }

    public String nodeId() {
        return nodeId;
    }

    public String nickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String ipAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public int tcpPort() {
        return tcpPort;
    }

    public void setTcpPort(int tcpPort) {
        this.tcpPort = tcpPort;
    }

    public long lastSeenMillis() {
        return lastSeenMillis;
    }

    public void touch() {
        this.lastSeenMillis = System.currentTimeMillis();
    }

    public boolean isTcpConnected() {
        return tcpConnected;
    }

    public void setTcpConnected(boolean tcpConnected) {
        this.tcpConnected = tcpConnected;
    }

    public String endpoint() {
        return ipAddress + ":" + tcpPort;
    }

    @Override
    public String toString() {
        return String.format("%s (%s) @ %s:%d [TCP=%s]",
                nickname, nodeId, ipAddress, tcpPort, tcpConnected ? "ATIVO" : "DESCOBERTO");
    }
}
