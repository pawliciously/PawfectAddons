package dev.pawfect.addons.features.dungeon.rooms;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

import it.unimi.dsi.fastutil.ints.IntSortedSet;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import org.joml.RoundingMode;
import org.joml.Vector2d;
import org.joml.Vector2dc;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;

import dev.pawfect.addons.features.dungeon.rooms.Room.Type;

public class DungeonMapUtils {
	public static final byte BLACK_COLOR = MapColor.COLOR_BLACK.getPackedId(MapColor.Brightness.LOWEST);
	public static final byte RED_COLOR = 18;
	public static final byte WHITE_COLOR = 34;
	public static final byte GREEN_COLOR = 30;

	public static byte getColor(MapItemSavedData map, @Nullable Vector2ic pos) {
		return pos == null ? -1 : getColor(map, pos.x(), pos.y());
	}

	public static byte getColor(MapItemSavedData map, int x, int z) {
		if (x < 0 || z < 0 || x >= 128 || z >= 128) {
			return -1;
		}
		return map.colors[x + (z << 7)];
	}

	public static boolean isEntranceColor(MapItemSavedData map, int x, int z) {
		return getColor(map, x, z) == Type.ENTRANCE.color;
	}

	public static boolean isEntranceColor(MapItemSavedData map, @Nullable Vector2ic pos) {
		return getColor(map, pos) == Type.ENTRANCE.color;
	}

	private static @Nullable Vector2i getMapPlayerPos(MapItemSavedData map) {
		for (MapDecoration decoration : map.getDecorations()) {
			if (decoration.type().value().equals(MapDecorationTypes.FRAME.value())) {
				return new Vector2i((decoration.x() >> 1) + 64, (decoration.y() >> 1) + 64);
			}
		}
		return null;
	}

	public static @Nullable ObjectIntPair<Vector2ic> getMapEntrancePosAndRoomSize(MapItemSavedData map) {
		Vector2ic mapPos = getMapPlayerPos(map);
		if (mapPos == null) {
			return null;
		}
		Queue<Vector2ic> posToCheck = new ArrayDeque<>();
		Set<Vector2ic> checked = new HashSet<>();
		posToCheck.add(mapPos);
		checked.add(mapPos);
		while ((mapPos = posToCheck.poll()) != null) {
			if (isEntranceColor(map, mapPos)) {
				ObjectIntPair<Vector2ic> mapEntranceAndRoomSizePos = getMapEntrancePosAndRoomSizeAt(map, mapPos);
				if (mapEntranceAndRoomSizePos.rightInt() > 0) {
					return mapEntranceAndRoomSizePos;
				}
			}
			Vector2ic pos = new Vector2i(mapPos).sub(10, 0);
			if (checked.add(pos)) {
				posToCheck.add(pos);
			}
			pos = new Vector2i(mapPos).sub(0, 10);
			if (checked.add(pos)) {
				posToCheck.add(pos);
			}
			pos = new Vector2i(mapPos).add(10, 0);
			if (checked.add(pos)) {
				posToCheck.add(pos);
			}
			pos = new Vector2i(mapPos).add(0, 10);
			if (checked.add(pos)) {
				posToCheck.add(pos);
			}
		}
		return null;
	}

	private static ObjectIntPair<Vector2ic> getMapEntrancePosAndRoomSizeAt(MapItemSavedData map, Vector2ic mapPosImmutable) {
		Vector2i mapPos = new Vector2i(mapPosImmutable);
		while (isEntranceColor(map, mapPos.sub(1, 0))) {
		}
		mapPos.add(1, 0);
		while (isEntranceColor(map, mapPos.sub(0, 1))) {
		}
		return ObjectIntPair.of(mapPos.add(0, 1), getMapRoomSize(map, mapPos));
	}

	public static int getMapRoomSize(MapItemSavedData map, Vector2ic mapEntrancePos) {
		int i = -1;
		while (isEntranceColor(map, mapEntrancePos.x() + ++i, mapEntrancePos.y())) {
		}
		return i > 5 ? i : 0;
	}

	public static @Nullable Vector2ic getMapRoomPos(MapItemSavedData map, Vector2ic mapEntrancePos, int mapRoomSize) {
		int mapRoomSizeWithGap = mapRoomSize + 4;
		Vector2i mapPos = getMapPlayerPos(map);
		if (mapPos == null) {
			return null;
		}
		Vector2ic offset = new Vector2i(mapEntrancePos.x() % mapRoomSizeWithGap, mapEntrancePos.y() % mapRoomSizeWithGap);
		return mapPos.add(2, 2).sub(offset).sub(Math.floorMod(mapPos.x(), mapRoomSizeWithGap), Math.floorMod(mapPos.y(), mapRoomSizeWithGap)).add(offset);
	}

	public static Vector2ic getMapPosFromPhysical(Vector2ic physicalEntrancePos, Vector2ic mapEntrancePos, int mapRoomSize, Vector2ic physicalPos) {
		return new Vector2i(physicalPos).sub(physicalEntrancePos).div(32).mul(mapRoomSize + 4).add(mapEntrancePos);
	}

	public static Vector2dc getMapPosFromPhysical(Vector2ic physicalEntrancePos, Vector2ic mapEntrancePos, int mapRoomSize, Position physicalPos) {
		return new Vector2d(physicalPos.x(), physicalPos.z()).sub(physicalEntrancePos.x(), physicalEntrancePos.y()).div(32).mul(mapRoomSize + 4).add(mapEntrancePos.x(), mapEntrancePos.y());
	}

	public static Vector2i getMapPosForNWMostRoom(Vector2ic mapEntrancePos, int mapRoomSize) {
		return new Vector2i(Math.floorMod(mapEntrancePos.x(), (mapRoomSize + 4)), Math.floorMod(mapEntrancePos.y(), (mapRoomSize + 4)));
	}

	public static Vector2ic getPhysicalRoomPos(Vec3 pos) {
		return getPhysicalRoomPos(pos.x(), pos.z());
	}

	public static Vector2ic getPhysicalRoomPos(Vec3i pos) {
		return getPhysicalRoomPos(pos.getX(), pos.getZ());
	}

	public static Vector2ic getPhysicalRoomPos(double x, double z) {
		Vector2i physicalPos = new Vector2i(x + 8.5, z + 8.5, RoundingMode.TRUNCATE);
		return physicalPos.sub(Math.floorMod(physicalPos.x(), 32), Math.floorMod(physicalPos.y(), 32)).sub(8, 8);
	}

	public static Vector2ic[] getPhysicalPosFromMap(Vector2ic mapEntrancePos, int mapRoomSize, Vector2ic physicalEntrancePos, Vector2ic... mapPositions) {
		for (int i = 0; i < mapPositions.length; i++) {
			mapPositions[i] = getPhysicalPosFromMap(mapEntrancePos, mapRoomSize, physicalEntrancePos, mapPositions[i]);
		}
		return mapPositions;
	}

	public static Vector2ic getPhysicalPosFromMap(Vector2ic mapEntrancePos, int mapRoomSize, Vector2ic physicalEntrancePos, Vector2ic mapPos) {
		return new Vector2i(mapPos).sub(mapEntrancePos).div(mapRoomSize + 4).mul(32).add(physicalEntrancePos);
	}

	public static Vector2ic getPhysicalCornerPos(Room.Direction direction, IntSortedSet segmentsX, IntSortedSet segmentsY) {
		return switch (direction) {
			case NW -> new Vector2i(segmentsX.firstInt(), segmentsY.firstInt());
			case NE -> new Vector2i(segmentsX.lastInt() + 30, segmentsY.firstInt());
			case SW -> new Vector2i(segmentsX.firstInt(), segmentsY.lastInt() + 30);
			case SE -> new Vector2i(segmentsX.lastInt() + 30, segmentsY.lastInt() + 30);
		};
	}

	public static BlockPos actualToRelative(Room.Direction direction, Vector2ic physicalCornerPos, BlockPos pos) {
		return switch (direction) {
			case NW -> new BlockPos(pos.getX() - physicalCornerPos.x(), pos.getY(), pos.getZ() - physicalCornerPos.y());
			case NE -> new BlockPos(pos.getZ() - physicalCornerPos.y(), pos.getY(), -pos.getX() + physicalCornerPos.x());
			case SW -> new BlockPos(-pos.getZ() + physicalCornerPos.y(), pos.getY(), pos.getX() - physicalCornerPos.x());
			case SE -> new BlockPos(-pos.getX() + physicalCornerPos.x(), pos.getY(), -pos.getZ() + physicalCornerPos.y());
		};
	}

	public static Vec3 actualToRelative(Room.Direction direction, Vector2ic physicalCornerPos, Vec3 pos) {
		return switch (direction) {
			case NW -> new Vec3(pos.x() - physicalCornerPos.x(), pos.y(), pos.z() - physicalCornerPos.y());
			case NE -> new Vec3(pos.z() - physicalCornerPos.y(), pos.y(), -pos.x() + physicalCornerPos.x());
			case SW -> new Vec3(-pos.z() + physicalCornerPos.y(), pos.y(), pos.x() - physicalCornerPos.x());
			case SE -> new Vec3(-pos.x() + physicalCornerPos.x(), pos.y(), -pos.z() + physicalCornerPos.y());
		};
	}

	public static BlockPos relativeToActual(Room.Direction direction, Vector2ic physicalCornerPos, BlockPos pos) {
		return switch (direction) {
			case NW -> new BlockPos(pos.getX() + physicalCornerPos.x(), pos.getY(), pos.getZ() + physicalCornerPos.y());
			case NE -> new BlockPos(-pos.getZ() + physicalCornerPos.x(), pos.getY(), pos.getX() + physicalCornerPos.y());
			case SW -> new BlockPos(pos.getZ() + physicalCornerPos.x(), pos.getY(), -pos.getX() + physicalCornerPos.y());
			case SE -> new BlockPos(-pos.getX() + physicalCornerPos.x(), pos.getY(), -pos.getZ() + physicalCornerPos.y());
		};
	}

	public static Vec3 relativeToActual(Room.Direction direction, Vector2ic physicalCornerPos, Vec3 pos) {
		return switch (direction) {
			case NW -> new Vec3(pos.x() + physicalCornerPos.x(), pos.y(), pos.z() + physicalCornerPos.y());
			case NE -> new Vec3(-pos.z() + physicalCornerPos.x(), pos.y(), pos.x() + physicalCornerPos.y());
			case SW -> new Vec3(pos.z() + physicalCornerPos.x(), pos.y(), -pos.x() + physicalCornerPos.y());
			case SE -> new Vec3(-pos.x() + physicalCornerPos.x(), pos.y(), -pos.z() + physicalCornerPos.y());
		};
	}

	public static @Nullable Type getRoomType(MapItemSavedData map, Vector2ic mapPos) {
		return switch (getColor(map, mapPos)) {
			case GREEN_COLOR -> Type.ENTRANCE;
			case 63 -> Type.ROOM;
			case 66 -> Type.PUZZLE;
			case 62 -> Type.TRAP;
			case 74 -> Type.MINIBOSS;
			case 82 -> Type.FAIRY;
			case 18 -> Type.BLOOD;
			case 85 -> Type.UNKNOWN;
			default -> null;
		};
	}

	public static Vector2ic[] getRoomSegments(MapItemSavedData map, Vector2ic mapPos, int mapRoomSize, byte color) {
		Set<Vector2ic> segments = new HashSet<>();
		Queue<Vector2ic> queue = new ArrayDeque<>();
		segments.add(mapPos);
		queue.add(mapPos);
		while (!queue.isEmpty()) {
			Vector2ic curMapPos = queue.poll();
			Vector2i newMapPos = new Vector2i();
			if (getColor(map, newMapPos.set(curMapPos).sub(1, 0)) == color && !segments.contains(newMapPos.sub(mapRoomSize + 3, 0))) {
				segments.add(newMapPos);
				queue.add(newMapPos);
				newMapPos = new Vector2i();
			}
			if (getColor(map, newMapPos.set(curMapPos).sub(0, 1)) == color && !segments.contains(newMapPos.sub(0, mapRoomSize + 3))) {
				segments.add(newMapPos);
				queue.add(newMapPos);
				newMapPos = new Vector2i();
			}
			if (getColor(map, newMapPos.set(curMapPos).add(mapRoomSize, 0)) == color && !segments.contains(newMapPos.add(4, 0))) {
				segments.add(newMapPos);
				queue.add(newMapPos);
				newMapPos = new Vector2i();
			}
			if (getColor(map, newMapPos.set(curMapPos).add(0, mapRoomSize)) == color && !segments.contains(newMapPos.add(0, 4))) {
				segments.add(newMapPos);
				queue.add(newMapPos);
			}
		}
		return segments.toArray(Vector2ic[]::new);
	}
}
