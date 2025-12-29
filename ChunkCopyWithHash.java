import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


//Reads a file in 256KB chunks,
//writes those chunks to a new file,
//computes SHA-256 hashes of both files to ensure no problems

//java ChunkCopyWithHash inputFile outputFile

public class ChunkCopyWithHash {

    private static final int CHUNK_SIZE = 256 * 1024; // 256 KB

    public static void main(String[] args) {
        
        if (args.length != 2) {
            System.out.println("Usage: java ChunkCopyWithHash <inputFile> <outputFile>");
            return;
        }

        File inputFile = new File(args[0]);
        File outputFile = new File(args[1]);

        if (!inputFile.exists()) {
            System.out.println("Input file does not exist: " + inputFile.getAbsolutePath());
            return;
        }

        try {
            System.out.println("Input file : " + inputFile.getAbsolutePath());
            System.out.println("Output file: " + outputFile.getAbsolutePath());

            //Copy file chunk-by-chunk
            copyFileInChunks(inputFile, outputFile);

            //Compute hashes of original and reconstructed file
            String originalHash = computeSha256(inputFile.toPath());
            String copyHash = computeSha256(outputFile.toPath());

            System.out.println("\n     SHA-256 Hashes     ");
            System.out.println("Original file   : " + originalHash);
            System.out.println("Reconstructed   : " + copyHash);

            if (originalHash.equals(copyHash)) {
                System.out.println("RESULT: Hashes MATCH. Chunk copy is correct.");
            } else {
                System.out.println("RESULT: Hashes DO NOT MATCH. Something went wrong.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    
    //Reads the input file in CHUNK_SIZE pieces and writes them into outputFile
    //Simulate downloading chunks over the network and reassembling them
    
    private static void copyFileInChunks(File inputFile, File outputFile) throws IOException {
        long fileLength = inputFile.length();
        int totalChunks = (int) Math.ceil(fileLength / (double) CHUNK_SIZE);

        System.out.println("\nFile size      : " + fileLength + " bytes");
        System.out.println("Chunk size     : " + CHUNK_SIZE + " bytes");
        System.out.println("Total chunks   : " + totalChunks);

        try (RandomAccessFile in = new RandomAccessFile(inputFile, "r");
             RandomAccessFile out = new RandomAccessFile(outputFile, "rw")) {

            byte[] buffer = new byte[CHUNK_SIZE];
            int chunkIndex = 0;

            while (true) {
                int bytesRead = in.read(buffer);
                if (bytesRead == -1) {
                    break;
                }

                long writePosition = (long) chunkIndex * CHUNK_SIZE;
                out.seek(writePosition);
                out.write(buffer, 0, bytesRead);

                System.out.printf("Chunk %d written: %d bytes (position %d)%n",
                        chunkIndex, bytesRead, writePosition);

                chunkIndex++;
            }
        }

        System.out.println("Chunk copy completed.");
    }

    //hashing
    private static String computeSha256(Path path) throws IOException, NoSuchAlgorithmException {
        byte[] fileBytes = Files.readAllBytes(path);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(fileBytes);

        return java.util.HexFormat.of().formatHex(digest);
    }
}

