package akisei.karmacurrency;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Writes high-volume shrine generation diagnostics outside Minecraft's general log. */
final class StructureLog {
	private static final Path FILE = Path.of("logs", "shrines.log");
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private StructureLog() {
	}

	static synchronized void info(String message) {
		write("INFO", message);
	}

	static synchronized void warn(String message) {
		write("WARN", message);
	}

	private static void write(String level, String message) {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, "[" + TIMESTAMP.format(LocalDateTime.now()) + "] [" + level + "] " + message + System.lineSeparator(),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			// Generation diagnostics must never interfere with world generation.
		}
	}
}