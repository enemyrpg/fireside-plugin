package com.fireside.icons;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Loads item icons. {@link ClientIconImages} is the real one; tests use a fake.
 */
public interface IconImages
{
	/**
	 * Starts loading the icon for {@code itemId} (as in the event, never
	 * canonicalized). {@code onLoaded} is called once, on any thread, when the
	 * image is ready, or never if the game cannot draw it. May be called from any
	 * thread and never blocks.
	 */
	void load(int itemId, Consumer<BufferedImage> onLoaded);
}
