package dev.pawfect.addons.features.visual.menu.hub

import dev.pawfect.addons.config.features.MenuSpot
import dev.pawfect.addons.features.visual.menu.hub.WalkPlanner.Waypoint

internal object MenuScenes {

    class Shot(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

    class Script(
        val camera: List<Shot>,
        val walk: List<Waypoint>,
        val greeting: Int,
    ) {
        val spot: MenuSpot?
            get() = camera.lastOrNull()?.let { end ->
                MenuSpot().apply {
                    x = end.x
                    y = end.y
                    z = end.z
                    yaw = end.yaw
                    pitch = end.pitch
                }
            }
    }

    private val HUB = Script(
        camera = emptyList(),
        walk = listOf(
            Waypoint(-127.0, 70.0, 13.0),
            Waypoint(-155.0, 71.0, 29.4),
            Waypoint(-166.0, 73.0, 34.0),
            Waypoint(-165.0, 75.0, 46.0),
            Waypoint(-159.0, 77.0, 53.0),
            Waypoint(-177.0, 83.0, 68.0),
            Waypoint(-187.0, 84.0, 68.0),
            Waypoint(-191.0, 88.0, 43.0),
            Waypoint(-205.0, 91.0, 43.0),
            Waypoint(-217.0, 91.0, 60.0),
        ),
        greeting = 1,
    )

    private val END = Script(
        camera = listOf(
            Shot(-500.14, 101.0, -275.14, 78.13f, -0.94f),
            Shot(-544.45, 102.64, -266.3, 110.41f, 64.58f),
            Shot(-553.5, 27.88, -267.5, 94.44f, 2.64f),
            Shot(-556.5, 24.88, -269.5, 93.45f, -1.22f),
            Shot(-564.26, 22.5, -274.28, 91.45f, -8.97f),
            Shot(-612.53, 27.97, -275.48, 90.43f, 0.77f),
        ),
        walk = listOf(
            Waypoint(-611.36, 8.06, -292.84),
            Waypoint(-631.9, 6.0, -287.07),
            Waypoint(-644.1, 5.0, -273.02),
            Waypoint(-664.96, 8.0, -274.49),
            Waypoint(-648.87, 5.0, -264.51),
            Waypoint(-630.3, 5.5, -257.3),
            Waypoint(-620.83, 8.0, -257.15),
            Waypoint(-614.75, 8.0, -259.7),
        ),
        greeting = 3,
    )

    private val scripts = mapOf(HubScene.HUB to HUB, "combat_3" to END)

    fun forArea(area: String?): Script? = area?.let(scripts::get)

    private val titles = mapOf(HubScene.HUB to "SkyBlock Hub", "combat_3" to "The End")

    fun title(area: String): String = titles[area] ?: area.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
}
