package com.springify.cli;

import com.springify.cli.command.GenerateCommand;
import picocli.CommandLine;

public final class HexagenCliApplication {

    private HexagenCliApplication() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GenerateCommand()).execute(args);
        System.exit(exitCode);
    }
}
