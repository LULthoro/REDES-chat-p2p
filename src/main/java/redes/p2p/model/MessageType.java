package redes.p2p.model;

/**
 * Comandos definidos na gramática do Protocolo de Aplicação P2P/1.0.
 *
 * Divididos conforme a camada de transporte subjacente:
 * - UDP (Descoberta de vizinhos e presença na LAN): BEACON, BYE_UDP
 * - TCP (Sessão ponto a ponto persistente e confiável): HELLO, HELLO_ACK, MSG_ALL, MSG_PRIV, FILE_SEND, PING, PONG, BYE
 */
public enum MessageType {
    // --- Comandos sobre UDP (Sem conexão - Descoberta/Presença) ---
    BEACON("UDP", "Anúncio periódico de presença e porta TCP de escuta"),
    BYE_UDP("UDP", "Aviso de saída voluntária na rede local via broadcast/multicast"),

    // --- Comandos sobre TCP (Orientado a conexão - Dados Confiáveis) ---
    HELLO("TCP", "Handshake inicial para registrar canal TCP full-duplex entre dois nós"),
    HELLO_ACK("TCP", "Confirmação do handshake TCP com dados do nó receptor"),
    MSG_ALL("TCP", "Mensagem de texto difundida para todos os nós conectados (Grupo)"),
    MSG_PRIV("TCP", "Mensagem de texto privada exclusiva para um nó destino"),
    FILE_SEND("TCP", "Transferência de pequeno arquivo codificado em Base64 com metadados"),
    PING("TCP", "Verificação de vivacidade (keep-alive) da conexão TCP"),
    PONG("TCP", "Resposta ao comando PING"),
    BYE("TCP", "Encerramento gracioso da conexão TCP persistente");

    private final String transport;
    private final String description;

    MessageType(String transport, String description) {
        this.transport = transport;
        this.description = description;
    }

    public String transport() {
        return transport;
    }

    public String description() {
        return description;
    }
}
