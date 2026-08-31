package forge.web;

import forge.gui.GuiBase;
import forge.model.FModel;
import forge.web.server.WebServer;

/**
 * Runs Forge's engine with a browser front end instead of a desktop window.
 *
 * <pre>
 *   java -jar forge-gui-web.jar [port]
 * </pre>
 *
 * <p>System properties: {@code forge.web.port}, {@code forge.assets.dir} (where {@code res/}
 * lives) and {@code forge.web.static} (serve the client from disk rather than the jar).
 */
public final class Main {

    private static final int DEFAULT_PORT = 7860;

    private Main() { }

    public static void main(final String[] args) throws Exception {
        final int port = resolvePort(args);

        final GuiWeb gui = new GuiWeb();
        GuiBase.setInterface(gui);

        System.out.println("Loading card database...");
        final long start = System.currentTimeMillis();
        FModel.initialize(null, null);
        System.out.printf("Card database ready in %.1fs%n", (System.currentTimeMillis() - start) / 1000.0);

        final LanRoomManager manager = new LanRoomManager();
        // Out-of-game dialogs (deck editor etc.) still go through the solo session.
        gui.setSession(manager.soloSession());

        final WebServer server = new WebServer(port, manager);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            manager.shutdown();
            server.stop();
        }));

        System.out.println("Forge is running at http://localhost:" + port);
        server.awaitShutdown();
    }

    private static int resolvePort(final String[] args) {
        if (args.length > 0) {
            try {
                return Integer.parseInt(args[0]);
            } catch (final NumberFormatException e) {
                System.err.println("Ignoring unparseable port '" + args[0] + "'");
            }
        }
        final String property = System.getProperty("forge.web.port");
        if (property != null) {
            try {
                return Integer.parseInt(property.trim());
            } catch (final NumberFormatException e) {
                System.err.println("Ignoring unparseable forge.web.port '" + property + "'");
            }
        }
        return DEFAULT_PORT;
    }
}
