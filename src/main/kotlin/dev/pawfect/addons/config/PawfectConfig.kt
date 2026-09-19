package dev.pawfect.addons.config

import com.google.gson.annotations.Expose
import dev.pawfect.addons.config.features.CosmeticsConfig
import dev.pawfect.addons.config.features.DungeonWaypointsConfig
import dev.pawfect.addons.config.features.ExperimentsConfig
import dev.pawfect.addons.config.features.GeneralConfig
import dev.pawfect.addons.config.features.HandChamsConfig
import dev.pawfect.addons.config.features.DevConfig
import dev.pawfect.addons.config.features.HitsoundConfig
import dev.pawfect.addons.config.features.InventoryConfig
import dev.pawfect.addons.config.features.MediaConfig
import dev.pawfect.addons.config.features.MenuConfig
import dev.pawfect.addons.config.features.PlayerChamsConfig
import dev.pawfect.addons.config.features.SecretWaypointsConfig
import dev.pawfect.addons.config.features.MotionBlurConfig
import dev.pawfect.addons.config.features.SkyboxConfig
import dev.pawfect.addons.config.features.RecipeTrackerConfig
import dev.pawfect.addons.config.features.SlayersConfig
import dev.pawfect.addons.config.features.StatsConfig
import dev.pawfect.addons.config.features.ThemeConfig
import dev.pawfect.addons.config.features.TooltipConfig
import dev.pawfect.addons.config.features.VisualsConfig

class PawfectConfig {

    @Expose
    val recipeTracker: RecipeTrackerConfig = RecipeTrackerConfig()

    @Expose
    val slayers: SlayersConfig = SlayersConfig()

    @Expose
    val visuals: VisualsConfig = VisualsConfig()

    @Expose
    val general: GeneralConfig = GeneralConfig()

    @Expose
    val dungeonWaypoints: DungeonWaypointsConfig = DungeonWaypointsConfig()

    @Expose
    val secretWaypoints: SecretWaypointsConfig = SecretWaypointsConfig()

    @Expose
    val handChams: HandChamsConfig = HandChamsConfig()

    @Expose
    val playerChams: PlayerChamsConfig = PlayerChamsConfig()

    @Expose
    val hitsounds: HitsoundConfig = HitsoundConfig()

    @Expose
    val media: MediaConfig = MediaConfig()

    @Expose
    val dev: DevConfig = DevConfig()

    @Expose
    val experiments: ExperimentsConfig = ExperimentsConfig()

    @Expose
    val cosmetics: CosmeticsConfig = CosmeticsConfig()

    @Expose
    val menu: MenuConfig = MenuConfig()

    @Expose
    val inventory: InventoryConfig = InventoryConfig()

    @Expose
    val tooltip: TooltipConfig = TooltipConfig()

    @Expose
    val skybox: SkyboxConfig = SkyboxConfig()

    @Expose
    val motionBlur: MotionBlurConfig = MotionBlurConfig()

    @Expose
    val stats: StatsConfig = StatsConfig()

    @Expose
    val theme: ThemeConfig = ThemeConfig()
}
