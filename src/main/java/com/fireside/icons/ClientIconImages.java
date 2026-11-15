package com.fireside.icons;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * {@link IconImages} from RuneLite's {@link ItemManager}, the same image as in
 * the game.
 * <p>
 * {@code getImage} may be called from any thread: it is a thread-safe cache, and
 * the sprite is drawn on the client thread by the item manager itself (RuneLite's
 * Loot Tracker calls it from the Swing thread). The image starts blank and is
 * filled in later, so we wait for {@code onLoaded}, which runs on the client
 * thread; the caller hands the work straight to its own thread.
 * <p>
 * {@code getImage(itemId)} means quantity 1 without a quantity number. For
 * stack-dependent items such as coins that is the single-item sprite, which is
 * the only consistent choice: the server keeps one icon per item ID.
 */
@Singleton
public class ClientIconImages implements IconImages
{
	private final ItemManager itemManager;

	@Inject
	ClientIconImages(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	@Override
	public void load(int itemId, Consumer<BufferedImage> onLoaded)
	{
		AsyncBufferedImage image = itemManager.getImage(itemId);
		if (image != null)
		{
			image.onLoaded(() -> onLoaded.accept(image));
		}
	}
}
