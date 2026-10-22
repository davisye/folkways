package io.github.izakyl.folkways.core.api.ground;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

public interface Ground {

    boolean known(BlockPos cell);

    BlockState state(BlockPos cell);

    LevelReader level();

    boolean standable(BlockPos feet);

    boolean reachable(BlockPos feet);

    List<BlockPos> stancesFor(BlockPos target);

    // Which stretch of the ground come to holds the cell, or -1 for a cell that is not come to: a stretch is walked
    // both ways between any two of its cells, and not between one of its cells and another stretch's.
    int stretch(BlockPos feet);

    // Every cell come to within arm's reach of the target, seen from there or not, the nearest first.
    List<BlockPos> withinReachOf(BlockPos target);

    Collection<BlockPos> lostSince(Ground earlier);

    Ground with(Map<BlockPos, BlockState> changes);

    // A proof of access to explicit exits, without treating a body's own position as an exit. Each step is
    // traversable in both directions, so a worker can come to the work and leave it by the same passage.
    Access accessFrom(Collection<BlockPos> exits);

    interface Access {

        // -1 means no route was proved within the watched, loaded ground and the search budget.
        int distance(BlockPos feet);

        List<BlockPos> path(BlockPos feet);

        List<BlockPos> stancesFor(BlockPos target);

        boolean canWorkFrom(BlockPos feet, BlockPos target);
    }
}
