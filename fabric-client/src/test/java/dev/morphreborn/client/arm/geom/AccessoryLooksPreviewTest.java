package dev.morphreborn.client.arm.geom;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Writes the accessory-on-arm pictures when {@code ACCESSORY_LOOKS_DIR} names a folder; otherwise returns at once. */
class AccessoryLooksPreviewTest {
	@Test
	void writePreviewPictures() throws IOException {
		String dir = System.getenv("ACCESSORY_LOOKS_DIR");
		if (dir == null || dir.isEmpty()) return;
		AccessoryLooksPreview.write(Path.of(dir));
	}

	/** The lantern on First-person Model's body: pictures when {@code LANTERN_FPM_DIR} names a folder. */
	@Test
	void writeLanternOnBodyPictures() throws IOException {
		String dir = System.getenv("LANTERN_FPM_DIR");
		if (dir == null || dir.isEmpty()) return;
		AccessoryLooksPreview.writeLanternOnBody(Path.of(dir));
	}
}
