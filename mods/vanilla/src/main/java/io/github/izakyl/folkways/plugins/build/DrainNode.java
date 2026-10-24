package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class DrainNode extends BuildNode {

    DrainNode(BuildJob job, Ends ends) {
        super(job, ends);
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        for (BlockPos cell : remaining(level, job().after(), false).keySet()) {
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), 2);
        }
        for (Map.Entry<BlockPos, BlockState> cell : remaining(level, job().before(), false).entrySet()) {
            level.updateNeighborsAt(cell.getKey(), cell.getValue().getBlock());
        }
        return Outcome.done();
    }
}
