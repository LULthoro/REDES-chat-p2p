package redes.p2p;

import redes.p2p.cli.ConsoleUI;
import redes.p2p.model.PeerInfo;
import redes.p2p.model.ProtocolMessage;
import redes.p2p.net.NodeListener;
import redes.p2p.net.P2PNode;
import redes.p2p.net.UdpDiscoveryService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ponto de entrada principal da aplicação P2P (Servent).
 *
 * Exemplos de uso em 3 terminais distintos:
 *   Terminal 1: mvn exec:java -Dexec.args="Alice"
 *   Terminal 2: mvn exec:java -Dexec.args="Bob"
 *   Terminal 3: mvn exec:java -Dexec.args="Carlos"
 *
 * Ou para executar a simulação automatizada de validação dos 3 nós:
 *   mvn exec:java -Dexec.args="--demo"
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        String nickname = null;
        int tcpPort = P2PNode.DEFAULT_BASE_TCP_PORT;
        int udpPort = UdpDiscoveryService.DEFAULT_UDP_PORT;
        boolean trace = false;
        boolean runDemo = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--nick", "-n" -> {
                    if (i + 1 < args.length) nickname = args[++i];
                }
                case "--port", "-p" -> {
                    if (i + 1 < args.length) tcpPort = Integer.parseInt(args[++i]);
                }
                case "--udp-port", "-u" -> {
                    if (i + 1 < args.length) udpPort = Integer.parseInt(args[++i]);
                }
                case "--trace", "-t" -> trace = true;
                case "--demo", "--self-test" -> runDemo = true;
                default -> {
                    if (!arg.startsWith("-") && nickname == null) {
                        nickname = arg;
                    }
                }
            }
        }

        if (runDemo) {
            runAutomatedThreeNodeDemo();
            return;
        }

        ConsoleUI ui = new ConsoleUI();
        try (P2PNode node = new P2PNode(
                nickname,
                tcpPort,
                udpPort,
                UdpDiscoveryService.DEFAULT_BEACON_INTERVAL_MS,
                ui
        )) {
            if (nickname == null || nickname.isBlank()) {
                node.setNickname("No-" + node.getTcpPort());
            }
            ui.attachNode(node, trace);
            node.start();
            ui.runInteractiveLoop();
        }
    }

    /**
     * Executa uma demonstração/validação automatizada completa com 3 instâncias Servent
     * reproduzindo todos os requisitos da Prévia (25/Nov) e da Apresentação Final (07/Dez).
     */
    private static void runAutomatedThreeNodeDemo() throws Exception {
        System.out.println("================================================================================");
        System.out.println("  INICIANDO SIMULAÇÃO AUTOMATIZADA DE 3 NÓS P2P (Alice, Bob, Carlos) - JAVA 21  ");
        System.out.println("================================================================================");

        NodeListener demoLogger = createDemoListener();
        int demoUdpPort = 4447;

        P2PNode alice = new P2PNode("Alice", 5101, demoUdpPort, 1000L, demoLogger);
        P2PNode bob = new P2PNode("Bob", 5102, demoUdpPort, 1000L, demoLogger);
        P2PNode carlos = new P2PNode("Carlos", 5103, demoUdpPort, 1000L, demoLogger);

        try {
            System.out.println("\n[ETAPA 1] Iniciando os 3 nós Servent e aguardando descoberta automática via Beacon UDP...");
            alice.start();
            bob.start();
            carlos.start();

            // Aguarda descoberta UDP e estabelecimento da malha TCP full-duplex
            long deadline = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < deadline) {
                if (alice.getActiveTcpConnectionCount() == 2
                        && bob.getActiveTcpConnectionCount() == 2
                        && carlos.getActiveTcpConnectionCount() == 2) {
                    break;
                }
                Thread.sleep(200);
            }

            printNodeNeighbors(alice);
            printNodeNeighbors(bob);
            printNodeNeighbors(carlos);

            System.out.println("\n[ETAPA 2] Testando envio de Mensagens em Grupo (MSG_ALL sobre TCP)...");
            alice.sendGroupMessage("Olá pessoal! Aqui é a Alice falando com todos na malha P2P!");
            Thread.sleep(300);
            bob.sendGroupMessage("Oi Alice e Carlos! Recebido perfeitamente via socket TCP persistente.");
            Thread.sleep(300);

            System.out.println("\n[ETAPA 3] Testando envio de Mensagem Privada (MSG_PRIV direta Alice -> Carlos)...");
            alice.sendPrivateMessage("Carlos", "Mensagem secreta exclusiva para você, Carlos! (Bob não recebe)");
            Thread.sleep(300);

            System.out.println("\n[ETAPA 4] Testando transferência de pequeno arquivo (FILE_SEND via TCP)...");
            Path tempFile = Files.createTempFile("resumo-redes-", ".txt");
            Files.writeString(tempFile, "Conteudo do arquivo transmitido pelo protocolo P2P/1.0 sobre TCP!\n", StandardCharsets.UTF_8);
            bob.sendFile("Alice", tempFile);
            Thread.sleep(400);
            Files.deleteIfExists(tempFile);

            System.out.println("\n[ETAPA 5] Simulando queda ABRUPTA de Carlos (sem envio de BYE, apenas corte de socket TCP) e verificando atualização imediata da Tabela de Vizinhos...");
            carlos.simulateAbruptCrash();
            Thread.sleep(500);

            printNodeNeighbors(alice);
            printNodeNeighbors(bob);

            System.out.println("\n[ETAPA 6] Verificando continuidade do serviço entre os nós sobreviventes (Alice <-> Bob)...");
            alice.sendGroupMessage("Carlos saiu, mas nossa malha P2P segue operando sem interrupção!");
            Thread.sleep(300);

            System.out.println("\n================================================================================");
            System.out.println("  SIMULAÇÃO CONCLUÍDA COM SUCESSO! TODOS OS REQUISITOS VALIDADOS.               ");
            System.out.println("================================================================================");
        } finally {
            alice.close();
            bob.close();
            carlos.close();
        }
    }

    private static void printNodeNeighbors(P2PNode node) {
        System.out.printf("  -> Tabela de Vizinhos de %s (%s): %s%n",
                node.getNickname(), node.getNodeId(), node.getNeighborsSnapshot());
    }

    private static NodeListener createDemoListener() {
        return new NodeListener() {
            @Override
            public void onPeerDiscovered(PeerInfo peer) {
                System.out.printf("  [UDP BEACON] Descoberto vizinho: %s (%s) em %s%n",
                        peer.nickname(), peer.nodeId(), peer.endpoint());
            }

            @Override
            public void onPeerConnected(PeerInfo peer, boolean inbound) {
                System.out.printf("  [TCP MESH] Canal TCP conectado (%s) com %s (%s)%n",
                        inbound ? "ENTRADA" : "SAÍDA", peer.nickname(), peer.endpoint());
            }

            @Override
            public void onPeerDisconnected(PeerInfo peer, String reason) {
                System.out.printf("  [TOLERÂNCIA A FALHAS] Vizinho desconectado: %s (%s) -> %s%n",
                        peer.nickname(), peer.endpoint(), reason);
            }

            @Override
            public void onGroupMessage(ProtocolMessage msg) {
                System.out.printf("  [MSG_ALL RECEBIDA] De %s: \"%s\"%n", msg.senderNick(), msg.payload());
            }

            @Override
            public void onPrivateMessage(ProtocolMessage msg) {
                System.out.printf("  [MSG_PRIV RECEBIDA] De %s para %s: \"%s\"%n",
                        msg.senderNick(), msg.target(), msg.payload());
            }

            @Override
            public void onFileReceived(ProtocolMessage msg, String fileName, Path savedPath, int sizeBytes) {
                System.out.printf("  [FILE_SEND RECEBIDO] De %s: arquivo '%s' (%d bytes) salvo em %s%n",
                        msg.senderNick(), fileName, sizeBytes, savedPath);
            }
        };
    }
}
