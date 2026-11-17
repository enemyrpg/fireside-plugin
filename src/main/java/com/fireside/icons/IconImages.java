package com.fireside.icons;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Loads item icons and game sprites. {@link ClientIconImages} is the real one; tests use a fake.
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

	/**
	 * Starts loading a game sprite (the icon of a skill or boss, 10.12).
	 * {@code onLoaded} is called once, on any thread, with the sprite as the
	 * game has it (trimmed, so often narrower than its canvas), or never if the
	 * game has no such sprite. May be called from any thread and never blocks.
	 */
	void loadSprite(int spriteId, Consumer<BufferedImage> onLoaded);
}
