package redes.p2p.cli;

import redes.p2p.model.PeerInfo;
import redes.p2p.model.ProtocolMessage;
import redes.p2p.net.NodeListener;
import redes.p2p.net.P2PNode;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interface de Linha de Comando (CLI) interativa para demonstração em múltiplos terminais.
 */
public final class ConsoleUI implements NodeListener {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String RED = "\u001B[31m";
    private static final String GRAY = "\u001B[90m";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private P2PNode node;
    private final AtomicBoolean traceFrames = new AtomicBoolean(false);

    public void attachNode(P2PNode node, boolean initialTrace) {
        this.node = node;
        this.traceFrames.set(initialTrace);
    }

    public void runInteractiveLoop() {
        printBanner();
        printHelp();
        printPrompt();

        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    printPrompt();
                    continue;
                }

                if (!handleInput(line)) {
                    break;
                }
                printPrompt();
            }
        }
    }

    private boolean handleInput(String input) {
        if (input.startsWith("@")) {
            int spaceIdx = input.indexOf(' ');
            if (spaceIdx <= 1 || spaceIdx == input.length() - 1) {
                printlnColored(YELLOW, "Uso: @<apelido> <mensagem privada>");
                return true;
            }
            String target = input.substring(1, spaceIdx).trim();
            String msg = input.substring(spaceIdx + 1).trim();
            sendPrivate(target, msg);
            return true;
        }

        if (!input.startsWith("/")) {
            int count = node.sendGroupMessage(input);
            printlnColored(GREEN, String.format("[%s] [VOCÊ -> GRUPO (%d nós)]: %s", now(), count, input));
            return true;
        }

        String[] parts = input.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "/help", "/ajuda" -> printHelp();
            case "/peers", "/vizinhos" -> printPeersTable();
            case "/status", "/info" -> printStatus();
            case "/beacon" -> {
                node.triggerBeaconNow();
                printlnColored(CYAN, "[UDP] Beacon de presença enviado manualmente na rede local.");
            }
            case "/trace", "/protocolo" -> {
                boolean enabled = !traceFrames.get();
                traceFrames.set(enabled);
                printlnColored(CYAN, "[PROTOCOLO] Exibição de quadros brutos P2P/1.0: " + (enabled ? "ATIVADA" : "DESATIVADA"));
            }
            case "/nick" -> {
                if (parts.length < 2) {
                    printlnColored(YELLOW, "Uso: /nick <novo_apelido>");
                } else {
                    String oldNick = node.getNickname();
                    node.setNickname(parts[1]);
                    printlnColored(CYAN, String.format("Apelido alterado de '%s' para '%s'.", oldNick, node.getNickname()));
                }
            }
            case "/msg", "/priv", "/dm" -> {
                if (parts.length < 3) {
                    printlnColored(YELLOW, "Uso: /msg <apelido_ou_id> <mensagem>");
                } else {
                    sendPrivate(parts[1], parts[2]);
                }
            }
            case "/file", "/arquivo" -> {
                if (parts.length < 3) {
                    printlnColored(YELLOW, "Uso: /file <apelido_ou_ALL> <caminho_do_arquivo>");
                } else {
                    sendFileCommand(parts[1], parts[2]);
                }
            }
            case "/connect", "/conectar" -> {
                if (parts.length < 2) {
                    printlnColored(YELLOW, "Uso: /connect <ip>:<porta_tcp>");
                } else {
                    connectManualCommand(parts[1]);
                }
            }
            case "/crash", "/queda" -> {
                printlnColored(RED, "Simulando queda abrupta do nó (fechando sockets sem enviar BYE)...");
                node.simulateAbruptCrash();
                System.exit(1);
                return false;
            }
            case "/quit", "/sair", "/exit" -> {
                printlnColored(YELLOW, "Encerrando Servent graciosamente (enviando BYE TCP + BYE_UDP)...");
                return false;
            }
            default -> printlnColored(YELLOW, "Comando desconhecido: " + cmd + ". Digite /ajuda para ver os comandos.");
        }
        return true;
    }

    private void sendPrivate(String target, String message) {
        boolean ok = node.sendPrivateMessage(target, message);
        if (ok) {
            printlnColored(MAGENTA, String.format("[%s] [PRIVADO -> %s]: %s", now(), target, message));
        } else {
            printlnColored(RED, "Não foi possível enviar para '" + target + "'. Verifique `/vizinhos`.");
        }
    }

    private void sendFileCommand(String target, String pathStr) {
        try {
            Path path = Paths.get(pathStr);
            int sent = node.sendFile(target, path);
            if (sent > 0) {
                printlnColored(CYAN, String.format("[%s] [ARQUIVO] '%s' enviado via TCP para %d nó(s) (%s).",
                        now(), path.getFileName(), sent, target));
            } else {
                printlnColored(RED, "Nenhum destinatário ativo encontrado para '" + target + "'.");
            }
        } catch (IOException e) {
            printlnColored(RED, "Erro ao enviar arquivo: " + e.getMessage());
        }
    }

    private void connectManualCommand(String endpoint) {
        int colon = endpoint.lastIndexOf(':');
        if (colon <= 0 || colon == endpoint.length() - 1) {
            printlnColored(YELLOW, "Formato inválido. Use: /connect <ip>:<porta_tcp> (ex: /connect 127.0.0.1:5002)");
            return;
        }
        try {
            String ip = endpoint.substring(0, colon).trim();
            int port = Integer.parseInt(endpoint.substring(colon + 1).trim());
            node.connectToPeerEndpoint(ip, port);
            printlnColored(CYAN, "Solicitada conexão TCP direta com " + ip + ":" + port + "...");
        } catch (NumberFormatException e) {
            printlnColored(RED, "Porta TCP inválida.");
        }
    }

    public void printPeersTable() {
        List<PeerInfo> peers = node.getNeighborsSnapshot();
        long now = System.currentTimeMillis();
        System.out.println();
        System.out.println(BOLD + "==================== TABELA DE VIZINHOS (MALHA P2P) ====================" + RESET);
        if (peers.isEmpty()) {
            System.out.println(YELLOW + "  Nenhum nó vizinho conectado no momento. Aguardando beacons UDP..." + RESET);
        } else {
            System.out.printf("  %-10s | %-14s | %-20s | %-10s | %-8s%n",
                    "ID NÓ", "APELIDO", "ENDPOINT TCP", "CANAL TCP", "VISTO HÁ");
            System.out.println("  ----------------------------------------------------------------------");
            for (PeerInfo p : peers) {
                long ageSec = Math.max(0L, (now - p.lastSeenMillis()) / 1000L);
                String status = p.isTcpConnected() ? GREEN + "CONECTADO" + RESET : YELLOW + "DESCOBERTO" + RESET;
                System.out.printf("  %-10s | %-14s | %-20s | %-19s | %ds%n",
                        p.nodeId(), p.nickname(), p.endpoint(), status, ageSec);
            }
        }
        System.out.println(BOLD + "========================================================================" + RESET);
    }

    private void printStatus() {
        System.out.println();
        System.out.println(BOLD + "--- STATUS DO SERVENT LOCAL ---" + RESET);
        System.out.println("  Apelido (Nick) : " + GREEN + node.getNickname() + RESET);
        System.out.println("  ID do Nó       : " + node.getNodeId());
        System.out.println("  Endpoint TCP   : " + node.getLocalIp() + ":" + node.getTcpPort());
        System.out.println("  Descoberta UDP : Porta " + node.getUdpPort() + " (Multicast 230.0.0.1 + Broadcast)");
        System.out.println("  Conexões TCP   : " + node.getActiveTcpConnectionCount() + " ativa(s)");
        System.out.println("  Modo Trace     : " + (traceFrames.get() ? "ATIVO" : "INATIVO"));
    }

    private void printBanner() {
        System.out.println(CYAN + BOLD + """
                ╔══════════════════════════════════════════════════════════════════════╗
                ║     SISTEMA DE CHAT P2P DESCENTRALIZADO - PROTOCOLO P2P/1.0          ║
                ║     Camadas de Aplicação e Transporte (UDP Beacon + Malha TCP)       ║
                ║     Java 21 (Virtual Threads) | Servent (Cliente + Servidor)         ║
                ╚══════════════════════════════════════════════════════════════════════╝""" + RESET);
        System.out.printf("  Nó iniciado: %s%s%s (%s) | Escuta TCP: %s%s:%d%s | Beacon UDP: %d%n",
                BOLD + GREEN, node.getNickname(), RESET,
                node.getNodeId(),
                BOLD, node.getLocalIp(), node.getTcpPort(), RESET,
                node.getUdpPort());
    }

    private void printHelp() {
        System.out.println(GRAY + """
                Comandos disponíveis:
                  <texto livre>                Envia mensagem em GRUPO (MSG_ALL) a todos os vizinhos via TCP
                  @<apelido> <texto>           Envia mensagem PRIVADA (MSG_PRIV) direta ao nó escolhido
                  /msg <apelido> <texto>       Alternativa para enviar mensagem privada
                  /file <apelido|ALL> <arq>    Envia pequeno arquivo pelo canal TCP (FILE_SEND)
                  /vizinhos  (ou /peers)       Exibe a Tabela de Vizinhos atualizada em tempo real
                  /connect <ip>:<porta>        Estabelece conexão TCP direta manual com um par
                  /trace                       Liga/desliga inspeção ao vivo dos quadros P2P/1.0
                  /nick <novo_apelido>         Altera seu apelido na rede
                  /status                      Exibe informações técnicas deste nó Servent
                  /sair      (ou Ctrl+C)       /sair encerra graciosamente; Ctrl+C simula queda abrupta!""" + RESET);
    }

    // =========================================================================
    // Callbacks assíncronos vindos do motor de rede (P2PNode)
    // =========================================================================

    @Override
    public void onPeerDiscovered(PeerInfo peer) {
        asyncPrint(CYAN + String.format("[%s] [DESCOBERTA UDP] Nó detectado na LAN: %s (%s) em %s",
                now(), peer.nickname(), peer.nodeId(), peer.endpoint()) + RESET);
    }

    @Override
    public void onPeerConnected(PeerInfo peer, boolean inbound) {
        String dir = inbound ? "recebida de" : "estabelecida com";
        asyncPrint(GREEN + BOLD + String.format("[%s] [MALHA TCP] Conexão persistente %s %s (%s @ %s). Vizinhos ativos: %d",
                now(), dir, peer.nickname(), peer.nodeId(), peer.endpoint(), node.getActiveTcpConnectionCount()) + RESET);
    }

    @Override
    public void onPeerDisconnected(PeerInfo peer, String reason) {
        asyncPrint(RED + BOLD + String.format("[%s] [TOLERÂNCIA A FALHAS] Nó %s (%s @ %s) removido da tabela! Motivo: %s. Vizinhos restantes: %d",
                now(), peer.nickname(), peer.nodeId(), peer.endpoint(), reason, node.getActiveTcpConnectionCount()) + RESET);
    }

    @Override
    public void onGroupMessage(ProtocolMessage message) {
        asyncPrint(GREEN + String.format("[%s] [GRUPO] %s (%s): %s",
                message.formattedTime(), message.senderNick(), message.senderId(), message.payload()) + RESET);
    }

    @Override
    public void onPrivateMessage(ProtocolMessage message) {
        asyncPrint(MAGENTA + BOLD + String.format("[%s] [PRIVADO de %s (%s)]: %s",
                message.formattedTime(), message.senderNick(), message.senderId(), message.payload()) + RESET);
    }

    @Override
    public void onFileReceived(ProtocolMessage message, String fileName, Path savedPath, int sizeBytes) {
        asyncPrint(YELLOW + BOLD + String.format("[%s] [ARQUIVO RECEBIDO de %s]: '%s' (%d bytes) salvo em: %s",
                message.formattedTime(), message.senderNick(), fileName, sizeBytes, savedPath) + RESET);
    }

    @Override
    public void onSystemLog(String category, String details) {
        asyncPrint(GRAY + String.format("[%s] [%s] %s", now(), category, details) + RESET);
    }

    @Override
    public void onProtocolFrameTrace(String direction, String transport, String remoteEndpoint, String rawFrame) {
        if (traceFrames.get()) {
            asyncPrint(GRAY + String.format("  [TRACE %s/%s %s] %s", direction, transport, remoteEndpoint, rawFrame) + RESET);
        }
    }

    private synchronized void asyncPrint(String message) {
        System.out.print("\r\u001B[2K");
        System.out.println(message);
        printPrompt();
    }

    private synchronized void printlnColored(String color, String message) {
        System.out.println(color + message + RESET);
    }

    private synchronized void printPrompt() {
        if (node != null) {
            System.out.print(BOLD + "[" + node.getNickname() + ":" + node.getTcpPort() + "]> " + RESET);
            System.out.flush();
        }
    }

    private static String now() {
        return LocalTime.now().format(TIME_FMT);
    }
}
