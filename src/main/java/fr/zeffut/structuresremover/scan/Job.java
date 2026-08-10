package fr.zeffut.structuresremover.scan;

import net.minecraft.text.Text;

import java.util.UUID;

/**
 * A long-running world operation spread over server ticks so the server never stalls.
 */
public interface Job {
	/**
	 * Performs one tick's worth of work.
	 *
	 * @return true when the job is finished and should be dropped
	 */
	boolean tick();

	/** Asks the job to stop at the next tick. */
	void cancel();

	UUID owner();

	/** Short one-line status shown by {@code /sr status}. */
	Text describe();
}
