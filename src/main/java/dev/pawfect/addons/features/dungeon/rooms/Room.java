package dev.pawfect.addons.features.dungeon.rooms;

import net.minecraft.world.level.material.MapColor;

public class Room {

	public enum Type {
		ENTRANCE(MapColor.PLANT.getPackedId(MapColor.Brightness.HIGH), "Entrance"),
		ROOM(MapColor.COLOR_ORANGE.getPackedId(MapColor.Brightness.LOWEST), "Room"),
		PUZZLE(MapColor.COLOR_MAGENTA.getPackedId(MapColor.Brightness.HIGH), "Puzzle"),
		TRAP(MapColor.COLOR_ORANGE.getPackedId(MapColor.Brightness.HIGH), "Trap"),
		MINIBOSS(MapColor.COLOR_YELLOW.getPackedId(MapColor.Brightness.HIGH), "Miniboss"),
		FAIRY(MapColor.COLOR_PINK.getPackedId(MapColor.Brightness.HIGH), "Fairy"),
		BLOOD(MapColor.FIRE.getPackedId(MapColor.Brightness.HIGH), "Blood"),
		UNKNOWN(MapColor.COLOR_GRAY.getPackedId(MapColor.Brightness.NORMAL), "Unknown");

		public final byte color;
		public final String name;

		Type(byte color, String name) {
			this.color = color;
			this.name = name;
		}

		public boolean needsScanning() {
			return this == ROOM || this == PUZZLE || this == TRAP || this == MINIBOSS;
		}

		public static Type fromColor(byte color) {
			for (Type type : values()) {
				if (type.color == color) return type;
			}
			return UNKNOWN;
		}
	}

	public enum Direction {
		NW, NE, SW, SE
	}

	public enum Shape {
		ONE_BY_ONE("1x1"),
		ONE_BY_TWO("1x2"),
		ONE_BY_THREE("1x3"),
		ONE_BY_FOUR("1x4"),
		L_SHAPE("L-shape"),
		TWO_BY_TWO("2x2"),
		PUZZLE("puzzle"),
		TRAP("trap"),
		MINIBOSS("miniboss");

		public final String shape;

		Shape(String shape) {
			this.shape = shape;
		}

		@Override
		public String toString() {
			return shape;
		}
	}
}
