package chattore.commands

import chattore.*
import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import com.velocitypowered.api.proxy.Player
import chattore.entity.ChattORESpec

@CommandAlias("chattore")
@CommandPermission("chattore.manage")
class Chattore(private val chattORE: ChattORE) : BaseCommand() {
    @Default
    @CatchUnknown
    @Subcommand("version")
    fun version(player: Player) {
        player.sendMessage(
            chattORE.config[ChattORESpec.format.chattore].render(
                "Version &7${chattORE.getVersion()}".legacyDeserialize()
            )
        )
    }

    @Subcommand("reload")
    fun reload(player: Player) {
        chattORE.reload()
        player.sendMessage(
            chattORE.config[ChattORESpec.format.chattore].render(
                "Reloaded ChattORE"
            )
        )
    }

    @Subcommand("spy")
    fun spy(player: Player) {
        val setting = chattORE.database.getSetting(SpySetting, player.uniqueId)
        val newSetting = !(setting ?: false)
        chattORE.database.setSetting(SpySetting, player.uniqueId, newSetting)
        player.sendMessage(
            chattORE.config[ChattORESpec.format.chattore].render(
                if (newSetting) {
                    "You are now spying on commands."
                } else {
                    "You are no longer spying on commands."
                }
            )
        )
    }
}