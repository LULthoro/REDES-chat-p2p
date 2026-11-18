package redes.p2p.protocol;

import redes.p2p.model.MessageType;
import redes.p2p.model.ProtocolMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Codificador e Decodificador (Parser) da Gramática do Protocolo de Aplicação P2P/1.0.
 *
 * Gramática Formal (EBNF Simplificada):
 *   QUADRO    ::= VERSAO "|" COMANDO "|" ID_ORIGEM "|" NICK_ORIGEM "|" DESTINO "|" TIMESTAMP "|" PAYLOAD "\n"
 *   VERSAO    ::= "P2P/1.0"
 *   COMANDO   ::= "BEACON" | "BYE_UDP" | "HELLO" | "HELLO_ACK" | "MSG_ALL" | "MSG_PRIV" | "FILE_SEND" | "PING" | "PONG" | "BYE"
 *   ID_ORIGEM ::= TEXTO_ESCAPADO
 *   NICK      ::= TEXTO_ESCAPADO
 *   DESTINO   ::= "ALL" | TEXTO_ESCAPADO
 *   TIMESTAMP ::= DIGITOS
 *   PAYLOAD   ::= TEXTO_ESCAPADO
 *
 * Regras de Escape de Delimitadores:
 *   "\" -> "\\"
 *   "|" -> "\p"
 *   "\n" -> "\n"
 *   "\r" -> "\r"
 */
public final class ProtocolCodec {

    public static final char DELIMITER = '|';

    private ProtocolCodec() {
    }

    /**
     * Serializa um objeto ProtocolMessage para a linha textual do protocolo (sem o \n final).
     */
    public static String encode(ProtocolMessage msg) {
        return msg.version() + DELIMITER +
                msg.type().name() + DELIMITER +
                escape(msg.senderId()) + DELIMITER +
                escape(msg.senderNick()) + DELIMITER +
                escape(msg.target()) + DELIMITER +
                msg.timestamp() + DELIMITER +
                escape(msg.payload());
    }

    /**
     * Serializa um ProtocolMessage em bytes UTF-8 prontos para DatagramPacket (UDP) ou OutputStream (TCP com \n).
     */
    public static byte[] encodeBytes(ProtocolMessage msg, boolean appendNewline) {
        String raw = encode(msg);
        if (appendNewline) {
            raw += "\n";
        }
        return raw.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Faz o parsing de uma linha recebida via UDP ou TCP para um ProtocolMessage.
     */
    public static ProtocolMessage decode(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            throw new IllegalArgumentException("Quadro vazio recebido.");
        }
        String line = rawLine.stripTrailing();
        List<String> tokens = splitTopLevel(line, DELIMITER, 7);
        if (tokens.size() < 7) {
            throw new IllegalArgumentException("Quadro inválido (esperado 7 campos): " + line);
        }

        String version = tokens.get(0);
        if (!ProtocolMessage.VERSION.equals(version)) {
            throw new IllegalArgumentException("Versão de protocolo incompatível: " + version);
        }

        MessageType type = MessageType.valueOf(tokens.get(1));
        String senderId = unescape(tokens.get(2));
        String senderNick = unescape(tokens.get(3));
        String target = unescape(tokens.get(4));
        long timestamp = Long.parseLong(tokens.get(5));
        String payload = unescape(tokens.get(6));

        return new ProtocolMessage(version, type, senderId, senderNick, target, timestamp, payload);
    }

    /**
     * Empacota metadados e conteúdo binário de um arquivo para o payload do comando FILE_SEND.
     * Formato interno do payload: nomeArquivo:tamanhoBytes:base64
     */
    public static String encodeFilePayload(String fileName, byte[] data) {
        String safeName = fileName.replace(":", "_");
        String b64 = Base64.getEncoder().encodeToString(data);
        return safeName + ":" + data.length + ":" + b64;
    }

    /**
     * Decodifica o payload de um comando FILE_SEND.
     */
    public static FilePayload decodeFilePayload(String payload) {
        int firstColon = payload.indexOf(':');
        int secondColon = payload.indexOf(':', firstColon + 1);
        if (firstColon <= 0 || secondColon <= firstColon) {
            throw new IllegalArgumentException("Payload de arquivo malformado.");
        }
        String fileName = payload.substring(0, firstColon);
        int size = Integer.parseInt(payload.substring(firstColon + 1, secondColon));
        byte[] bytes = Base64.getDecoder().decode(payload.substring(secondColon + 1));
        return new FilePayload(fileName, size, bytes);
    }

    public record FilePayload(String fileName, int declaredSize, byte[] data) {
    }

    public static String escape(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '|' -> sb.append("\\p");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String unescape(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < input.length()) {
                char next = input.charAt(++i);
                switch (next) {
                    case '\\' -> sb.append('\\');
                    case 'p' -> sb.append('|');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    default -> {
                        sb.append('\\').append(next);
                    }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static List<String> splitTopLevel(String str, char delimiter, int maxParts) {
        List<String> parts = new ArrayList<>(maxParts);
        int start = 0;
        for (int i = 0; i < str.length() && parts.size() < maxParts - 1; i++) {
            if (str.charAt(i) == delimiter) {
                parts.add(str.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(str.substring(start));
        return parts;
    }
}
