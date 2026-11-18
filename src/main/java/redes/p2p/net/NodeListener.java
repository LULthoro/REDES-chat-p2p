package redes.p2p.net;

import redes.p2p.model.PeerInfo;
import redes.p2p.model.ProtocolMessage;

import java.nio.file.Path;

/**
 * Interface de eventos gerados pelo nó P2P (Servent).
 * Permite desacoplar o motor de rede da interface (CLI, Testes ou Mobile).
 */
public interface NodeListener {

    default void onPeerDiscovered(PeerInfo peer) {}

    default void onPeerConnected(PeerInfo peer, boolean inbound) {}

    default void onPeerDisconnected(PeerInfo peer, String reason) {}

    default void onGroupMessage(ProtocolMessage message) {}

    default void onPrivateMessage(ProtocolMessage message) {}

    default void onFileReceived(ProtocolMessage message, String fileName, Path savedPath, int sizeBytes) {}

    default void onSystemLog(String category, String details) {}

    default void onProtocolFrameTrace(String direction, String transport, String remoteEndpoint, String rawFrame) {}
}
