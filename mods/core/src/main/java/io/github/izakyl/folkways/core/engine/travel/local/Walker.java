package io.github.izakyl.folkways.core.engine.travel.local;

import io.github.izakyl.folkways.core.api.resident.Locomotion;
import io.github.izakyl.folkways.core.api.resident.Locomotions;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;

record Walker(Kind kind, Locomotion locomotion, Mob body) {

    record Kind(ResourceLocation locomotion, int width, int height) {
    }

    // A body the colony has no kind for is surveyed as if it walked, when anything walks at all.
    static Optional<Walker> of(Mob body) {
        return Bodies.of(body).map(Body::kind).map(kind -> kind.locomotion())
            .or(() -> Locomotions.of(Locomotions.WALKING))
            .map(locomotion -> new Walker(new Kind(locomotion.id(), Float.floatToIntBits(body.getBbWidth()),
                Float.floatToIntBits(body.getBbHeight())), locomotion, body));
    }

    static Optional<Walker> of(LivingEntity body) {
        if (body instanceof Mob mob) {
            return of(mob).map(walker -> {
                if (body.getPose() != Pose.SLEEPING || !walker.locomotion().id().equals(Locomotions.WALKING)) {
                    return walker;
                }
                Mob sizing = new SizingBody(body);
                return new Walker(new Kind(walker.locomotion().id(), Float.floatToIntBits(sizing.getBbWidth()),
                    Float.floatToIntBits(sizing.getBbHeight())), walker.locomotion(), sizing);
            });
        }
        return Locomotions.of(Locomotions.WALKING).map(locomotion -> {
            Mob sizing = new SizingBody(body);
            return new Walker(new Kind(locomotion.id(), Float.floatToIntBits(sizing.getBbWidth()),
                Float.floatToIntBits(sizing.getBbHeight())), locomotion, sizing);
        });
    }

    // NodeEvaluator requires a Mob. This unspawned body supplies a non-mob's actual dimensions; it never moves
    // the original entity or joins the world. Players are conservatively surveyed as ordinary walkers.
    private static final class SizingBody extends PathfinderMob {

        private EntityDimensions size;

        SizingBody(LivingEntity original) {
            super(EntityType.VILLAGER, original.level());
            // Sleep is not a way to walk out. Protect the space needed after waking, without waking or
            // resizing the real body while surveying a placement.
            size = original.getPose() == Pose.SLEEPING ? original.getDimensions(Pose.STANDING)
                : EntityDimensions.scalable(original.getBbWidth(), original.getBbHeight());
            refreshDimensions();
            setPos(original.position());
        }

        @Override
        protected EntityDimensions getDefaultDimensions(Pose pose) {
            return size == null ? super.getDefaultDimensions(pose) : size;
        }
    }
}
