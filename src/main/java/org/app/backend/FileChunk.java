package org.app.backend;

public class FileChunk {
    private final String fileName;
    private final long startPosition;
    private final int chunkSize;
    private final long totalFileSize;
    private final int chunkIndex;
    private final int totalChunks;

    public FileChunk(String fileName, long startPosition, int chunkSize, long totalFileSize, int chunkIndex, int totalChunks) {
        this.fileName = fileName;
        this.startPosition = startPosition;
        this.chunkSize = chunkSize;
        this.totalFileSize = totalFileSize;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
    }

    public String getFileName() { return fileName; }
    public long getStartPosition() { return startPosition; }
    public int getChunkSize() { return chunkSize; }
    public long getTotalFileSize() { return totalFileSize; }
    public int getChunkIndex() { return chunkIndex; }
    public int getTotalChunks() { return totalChunks; }
} 