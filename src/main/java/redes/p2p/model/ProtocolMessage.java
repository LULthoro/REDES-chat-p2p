package redes.p2p.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Representa um quadro/mensagem da Camada de Aplicação no protocolo P2P/1.0.
 *
 * Gramática na linha (UTF-8):
 * P2P/1.0|<COMANDO>|<ID_ORIGEM>|<NICK_ORIGEM>|<DESTINO>|<TIMESTAMP>|<PAYLOAD>\n
 */
public record ProtocolMessage(
        String version,
        MessageType type,
        String senderId,
        String senderNick,
        String target,
        long timestamp,
        String payload
) {
    public static final String VERSION = "P2P/1.0";
    public static final String TARGET_ALL = "ALL";

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public static ProtocolMessage create(
            MessageType type,
            String senderId,
            String senderNick,
            String target,
            String payload
    ) {
        return new ProtocolMessage(
                VERSION,
                type,
                senderId,
                senderNick,
                target == null || target.isBlank() ? TARGET_ALL : target,
                System.currentTimeMillis(),
                payload == null ? "" : payload
        );
    }

    public String formattedTime() {
        return TIME_FMT.format(Instant.ofEpochMilli(timestamp));
    }
}
