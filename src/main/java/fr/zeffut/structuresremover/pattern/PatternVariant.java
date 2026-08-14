package fr.zeffut.structuresremover.pattern;

import net.minecraft.block.BlockState;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;

import java.util.Arrays;

/**
 * One orientation of a {@link StructurePattern}, ready to be matched against the world.
 *
 * <p>{@code anchor*} is the cell the scan keys on: when the world holds {@link #anchorState()} at
 * some position {@code p}, the candidate copy starts at {@code p - (anchorX, anchorY, anchorZ)}.
 */
public record PatternVariant(
		int sizeX,
		int sizeY,
		int sizeZ,
		BlockState[] states,
		int[] agreement,
		int requiredAgreement,
		BlockRotation rotation,
		BlockMirror mirror,
		int anchorX,
		int anchorY,
		int anchorZ,
		BlockState anchorState,
		int solidCount,
		int requiredCount
) {
	public BlockState stateAt(int x, int y, int z) {
		return this.states[(y * this.sizeZ + z) * this.sizeX + x];
	}

	/** Whether this cell has to match, as opposed to only being cleared on removal. */
	public boolean isRequired(int x, int y, int z) {
		// Air can be required too; matchAir decides whether an empty cell is held against the world.
		return this.agreement[(y * this.sizeZ + z) * this.sizeX + x] >= this.requiredAgreement;
	}

	/** Whether removal clears this cell: carried by enough examples not to be just the ground. */
	public boolean isInFootprint(int x, int y, int z) {
		int i = (y * this.sizeZ + z) * this.sizeX + x;
		return !this.states[i].isAir() && this.agreement[i] >= footprintThreshold(this.requiredAgreement);
	}

	/** Removal reaches a little wider than matching, but never on the word of a single example. */
	private static int footprintThreshold(int requiredAgreement) {
		if (requiredAgreement <= 1) {
			return 1;
		}

		return Math.max(2, (requiredAgreement * 2) / 3);
	}

	/** Number of cells removal would clear. */
	public int footprintCount() {
		int count = 0;

		int threshold = footprintThreshold(this.requiredAgreement);

		for (int i = 0; i < this.states.length; i++) {
			if (!this.states[i].isAir() && this.agreement[i] >= threshold) {
				count++;
			}
		}

		return count;
	}

	public int agreementAt(int x, int y, int z) {
		return this.agreement[(y * this.sizeZ + z) * this.sizeX + x];
	}

	public boolean hasSameContent(PatternVariant other) {
		return this.sizeX == other.sizeX
				&& this.sizeY == other.sizeY
				&& this.sizeZ == other.sizeZ
				&& Arrays.equals(this.states, other.states);
	}

	public String describeOrientation() {
		String rotationName = switch (this.rotation) {
			case CLOCKWISE_90 -> "90°";
			case CLOCKWISE_180 -> "180°";
			case COUNTERCLOCKWISE_90 -> "270°";
			default -> "0°";
		};

		return this.mirror == BlockMirror.NONE ? rotationName : rotationName + " mirrored";
	}
}
