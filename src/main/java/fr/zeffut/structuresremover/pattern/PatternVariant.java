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
		BlockRotation rotation,
		BlockMirror mirror,
		int anchorX,
		int anchorY,
		int anchorZ,
		BlockState anchorState,
		int solidCount
) {
	public BlockState stateAt(int x, int y, int z) {
		return this.states[(y * this.sizeZ + z) * this.sizeX + x];
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
