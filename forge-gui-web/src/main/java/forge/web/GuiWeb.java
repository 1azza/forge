package forge.web;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.jupnp.DefaultUpnpServiceConfiguration;
import org.jupnp.UpnpServiceConfiguration;

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.BuildInfo;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;

/**
 * Headless {@link IGuiBase} for the browser front end.
 *
 * <p>Everything the desktop client renders with Swing is either handled in the browser or
 * not needed at all, so the drawing, skinning and audio hooks are inert here. What this
 * class does still owns is the threading contract the rest of Forge relies on: the engine
 * assumes a single "GUI thread" that it can post work to and block on, so we run one
 * ({@link #EDT}) and answer {@link #isGuiThread()} against it exactly as the Swing port
 * answers against the EDT.
 */
public class GuiWeb implements IGuiBase {

    private static final String EDT_THREAD_NAME = "Forge Web GUI";

    private final ExecutorService edt = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(final Runnable r) {
            final Thread t = new Thread(r, EDT_THREAD_NAME);
            t.setDaemon(true);
            return t;
        }
    });

    private final ImageFetcher imageFetcher = new NoOpImageFetcher();

    /** Set once the session is created, so game GUIs can be handed out on request. */
    private volatile WebSession session;

    public void setSession(final WebSession session) {
        this.session = session;
    }

    @Override
    public boolean isRunningOnDesktop() {
        return true;
    }

    @Override
    public boolean isLibgdxPort() {
        return false;
    }

    @Override
    public String getCurrentVersion() {
        return BuildInfo.getVersionString();
    }

    // ------------------------------------------------------------- threading

    @Override
    public void invokeInEdtNow(final Runnable runnable) {
        runnable.run();
    }

    @Override
    public void invokeInEdtLater(final Runnable runnable) {
        edt.execute(guarded(runnable));
    }

    @Override
    public void invokeInEdtAndWait(final Runnable proc) {
        if (isGuiThread()) {
            proc.run();
            return;
        }
        try {
            edt.submit(guarded(proc)).get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    @Override
    public void runBackgroundTask(final String message, final Runnable task) {
        final Thread t = new Thread(guarded(task), "Forge Web background task");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public boolean isGuiThread() {
        return EDT_THREAD_NAME.equals(Thread.currentThread().getName());
    }

    /** Keeps one failing UI task from killing the single GUI thread for the whole session. */
    private static Runnable guarded(final Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (final RuntimeException | Error e) {
                System.err.println("Error on Forge Web GUI thread: " + e);
                e.printStackTrace();
            }
        };
    }

    // --------------------------------------------------------------- assets

    @Override
    public String getAssetsDir() {
        final String override = System.getProperty("forge.assets.dir");
        if (StringUtils.isNotBlank(override)) {
            return override.endsWith("/") ? override : override + "/";
        }
        // Same convention as the desktop client: a dev build runs out of the source tree
        // and needs to reach sideways into forge-gui, a packaged build sits next to res/.
        return StringUtils.containsIgnoreCase(BuildInfo.getVersionString(), "git") ? "../forge-gui/" : "";
    }

    @Override
    public ImageFetcher getImageFetcher() {
        return imageFetcher;
    }

    /**
     * The browser loads card art directly from {@code /api/card-image}, which falls back to
     * Scryfall on a cache miss, so the engine never needs to pull images down itself.
     */
    private static final class NoOpImageFetcher extends ImageFetcher {
        @Override
        protected Runnable getDownloadTask(final String[] downloadUrls, final String destPath, final Runnable notifyObservers) {
            return () -> { };
        }
    }

    @Override
    public ISkinImage getSkinIcon(final FSkinProp skinProp) {
        return null;
    }

    @Override
    public ISkinImage getUnskinnedIcon(final String path) {
        return null;
    }

    @Override
    public ISkinImage getCardArt(final PaperCard card, final boolean backFace) {
        return null;
    }

    @Override
    public ISkinImage createLayeredImage(final PaperCard card, final FSkinProp background, final String overlayFilename, final float opacity) {
        return null;
    }

    @Override
    public void clearImageCache() {
    }

    @Override
    public String encodeSymbols(final String str, final boolean formatReminderText) {
        // Mana symbols are rendered client side from the raw {W}{U} notation, so pass through.
        return str;
    }

    @Override
    public int getAvatarCount() {
        return 0;
    }

    @Override
    public int getSleevesCount() {
        return 0;
    }

    @Override
    public float getScreenScale() {
        return 1f;
    }

    @Override
    public void preventSystemSleep(final boolean preventSleep) {
    }

    @Override
    public void download(final GuiDownloadService service, final Consumer<Boolean> callback) {
        if (callback != null) {
            callback.accept(false);
        }
    }

    @Override
    public void copyToClipboard(final String text) {
    }

    @Override
    public void browseToUrl(final String url) throws IOException, URISyntaxException {
    }

    // --------------------------------------------------------------- dialogs
    // These are the out-of-game dialogs (deck editor, quest shop, bug reports). The web
    // front end drives its own screens for anything it supports, so the rest answer with
    // the least surprising default rather than blocking a thread on a UI that isn't there.

    @Override
    public void showCardList(final String title, final String message, final List<PaperCard> list) {
    }

    @Override
    public boolean showBoxedProduct(final String title, final String message, final List<PaperCard> list) {
        return false;
    }

    @Override
    public void showBugReportDialog(final String title, final String text, final boolean showExitAppBtn) {
        System.err.println(title + ": " + text);
    }

    @Override
    public void showImageDialog(final ISkinImage image, final String message, final String title) {
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon, final List<String> options, final int defaultOption) {
        final WebSession s = session;
        return s == null ? defaultOption : s.showOptionDialog(message, title, options, defaultOption);
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon, final String initialInput,
            final List<String> inputOptions, final boolean isNumeric) {
        final WebSession s = session;
        return s == null ? initialInput : s.showInputDialog(message, title, initialInput, inputOptions, isNumeric);
    }

    @Override
    public String showFileDialog(final String title, final String defaultDir) {
        return null;
    }

    @Override
    public File getSaveFile(final File defaultFile) {
        return defaultFile;
    }

    @Override
    public <T> List<T> order(final String title, final String top, final int remainingObjectsMin, final int remainingObjectsMax,
            final List<T> sourceChoices, final List<T> destChoices) {
        final List<T> result = new ArrayList<>();
        if (destChoices != null) {
            result.addAll(destChoices);
        }
        result.addAll(sourceChoices);
        return result;
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final Collection<T> choices,
            final Collection<T> selected, final FSerializableFunction<T, String> display) {
        final List<T> result = new ArrayList<>();
        for (final T choice : choices) {
            if (max >= 0 && result.size() >= max) {
                break;
            }
            result.add(choice);
        }
        return result;
    }

    @Override
    public PaperCard chooseCard(final String title, final String message, final List<PaperCard> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    // ----------------------------------------------------------------- audio

    @Override
    public boolean isSupportedAudioFormat(final File file) {
        return false;
    }

    @Override
    public IAudioClip createAudioClip(final String filename) {
        return null;
    }

    @Override
    public IAudioMusic createAudioMusic(final String filename) {
        return null;
    }

    @Override
    public void startAltSoundSystem(final String filename, final boolean isSynchronized) {
    }

    // ---------------------------------------------------------------- screens

    @Override
    public void showSpellShop() {
    }

    @Override
    public void showBazaar() {
    }

    @Override
    public IGuiGame getNewGuiGame() {
        final WebSession s = session;
        return s == null ? null : s.getGuiGame();
    }

    @Override
    public HostedMatch hostMatch() {
        return new HostedMatch();
    }

    @Override
    public UpnpServiceConfiguration getUpnpPlatformService() {
        return new DefaultUpnpServiceConfiguration();
    }

    @Override
    public boolean hasNetGame() {
        return false;
    }
}
