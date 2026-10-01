package dev.pawfect.addons.features.profile

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** What me.pawfectaddons.net/api/profile returns, already levelled by the server. */
class ProfileData(val uuid: String, val ign: String, val profiles: List<Profile>) {

    class Level(val level: Int, val progress: Float, val xp: Double, val maxed: Boolean, val cap: Int)

    class Skill(val id: String, val name: String, val level: Level)

    class Slayer(val id: String, val name: String, val level: Level, val kills: List<Pair<Int, Int>>)

    class DungeonClass(val id: String, val level: Level)

    class Dungeons(
        val catacombs: Level,
        val classes: List<DungeonClass>,
        val selectedClass: String?,
        val secrets: Long,
        val floors: List<Pair<Int, Int>>,
        val masterFloors: List<Pair<Int, Int>>,
    )

    class Pet(
        val type: String,
        val tier: String,
        val level: Int,
        val maxLevel: Int,
        val progress: Float,
        val exp: Double,
        val active: Boolean,
        val heldItem: String?,
        val skin: String?,
    )

    class Profile(
        val id: String,
        val name: String,
        val mode: String,
        val selected: Boolean,
        val coopSize: Int,
        val purse: Double,
        val bank: Double?,
        val sbLevel: Int,
        val sbProgress: Float,
        val fairySouls: Int,
        val skills: List<Skill>?,
        val skillAverage: Double?,
        val slayers: List<Slayer>,
        val dungeons: Dungeons,
        val pets: List<Pet>,
        /** Base64 NBT per container, or null when hidden or empty. */
        val inventories: Map<String, String?>,
        val backpacks: Map<Int, String>,
        val backpackIcons: Map<Int, String>,
        val wardrobeSlot: Int?,
        val inventoryApi: Boolean,
        val bankApi: Boolean,
    )

    companion object {
        fun parse(json: JsonObject): ProfileData = ProfileData(
            uuid = json.str("uuid"),
            ign = json.str("ign"),
            profiles = json.getAsJsonArray("profiles")?.map { profile(it.asJsonObject) } ?: emptyList(),
        )

        private fun profile(p: JsonObject): Profile {
            val dungeons = p.obj("dungeons")
            val sb = p.obj("sb_level")
            val api = p.obj("api")
            return Profile(
                id = p.str("id"),
                name = p.str("name"),
                mode = p.str("mode"),
                selected = p.bool("selected"),
                coopSize = p.int("coop_size"),
                purse = p.num("purse"),
                bank = p.get("bank")?.takeUnless { it.isJsonNull }?.asDouble,
                sbLevel = sb.int("level"),
                sbProgress = sb.num("progress").toFloat(),
                fairySouls = p.int("fairy_souls"),
                skills = p.get("skills")?.takeIf { it.isJsonArray }?.asJsonArray?.map {
                    val s = it.asJsonObject
                    Skill(s.str("id"), s.str("name"), level(s))
                },
                skillAverage = p.get("skill_average")?.takeUnless { it.isJsonNull }?.asDouble,
                slayers = p.getAsJsonArray("slayers")?.map {
                    val s = it.asJsonObject
                    Slayer(
                        s.str("id"),
                        s.str("name"),
                        level(s),
                        s.getAsJsonArray("kills")?.map { k -> k.asJsonObject.int("tier") to k.asJsonObject.int("kills") } ?: emptyList(),
                    )
                } ?: emptyList(),
                dungeons = Dungeons(
                    catacombs = level(dungeons.obj("catacombs")),
                    classes = dungeons.getAsJsonArray("classes")?.map { DungeonClass(it.asJsonObject.str("id"), level(it.asJsonObject)) } ?: emptyList(),
                    selectedClass = dungeons.get("selected_class")?.takeUnless { it.isJsonNull }?.asString,
                    secrets = dungeons.num("secrets").toLong(),
                    floors = floors(dungeons.getAsJsonArray("floors")),
                    masterFloors = floors(dungeons.getAsJsonArray("master_floors")),
                ),
                pets = p.getAsJsonArray("pets")?.map {
                    val pet = it.asJsonObject
                    Pet(
                        type = pet.str("type"),
                        tier = pet.str("tier"),
                        level = pet.int("level"),
                        maxLevel = pet.int("max_level"),
                        progress = pet.num("progress").toFloat(),
                        exp = pet.num("exp"),
                        active = pet.bool("active"),
                        heldItem = pet.get("held_item")?.takeUnless { e -> e.isJsonNull }?.asString,
                        skin = pet.get("skin")?.takeUnless { e -> e.isJsonNull }?.asString,
                    )
                } ?: emptyList(),
                inventories = p.obj("inventories").entrySet().associate { (k, v) -> k to v.takeUnless { it.isJsonNull }?.asString },
                backpacks = slotted(p.obj("backpacks")),
                backpackIcons = slotted(p.obj("backpack_icons")),
                wardrobeSlot = p.get("wardrobe_slot")?.takeUnless { it.isJsonNull }?.asInt,
                inventoryApi = api.bool("inventory"),
                bankApi = api.bool("bank"),
            )
        }

        private fun level(o: JsonObject) = Level(
            level = o.int("level"),
            progress = o.num("progress").toFloat(),
            xp = o.num("xp"),
            maxed = o.bool("maxed"),
            cap = o.int("cap"),
        )

        private fun floors(array: com.google.gson.JsonArray?): List<Pair<Int, Int>> =
            array?.map { it.asJsonObject.int("floor") to it.asJsonObject.int("runs") } ?: emptyList()

        private fun slotted(o: JsonObject): Map<Int, String> =
            o.entrySet().mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v.asString } }.toMap().toSortedMap()

        private fun JsonObject.obj(key: String): JsonObject = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        private fun JsonObject.str(key: String): String = get(key)?.takeUnless(JsonElement::isJsonNull)?.asString ?: ""
        private fun JsonObject.num(key: String): Double = get(key)?.takeUnless(JsonElement::isJsonNull)?.asDouble ?: 0.0
        private fun JsonObject.int(key: String): Int = num(key).toInt()
        private fun JsonObject.bool(key: String): Boolean = get(key)?.takeUnless(JsonElement::isJsonNull)?.asBoolean ?: false
    }
}
