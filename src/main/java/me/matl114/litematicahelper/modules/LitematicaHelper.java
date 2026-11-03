package me.matl114.litematicahelper.modules;

import java.awt.Color;
import fi.dy.masa.litematica.util.SchematicUtils;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.Render3D;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hooks.LitematicaHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

public final class LitematicaHelper extends BaseModule {
    public final ModulePath root = makePath(Configs.SURVIVAL_CONFIG, "litematica-helper");
    public final FlagRef enabled = flagBuilder(root.addEnable()).build();
    public final KeyBindRef replaceHotkey = hotkey(root.add("replace-hotkey"), new MultiKeyBind())
            .build();

    public LitematicaHelper() {
        super("LitematicaHelper");
        bindFlag(enabled);
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPostGameTick(), this::updateRenderCollector);
        registerListener(RenderListener.getRender3DEvent(), this::render3D);
        registerListener(Listener.getItemUseAction(), this::useItem);
    }

    private final RenderCollector<Box> boxCollector = RenderCollectors.createBoxCollector(true, false, false);

    private void updateRenderCollector() {
        boxCollector.clear();
        if (checkNull() || !enabled.get() || !replaceHotkey.get().isAllPressed()) {
            return;
        }

        World schematicWorld = LitematicaHooks.getInstance().getSchematicWorld();
        if (schematicWorld == null) {
            return;
        }

        BlockHitResult hitResult = raycastSchematic(schematicWorld);
        if (hitResult == null) {
            return;
        }

        BlockPos targetPos = hitResult.getBlockPos();
        if (LitematicaHooks.getInstance().isPositionWithinRange(targetPos)
                && !schematicWorld.getBlockState(targetPos).isAir()) {
            boxCollector.submit(new Box(targetPos), Color.CYAN.getRGB());
        }
    }

    private void render3D(Event<Render3D> event) {
        if (checkNull() || !enabled.get()) {
            return;
        }

        Render3D render = event.context();
        RenderUtils.startDrawVirtual(render.stack());
        try {
            boxCollector.render3D(render.stack(), render.partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(render.stack());
        }
    }

    private void useItem(Event<HitResult> event) {
        if (event.isCancelled() || checkNull() || !enabled.get() || !replaceHotkey.get().isAllPressed()) {
            return;
        }

        Hand hand = event.getArgs(0);
        ItemStack stack = mc.player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return;
        }

        event.cancel();

        World schematicWorld = LitematicaHooks.getInstance().getSchematicWorld();
        if (schematicWorld == null) {
            return;
        }

        BlockHitResult hitResult = raycastSchematic(schematicWorld);
        if (hitResult == null) {
            return;
        }

        BlockPos targetPos = hitResult.getBlockPos();
        BlockState targetState = schematicWorld.getBlockState(targetPos);
        if (targetState.isAir() || !LitematicaHooks.getInstance().isPositionWithinRange(targetPos)) {
            return;
        }

        BlockState replacementDefault = blockItem.getBlock().getDefaultState();
        if (targetState.getBlock() == replacementDefault.getBlock()) {
            return;
        }

        if (replaceMatchingProjectedBlocks(hand)) {
            logI18N("litematica-helper.replace.success");
        } else {
            logI18N("litematica-helper.replace.failed");
        }
    }

    private boolean replaceMatchingProjectedBlocks(Hand hand) {
        boolean swapHands = hand == Hand.OFF_HAND;
        ItemStack mainHandStack = mc.player.getStackInHand(Hand.MAIN_HAND);
        ItemStack offHandStack = mc.player.getStackInHand(Hand.OFF_HAND);
        try {
            if (swapHands) {
                mc.player.setStackInHand(Hand.MAIN_HAND, offHandStack);
                mc.player.setStackInHand(Hand.OFF_HAND, mainHandStack);
            }

            return SchematicUtils.replaceBlocksKeepingProperties(mc);
        } catch (RuntimeException | LinkageError exception) {
            return false;
        } finally {
            if (swapHands) {
                mc.player.setStackInHand(Hand.MAIN_HAND, mainHandStack);
                mc.player.setStackInHand(Hand.OFF_HAND, offHandStack);
            }
        }
    }

    private BlockHitResult raycastSchematic(World schematicWorld) {
        Vec3d start = mc.player.getCameraPosVec(1.0F);
        Vec3d end = start.add(mc.player.getRotationVec(1.0F).multiply(mc.player.getBlockInteractionRange()));
        BlockHitResult hitResult = schematicWorld.raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                mc.player));
        return hitResult.getType() == HitResult.Type.BLOCK ? hitResult : null;
    }

}
