package com.multiforge.master.handlers;

import com.multiforge.master.ServerConnection;
import com.multiforge.master.util.MasterStorage;
import com.multiforge.protocol.messages.masterbound.RequestFilesToSyncMessage;
import com.multiforge.protocol.messages.serverbound.FilesToSyncMessage;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists every file the master stores for synchronization.
 *
 * <p>Used by a server after start-up to discover which files exist on the master
 * and how new they are, so it can download the ones it is missing or has an older
 * copy of. Paths are returned relative to the shared file root, which is what
 * {@code DownloadFileMessage} expects.</p>
 */
public class RequestFilesToSyncHandler {

    private RequestFilesToSyncHandler() {
    }

    /**
     * @param connection connection asking for the list
     * @param message    request
     */
    public static void handle(ServerConnection connection, RequestFilesToSyncMessage message) {
        File sharedRoot = MasterStorage.sharedRoot();
        List<File> files = new ArrayList<>();
        appendFilesRecursively(sharedRoot, files);

        Path rootPath = sharedRoot.toPath().toAbsolutePath().normalize();
        FilesToSyncMessage.FileToSync[] filesToSync = new FilesToSyncMessage.FileToSync[files.size()];
        for (int i = 0; i < filesToSync.length; i++) {
            File file = files.get(i);
            String relative = rootPath.relativize(file.toPath().toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
            filesToSync[i] = new FilesToSyncMessage.FileToSync(relative, file.lastModified());
        }
        connection.sendReply(new FilesToSyncMessage(filesToSync), message);
    }

    private static void appendFilesRecursively(File file, List<File> files) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    appendFilesRecursively(child, files);
                }
            }
        } else if (file.isFile()) {
            files.add(file);
        }
    }
}
