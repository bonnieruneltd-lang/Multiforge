package com.multiforge.master;

import com.multiforge.master.util.Log;

import java.util.Scanner;

public class CommandLineInput {

    public boolean run() {
        Scanner scanner = new Scanner(System.in);

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();

            if (line.equalsIgnoreCase("shutdown")) {
                Log.info("Shutting down servers...");
                ServerConnection.shutdownAndWait();
                Log.info("Shutdown complete");
                return true;
            } else if (line.equalsIgnoreCase("exit")) {
                Log.info("Exiting...");
                return true;
            } else if (line.equalsIgnoreCase("threaddump")) {
                Log.info("Printing thread dump...");
                for (Thread thread : Thread.getAllStackTraces().keySet()) {
                    Log.info("");
                    Log.info(thread.getName() + " " + thread.getState());
                    for (StackTraceElement element : thread.getStackTrace()) {
                        Log.info("    " + element);
                    }
                }
            } else {
                Log.info("Unknown command, use 'shutdown' to shutdown all servers or 'exit' to stop just this master server");
            }
        }

        // No command line detected
        return false;
    }

}
