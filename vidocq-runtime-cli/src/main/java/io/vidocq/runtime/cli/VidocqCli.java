package io.vidocq.runtime.cli;

/**
 * Entry point for the Vidocq command-line interface.
 *
 * <pre>{@code
 * java -m io.vidocq.runtime.cli/io.vidocq.runtime.cli.VidocqCli <command> [options]
 * }</pre>
 */
public final class VidocqCli {

    public static final String VERSION = "0.2.0-SNAPSHOT";

    private VidocqCli() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            CliParser.printHelp(null);
            System.exit(0);
        }
        try {
            Command cmd = CliParser.parse(args);
            System.exit(CommandRunner.run(cmd));
        } catch (CliException e) {
            CliOutput.error(e.getMessage());
            System.exit(1);
        }
    }
}
