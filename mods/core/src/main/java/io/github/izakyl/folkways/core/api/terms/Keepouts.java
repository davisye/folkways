package io.github.izakyl.folkways.core.api.terms;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

// Ground nobody sets foot on however open it looks, such as the line a train runs along. Paths go around it, and
// nobody is asked to stand in it.
public final class Keepouts {

    public interface Keepout {
        boolean forbids(Level level, int x, int y, int z);
    }

    private static final List<Keepout> SOURCES = new CopyOnWriteArrayList<>();
    private static final AtomicLong REVISION = new AtomicLong();

    private Keepouts() {
    }

    public static Runnable install(Keepout source) {
        SOURCES.add(source);
        REVISION.incrementAndGet();
        return () -> {
            if (SOURCES.remove(source)) {
                REVISION.incrementAndGet();
            }
        };
    }

    public static long revision() {
        return REVISION.get();
    }

    public static boolean forbidden(Level level, int x, int y, int z) {
        for (Keepout source : SOURCES) {
            if (source.forbids(level, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    public static boolean forbidden(Level level, BlockPos cell) {
        return forbidden(level, cell.getX(), cell.getY(), cell.getZ());
    }
}
