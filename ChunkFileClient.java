import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;

//simple client that connects to ChunkFileServer and downloads a file in 256KB chunks
//java ChunkFileClient 192.168.1.10 5001 video.mp4

public class ChunkFileClient {

    private static final int CHUNK_SIZE = 256 * 1024; // 256 KB

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("Usage: java ChunkFileClient <serverHost> <port> <outputFilePath>");
            return;
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        File outputFile = new File(args[2]);

        System.out.println("Connecting to server " + host + ":" + port);
        System.out.println("Output file will be: " + outputFile.getAbsolutePath());

        try (Socket socket = new Socket(host, port);
             DataInputStream in = new DataInputStream(socket.getInputStream());
             FileOutputStream fos = new FileOutputStream(outputFile)) {

            System.out.println("Connected to server.");

            //Read file size
            long fileSize = in.readLong();
            System.out.println("Server reports file size: " + fileSize + " bytes");

            //Receive file data in chunks
            byte[] buffer = new byte[CHUNK_SIZE];
            long totalReceived = 0;
            int chunkIndex = 0;

            while (totalReceived < fileSize) {
                int bytesToRead = (int) Math.min(CHUNK_SIZE, fileSize - totalReceived);
                int bytesRead = in.read(buffer, 0, bytesToRead);

                if (bytesRead == -1) {
                    System.out.println("Reached end of stream unexpectedly.");
                    break;
                }

                fos.write(buffer, 0, bytesRead);
                totalReceived += bytesRead;

                double percent = (totalReceived * 100.0) / fileSize;
                System.out.printf("Received chunk %d: %d bytes (total %d/%d, %.2f%%)%n",
                        chunkIndex, bytesRead, totalReceived, fileSize, percent);
                chunkIndex++;
            }

            fos.flush();

            System.out.println("Download completed. Total bytes received: " + totalReceived);

            if (totalReceived == fileSize) {
                System.out.println("SUCCESS: All bytes received.");
            } else {
                System.out.println("WARNING: Received size does not match expected file size.");
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

