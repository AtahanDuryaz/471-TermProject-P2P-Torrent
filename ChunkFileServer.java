import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

//simple server that sends a file in 256KB chunks to a single client with UDP

public class ChunkFileServer {

    private static final int CHUNK_SIZE = 256 * 1024; // 256 KB

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java ChunkFileServer <port> <filePath>");
            return;
        }

        int port = Integer.parseInt(args[0]);
        File file = new File(args[1]);

        if (!file.exists() || !file.isFile()) {
            System.out.println("File does not exist or is not a regular file: " + file.getAbsolutePath());
            return;
        }

        System.out.println("Starting ChunkFileServer on port " + port);
        System.out.println("Serving file: " + file.getAbsolutePath());
        System.out.println("File size   : " + file.length() + " bytes");

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Waiting for client connection...");

            try (Socket clientSocket = serverSocket.accept();
                 FileInputStream fis = new FileInputStream(file);
                 DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())) {

                System.out.println("Client connected from " +
                        clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort());

                //Send the file size first (8 bytes)
                long fileSize = file.length();
                out.writeLong(fileSize);
                out.flush();
                System.out.println("Sent file size: " + fileSize + " bytes");

                //Send file data in chunks
                byte[] buffer = new byte[CHUNK_SIZE];
                long totalSent = 0;
                int chunkIndex = 0;

                while (true) {
                    int bytesRead = fis.read(buffer);
                    if (bytesRead == -1) {
                        break; // EOF
                    }

                    out.write(buffer, 0, bytesRead);
                    out.flush();

                    totalSent += bytesRead;
                    System.out.printf("Sent chunk %d: %d bytes (total %d/%d)%n",
                            chunkIndex, bytesRead, totalSent, fileSize);
                    chunkIndex++;
                }

                System.out.println("File transfer completed. Total bytes sent: " + totalSent);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

