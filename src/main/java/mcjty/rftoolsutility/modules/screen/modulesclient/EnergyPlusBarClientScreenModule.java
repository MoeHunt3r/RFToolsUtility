package mcjty.rftoolsutility.modules.screen.modulesclient;

import mcjty.lib.varia.BlockPosTools;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;

public class EnergyPlusBarClientScreenModule extends EnergyBarClientScreenModule {

    @Override
    protected void setupCoordinateFromNBT(CompoundNBT tagCompound, DimensionType dim, BlockPos pos) {
        coordinate = BlockPosTools.INVALID;
        if (tagCompound.contains("monitorx")) {
            this.dim = DimensionType.byName(new ResourceLocation(tagCompound.getString("monitordim")));
            coordinate = new BlockPos(tagCompound.getInt("monitorx"), tagCompound.getInt("monitory"), tagCompound.getInt("monitorz"));
        }
    }

    @Override
    public void mouseClick(World world, int x, int y, boolean clicked) {

    }
}
