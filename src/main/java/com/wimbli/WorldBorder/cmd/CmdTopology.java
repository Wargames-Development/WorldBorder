package com.wimbli.WorldBorder.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.wimbli.WorldBorder.BorderData;
import com.wimbli.WorldBorder.BorderTopology;
import com.wimbli.WorldBorder.Config;


public class CmdTopology extends WBCmd
{
	public CmdTopology()
	{
		name = permission = "topology";
		minParams = 1;
		maxParams = 2;

		addCmdExample(nameEmphasized() + "{world} <normal|cylinder|torus|globe> - set border topology.");
		helpText = "normal blocks crossings, cylinder wraps east/west only, torus wraps both axes, and globe wraps east/west while pairing north/south crossings by mirroring X across the rectangular map centre and reversing heading by 180 degrees. Cylinder and globe require a rectangular border.";
	}

	@Override
	public void execute(CommandSender sender, Player player, List<String> params, String worldName)
	{
		if (player == null && params.size() == 1)
		{
			sendErrorAndHelp(sender, "When running this command from console, you must specify a world.");
			return;
		}

		String value;
		if (params.size() == 2)
		{
			worldName = params.get(0);
			value = params.get(1);
		}
		else
		{
			worldName = player.getWorld().getName();
			value = params.get(0);
		}

		BorderData border = Config.Border(worldName);
		if (border == null)
		{
			sendErrorAndHelp(sender, "This world (\"" + worldName + "\") does not have a border set.");
			return;
		}

		BorderTopology topology = BorderTopology.fromString(value);
		if (topology == null)
		{
			sendErrorAndHelp(sender, "Unknown topology \"" + value + "\".");
			return;
		}

		boolean round = (border.getShape() == null) ? Config.ShapeRound() : border.getShape().booleanValue();
		if (round && (topology == BorderTopology.CYLINDER || topology == BorderTopology.GLOBE))
		{
			sendErrorAndHelp(sender, "Cylinder and globe topology require a rectangular border. Set this world to rectangular first with /wb wshape " + worldName + " rectangular.");
			return;
		}

		border.setTopology(topology);
		Config.setBorder(worldName, border, false);
		sender.sendMessage("Border topology for world \"" + worldName + "\" is now " + topology.getConfigName() + ".");
	}
}
