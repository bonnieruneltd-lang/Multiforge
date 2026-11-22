package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.ExtendedByteBuf;

public class FilesToSyncMessage extends ServerBoundMessage {

    /** Upper bound for entries in one list; a world never has millions of files. */
    public static final int MAX_FILES = 1 << 20;

    public final FileToSync[] filesToSync;

    public FilesToSyncMessage(FileToSync[] filesToSync) {
        this.filesToSync = filesToSync;
    }

    public FilesToSyncMessage(ExtendedByteBuf byteBuf) {
        int count = byteBuf.readVarInt();
        // Every entry needs at least one byte for the string length and eight for
        // the timestamp. A hostile frame could otherwise announce two billion
        // entries in a 20 byte frame and take the process down with an
        // OutOfMemoryError before reading a single entry.
        if (count < 0 || count > MAX_FILES || count > byteBuf.readableBytes() / 9) {
            throw new IllegalArgumentException("Files to sync count " + count
                    + " does not fit in " + byteBuf.readableBytes() + " readable bytes (limit "
                    + MAX_FILES + ")");
        }
        filesToSync = new FileToSync[count];
        for (int i = 0; i < filesToSync.length; i ++) {
            filesToSync[i] = new FileToSync(
                    byteBuf.readString(),
                    byteBuf.readLong()
            );
        }
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeVarInt(filesToSync.length);
        for (FileToSync fileToSync : filesToSync) {
            byteBuf.writeString(fileToSync.getPath());
            byteBuf.writeLong(fileToSync.getLastModified());
        }
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        throw new UnsupportedOperationException("This message can only be handled in a reply");
    }

    public record FileToSync(String path, long lastModified) {
        public String getPath() {
            return path;
        }

        public long getLastModified() {
            return lastModified;
        }
    }
}
