package com.fireside.icons;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
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
 * <p>
 * Sprites (skills and bosses, 10.12) come from RuneLite's {@link SpriteManager},
 * read on the client thread. Not {@code getSpriteAsync}: it tries again every
 * frame for as long as the sprite is missing, which would never end for a
 * sprite the game does not have. Here a sprite that is not loaded yet is tried
 * a limited number of times and then given up (the uploader's timeout skips it).
 */
@Singleton
public class ClientIconImages implements IconImages
{
	/** Client frames one sprite is tried for (about two seconds). */
	static final int SPRITE_ATTEMPTS = 100;

	private final ItemManager itemManager;
	private final SpriteManager spriteManager;
	private final ClientThread clientThread;

	@Inject
	ClientIconImages(ItemManager itemManager, SpriteManager spriteManager, ClientThread clientThread)
	{
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		this.clientThread = clientThread;
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

	@Override
	public void loadSprite(int spriteId, Consumer<BufferedImage> onLoaded)
	{
		int[] attempts = {0};
		// Returning false runs it again on the next client frame.
		clientThread.invoke(() ->
		{
			BufferedImage image;
			try
			{
				image = spriteManager.getSprite(spriteId, 0);
			}
			catch (RuntimeException e)
			{
				return true;
			}
			if (image == null)
			{
				return ++attempts[0] >= SPRITE_ATTEMPTS;
			}
			onLoaded.accept(image);
			return true;
		});
	}
}
