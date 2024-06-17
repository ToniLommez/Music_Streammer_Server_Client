import javax.sound.sampled.*;
import java.util.*;
import java.io.*;
import java.net.*;

class UDPClient {
    private String serverName;
    private int port;
    private DatagramSocket clientSocket;
    private InetAddress IPAddress;
    private int bufferSize;
    private int timeout;

    public UDPClient(String serverName, int port, int bufferSize, int timeout) throws Exception {
        this.serverName = serverName;
        this.port = port;
        this.IPAddress = InetAddress.getByName(this.serverName);
        this.bufferSize = bufferSize;
        this.timeout = timeout;
    }

    public void connect() throws Exception {
        clientSocket = new DatagramSocket();
        System.out.println("UDP client socket created.");
    }

    public void sendMessage(String message) throws IOException {
        byte[] sendData = message.getBytes();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
        clientSocket.send(sendPacket);
    }

    public int receiveMessage(byte[] data[]) throws Exception {
        byte[] receiveData = new byte[bufferSize];
        clientSocket.setSoTimeout(timeout);
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
    
        clientSocket.receive(receivePacket);
        byte[] packetData = receivePacket.getData();
        int packetNumber = Integer.parseInt(new String(Arrays.copyOfRange(packetData, 0, 8)));
        byte[] receivedData = Arrays.copyOfRange(packetData, 8, receivePacket.getLength());
    
        // Redimensiona o array de dados
        data[0] = new byte[receivedData.length];
        System.arraycopy(receivedData, 0, data[0], 0, receivedData.length);
    
        return packetNumber;
    }

    public void close() {
        clientSocket.close();
        System.out.println("UDP client socket closed.");
    }
}

class TCPClient {
    private String serverName;
    private int port;
    private Socket clientSocket;
    private DataOutputStream outToServer;
    private BufferedReader inFromServer;

    public TCPClient(String serverName, int port) {
        this.serverName = serverName;
        this.port = port;
    }

    public void connect() throws IOException {
        clientSocket = new Socket(serverName, port);
        outToServer = new DataOutputStream(clientSocket.getOutputStream());
        inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        System.out.println("Connected to server.");
    }

    public void sendMessage(String message) throws IOException {
        outToServer.writeBytes(message + '\n');
    }

    public String receiveMessage() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        String line;
        while ((line = inFromServer.readLine()) != null)
            stringBuilder.append(line).append("\n");
        return stringBuilder.toString();
    }

    public void close() throws IOException {
        inFromServer.close();
        outToServer.close();
        clientSocket.close();
        System.out.println("Connection closed.");
    }
}

class MusicClient {
    private static final String SERVER_ADDRESS = "localhost";
    private static final int BUFFER_SIZE = 4096;
    private static final int TCP_PORT = 5050;
    private static final int UDP_PORT = 5051;
    private static final int TIMEOUT = 1000;

    private TCPClient tcpClient;
    private UDPClient udpClient;

    SourceDataLine line;

    volatile boolean ended;
    private String musicName;
    private boolean canPlay;
    private int packets;
    private double downloaded;

    public MusicClient() throws Exception {
        this.tcpClient = new TCPClient(SERVER_ADDRESS, TCP_PORT);
        this.udpClient = new UDPClient(SERVER_ADDRESS, UDP_PORT, BUFFER_SIZE + 8, TIMEOUT);
        this.canPlay = false;
        this.musicName = "";
        this.packets = 0;
        this.ended = false;
    }

    public void start() throws Exception {
        tcpClient.connect();
        udpClient.connect();

        AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
        line = AudioSystem.getSourceDataLine(format);
        line.open(format);
    }

    // TODO: BOTAO DE PESQUISA -- Adicione esta para escolher a musica no frontend
    public void chooseMusic() throws Exception {
        String musicName = System.console().readLine("Music name: ");
        tcpClient.sendMessage(musicName);

        String[] serverResponse = tcpClient.receiveMessage().split("\n");
        String response = serverResponse[0].split(": ")[1];

        if (response.equals("music ready")) {
            this.musicName = musicName;
            canPlay = true;
            packets = Integer.parseInt(serverResponse[2].split(": ")[1]);
        }
    }

    // TODO: BOTAO DE VOLUME -- Permita que o frontend controle o volume, use porcentagem
    public void changeVolume(double volume) {
        FloatControl volumeControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
        float vol = (float) (Math.log(volume) / Math.log(10.0) * 20.0);
        volumeControl.setValue(vol);
    }

    // TODO: BOTAO DE PLAY -- Basta chamar esta funcao que a musica escolhida em chooseMusic começara a tocar
    public void playMusic() throws Exception {
        if (!canPlay) {
            System.out.println("Please, insert a valid music");
            return;
        }

        System.console().readLine("Press start to play");

        // Armazenamento para pacotes recebidos
        byte[][] musicBuffer = new byte[packets][];
        boolean[] receivedPackets = new boolean[packets];
        boolean[] requestStackController = new boolean[packets];
        Stack<Integer> requestStack = new Stack<>();

        // Popula a fila de requisições
        for (int i = packets - 1; i >= 0; i--) {
            requestStack.add(i);
            requestStackController[i] = true;
        }

        // Iniciar audio
        line.start();

        // Thread para disparar pedidos
        Thread requestThread = new Thread(() -> {
            try {
                while (!ended) {
                    while (!requestStack.isEmpty()) {
                        int packetNumber = requestStack.pop();
                        requestStackController[packetNumber] = false;

                        if (!receivedPackets[packetNumber]) {
                            udpClient.sendMessage(this.musicName + ":" + packetNumber);
                        }
                        try {
                            Thread.sleep(3);
                        } catch (Exception e) {
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // Thread para receber e reconstruir o arquivo
        Thread receiveThread = new Thread(() -> {
            double percentPerPackage = 100.0 / packets;
            while (!ended) {
                try {
                    byte[] data[] = new byte[1][]; // isso é gambiarra pra simular passagem por referencia
                    int packetNumber = udpClient.receiveMessage(data);

                    if (!receivedPackets[packetNumber]) {
                        musicBuffer[packetNumber] = data[0];
                        receivedPackets[packetNumber] = true;
                        downloaded += percentPerPackage;
                    }
                } catch (Exception e) {}
            }
            downloaded = 100;
        });

        // Thread para adicionar ao player de música
        Thread playThread = new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
            }

            int expectedPacket = 0;
            while (expectedPacket < packets) {
                if (receivedPackets[expectedPacket]) {
                    line.write(musicBuffer[expectedPacket], 0, BUFFER_SIZE);
                    expectedPacket++;
                }
            }
        });

        // Thread para analisar pacotes perdidos
        Thread retrieveThread = new Thread(() -> {
            int expectedPacket = 0;
            while (expectedPacket < packets) {
                if (receivedPackets[expectedPacket]) {
                    expectedPacket++;
                    continue;
                }
                try {
                    Thread.sleep(10);
                } catch (Exception e) {
                }

                for (int i = expectedPacket; i < expectedPacket+500 && i < receivedPackets.length; i++) {
                    if (!receivedPackets[i] && !requestStackController[i]) {
                        requestStack.add(i);
                        requestStackController[i] = true;
                    }
                }
            }

            ended = true;
        });

        // Thread para printar a porcentagem de download no momento
        Thread logThread = new Thread(() -> {
            while (!ended) {
                try {
                    Thread.sleep(500);
                } catch (Exception e) {
                }

                logDownloaded(downloaded);
            }
            System.out.printf("\rDownloaded = 100%%            \n");
            ended = true;
        });

        // Disparar threads
        requestThread.start();
        receiveThread.start();
        playThread.start();
        retrieveThread.start();
        logThread.start();

        requestThread.join();
        receiveThread.join();
        playThread.join(); // Esta so termina quando a musica acaba de tocar
        retrieveThread.join();
        logThread.join();

        // Fechar audio
        line.drain();
        line.close();
    }

    // TODO: LOG DE DOWNLOAD -- Use isto para mostrar no frontend a porcentagem atual de download
    public void logDownloaded(double percent) {
        System.out.printf("\rDownloaded = %.1f%%", downloaded);
    }

    public void close() throws Exception {
        tcpClient.close();
    }
}

public class Client {
    // TODO: aqui tem a sequencia necessaria de ações para rodar
    public static void main(String[] args) throws Exception {
        MusicClient client = new MusicClient();
        client.start();
        client.chooseMusic();
        client.changeVolume(0.1);
        client.playMusic();

        System.console().readLine("Press ENTER to exit");
        client.close();
    }
}
