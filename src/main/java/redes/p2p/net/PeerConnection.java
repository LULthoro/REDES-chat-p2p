package redes.p2p.net;

import redes.p2p.model.PeerInfo;
import redes.p2p.model.ProtocolMessage;
import redes.p2p.protocol.ProtocolCodec;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Representa uma conexão TCP ponto a ponto persistente (Full-Duplex) com um nó vizinho.
 *
 * Cada instância utiliza uma Virtual Thread do Java 21 (Project Loom) dedicada à leitura
 * contínua do fluxo de entrada TCP, permitindo escalar centenas de conexões simultâneas
 * com custo mínimo de memória e detectar quedas abruptas (TCP FIN/RST) instantaneamente.
 */
public final class PeerConnection implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile PeerInfo peerInfo;

    public PeerConnection(Socket socket, PeerInfo initialPeerInfo) throws IOException {
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        this.socket.setKeepAlive(true);
        this.peerInfo = initialPeerInfo;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    public PeerInfo peerInfo() {
        return peerInfo;
    }

    public void setPeerInfo(PeerInfo peerInfo) {
        this.peerInfo = peerInfo;
    }

    public String remoteAddressString() {
        if (peerInfo != null) {
            return peerInfo.endpoint();
        }
        return socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
    }

    /**
     * Inicia a Virtual Thread do Java 21 responsável por ler continuamente os quadros TCP deste par.
     */
    public void startReaderVirtualThread(
            BiConsumer<PeerConnection, ProtocolMessage> onMessage,
            BiConsumer<PeerConnection, String> onDisconnect,
            NodeListener traceListener
    ) {
        String threadName = "tcp-vthread-" + remoteAddressString();
        Thread.ofVirtual().name(threadName).start(() -> {
            String disconnectReason = "EOF (Conexão encerrada pelo par)";
            try {
                String line;
                while (!closed.get() && (line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (traceListener != null) {
                        traceListener.onProtocolFrameTrace("RX", "TCP", remoteAddressString(), line);
                    }
                    ProtocolMessage msg = ProtocolCodec.decode(line);
                    if (peerInfo != null) {
                        peerInfo.touch();
                    }
                    onMessage.accept(this, msg);
                }
            } catch (IOException e) {
                if (!closed.get()) {
                    disconnectReason = "Queda abrupta detectada na camada de transporte (" + e.getMessage() + ")";
                }
            } catch (Exception e) {
                if (!closed.get()) {
                    disconnectReason = "Erro no protocolo: " + e.getMessage();
                }
            } finally {
                boolean wasOpen = closed.compareAndSet(false, true);
                closeQuietly();
                if (wasOpen) {
                    onDisconnect.accept(this, disconnectReason);
                }
            }
        });
    }

    /**
     * Envia de forma sincronizada um quadro do protocolo P2P/1.0 pelo canal TCP persistente.
     */
    public synchronized boolean send(ProtocolMessage message, NodeListener traceListener) {
        if (closed.get()) {
            return false;
        }
        try {
            String encoded = ProtocolCodec.encode(message);
            if (traceListener != null) {
                traceListener.onProtocolFrameTrace("TX", "TCP", remoteAddressString(), encoded);
            }
            writer.write(encoded);
            writer.write('\n');
            writer.flush();
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    public boolean isClosed() {
        return closed.get() || socket.isClosed();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
