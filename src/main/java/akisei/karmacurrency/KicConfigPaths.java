package akisei.karmacurrency;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
public final class KicConfigPaths {
    private KicConfigPaths() {}
    public static Path file(String name) {
        Path old = Path.of("config", name), target = Path.of("config", "kic", name);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(old)) {
                if (Files.exists(target)) KarmaIsCurrency.LOGGER.warn("Both {} and {} exist; using {} and preserving the old file.", old, target, target);
                else Files.move(old, target);
            }
        } catch (IOException e) { throw new IllegalStateException("Cannot migrate KIC config " + name, e); }
        return target;
    }
}
