package me.matl114.litematicahelper.modules;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import fi.dy.masa.litematica.util.SchematicUtils;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.catchers.PacketCatcherImpl;
import me.matl114.events.impl.Render3D;
import me.matl114.accessors.interfaces.TileInventory;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.InvTasks;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hooks.LitematicaHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.hacks.utils.RenderUtils;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
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
    public final ModulePath schematicModification = root.add("schematic-modification");
    public final FlagRef schematicModificationEnabled =
            flagBuilder(schematicModification.addEnable()).build();
    public final KeyBindRef schematicModificationToggleHotkey = toggleHotkey(
                    schematicModification.addHotkey(), new MultiKeyBind(), schematicModification.addEnable())
            .build();
    public final ModulePath containerFill = root.add("container-fill");
    public final FlagRef containerFillEnabled = flagBuilder(containerFill.addEnable()).build();
    public final KeyBindRef containerFillToggleHotkey =
            toggleHotkey(containerFill.addHotkey(), new MultiKeyBind(), containerFill.addEnable()).build();
    public final KeyBindRef replaceHotkey = hotkey(schematicModification.add("replace-hotkey"), new MultiKeyBind())
            .build();
    public final KeyBindRef saveContainerTemplateHotkey =
            hotkey(containerFill.add("save-template-hotkey"), new MultiKeyBind())
                    .registerHotkey(HotKeyUtils.asNonInputHandler(this::saveCurrentContainerTemplate))
                    .build();
    public final FlagRef autoCloseAfterFill =
            flagBuilder(containerFill.add("auto-close")).defaultValue(true).build();
    public final FlagRef ignoreAmount =
            flagBuilder(containerFill.add("ignore-amount")).defaultValue(false).build();

    private final Map<Block, List<ItemStack>> containerTemplates = new HashMap<>();
    private long fillRequestGeneration;

    public LitematicaHelper() {
        super("LitematicaHelper");
        schematicModificationEnabled.addUpdateListenerWithUpdate(
                registerReason(value -> refreshActiveStatus(), REASON_UPDATE_LISTENER));
        containerFillEnabled.addUpdateListenerWithUpdate(registerReason(value -> {
            if (!value) {
                clearContainerState();
            }
            refreshActiveStatus();
        }, REASON_UPDATE_LISTENER));
        refreshActiveStatus();
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPostGameTick(), this::updateRenderCollector);
        registerListener(RenderListener.getRender3DEvent(), this::render3D);
        registerListener(Listener.getItemUseAction(), this::useItem);
        registerListener(Listener.getPostOpenHandledScreen(), this::onOpenHandledScreen);
        registerListener(Listener.getServerLeavePoint(), this::clearContainerState);
    }

    private final RenderCollector<Box> boxCollector = RenderCollectors.createBoxCollector(true, false, false);

    private void updateRenderCollector() {
        boxCollector.clear();
        if (checkNull() || !schematicModificationEnabled.get() || !replaceHotkey.get().isAllPressed()) {
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
        if (checkNull() || !schematicModificationEnabled.get()) {
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

    private void onOpenHandledScreen(Event<HandledScreen<?>> event) {
        long requestGeneration = ++fillRequestGeneration;
        if (checkNull() || !containerFillEnabled.get()) {
            return;
        }

        HandledScreen<?> screen = event.context();
        if (!(screen instanceof TileInventory tile) || tile.isVirtual()) {
            return;
        }

        Block block = tile.getBlockType();
        BlockPos pos = tile.getPos();
        if (block == null || pos == null) {
            return;
        }

        World schematicWorld = LitematicaHooks.getInstance().getSchematicWorld();
        if (schematicWorld == null || !LitematicaHooks.getInstance().isPositionWithinRange(pos)) {
            return;
        }
        if (schematicWorld.getBlockState(pos).getBlock() != block) {
            return;
        }

        List<ItemStack> template = containerTemplates.get(block);
        if (template != null) {
            ScreenHandler handler = screen.getScreenHandler();
            int syncId = handler.syncId;
            Listener.addPostPacketCatcher(new PacketCatcherImpl<>(InventoryS2CPacket.class, packetEvent -> {
                if (requestGeneration != fillRequestGeneration) {
                    return true;
                }
                if (packetEvent.context().syncId() != syncId) {
                    return false;
                }
                if (checkNull()
                        || !containerFillEnabled.get()
                        || mc.currentScreen != screen
                        || mc.player.currentScreenHandler != handler) {
                    return true;
                }
                tryFillContainer(screen, template);
                return true;
            }));
        }
    }

    private void saveCurrentContainerTemplate() {
        if (checkNull()
                || !containerFillEnabled.get()
                || !(mc.currentScreen instanceof HandledScreen<?> screen)
                || !(screen instanceof TileInventory tile)
                || tile.isVirtual()) {
            return;
        }

        Block block = tile.getBlockType();
        if (block == null) {
            return;
        }

        List<ItemStack> contents = getContainerContents(screen);
        if (!contents.isEmpty()) {
            containerTemplates.put(block, contents);
            logI18N("litematica-helper.container-fill.template-saved");
        }
    }

    private List<ItemStack> getContainerContents(HandledScreen<?> screen) {
        List<ItemStack> contents = new ArrayList<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (!(slot.inventory instanceof PlayerInventory)) {
                contents.add(slot.getStack().copy());
            }
        }
        return List.copyOf(contents);
    }

    private void tryFillContainer(HandledScreen<?> screen, List<ItemStack> template) {
        ScreenHandler handler = screen.getScreenHandler();
        if (!handler.getCursorStack().isEmpty()) {
            return;
        }

        List<Slot> containerSlots = new ArrayList<>();
        List<Slot> playerSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory) {
                playerSlots.add(slot);
            } else {
                containerSlots.add(slot);
            }
        }
        if (containerSlots.size() != template.size()) {
            return;
        }

        List<SlotTransfer> transfers = new ArrayList<>();
        if (!createContainerFillPlan(containerSlots, playerSlots, template, transfers)) {
            return;
        }

        if (mc.interactionManager == null
                || mc.player.currentScreenHandler != handler
                || InvTasks.getCurrentServerScreenHandler(mc.player) != handler) {
            return;
        }
        for (SlotTransfer transfer : transfers) {
            InvTasks.moveStackFromToAmount(
                    handler, transfer.sourceSlotId(), transfer.destinationSlotId(), transfer.amount());
        }
        if (autoCloseAfterFill.get() && mc.currentScreen == screen) {
            screen.close();
        }
    }

    private boolean createContainerFillPlan(
            List<Slot> containerSlots, List<Slot> playerSlots, List<ItemStack> template, List<SlotTransfer> transfers) {
        int[] sourceAvailable = new int[playerSlots.size()];
        for (int i = 0; i < playerSlots.size(); i++) {
            sourceAvailable[i] = playerSlots.get(i).getStack().getCount();
        }

        int[] destinationCounts = new int[containerSlots.size()];
        int[] targetCounts = new int[containerSlots.size()];
        boolean[] fillSlots = new boolean[containerSlots.size()];
        for (int i = 0; i < containerSlots.size(); i++) {
            Slot destination = containerSlots.get(i);
            ItemStack expected = template.get(i);
            ItemStack current = destination.getStack();
            destinationCounts[i] = current.getCount();

            if (expected.isEmpty() || !current.isEmpty()) {
                continue;
            }

            if (!destination.canInsert(expected)) {
                return false;
            }
            int maxCount = destination.getMaxItemCount(expected);
            targetCounts[i] = ignoreAmount.get() ? Math.min(expected.getCount(), maxCount) : expected.getCount();
            if (targetCounts[i] > maxCount) {
                return false;
            }
            fillSlots[i] = true;
        }

        if (ignoreAmount.get()) {
            for (int i = 0; i < containerSlots.size(); i++) {
                if (!fillSlots[i]) {
                    continue;
                }
                if (planItemTransfers(
                                i, 1, containerSlots, playerSlots, template, sourceAvailable, destinationCounts, transfers)
                        != 1) {
                    return false;
                }
            }
        }

        for (int i = 0; i < containerSlots.size(); i++) {
            if (!fillSlots[i]) {
                continue;
            }
            int needed = targetCounts[i] - destinationCounts[i];
            int transferred = planItemTransfers(
                    i, needed, containerSlots, playerSlots, template, sourceAvailable, destinationCounts, transfers);
            if (!ignoreAmount.get() && transferred < needed) {
                return false;
            }
        }
        return true;
    }

    private int planItemTransfers(
            int destinationIndex,
            int amountNeeded,
            List<Slot> containerSlots,
            List<Slot> playerSlots,
            List<ItemStack> template,
            int[] sourceAvailable,
            int[] destinationCounts,
            List<SlotTransfer> transfers) {
        int transferred = 0;
        Slot destination = containerSlots.get(destinationIndex);
        ItemStack expected = template.get(destinationIndex);
        for (int sourceIndex = 0; transferred < amountNeeded && sourceIndex < playerSlots.size(); sourceIndex++) {
            Slot source = playerSlots.get(sourceIndex);
            ItemStack availableStack = source.getStack();
            int available = sourceAvailable[sourceIndex];
            if (available <= 0 || availableStack.isEmpty()
                    || !ItemStack.areItemsAndComponentsEqual(expected, availableStack)) {
                continue;
            }

            int amount = Math.min(amountNeeded - transferred, available);
            transfers.add(new SlotTransfer(source.id, destination.id, amount));
            sourceAvailable[sourceIndex] -= amount;
            destinationCounts[destinationIndex] += amount;
            transferred += amount;
        }
        return transferred;
    }

    private void clearContainerState() {
        containerTemplates.clear();
        fillRequestGeneration++;
    }

    private void refreshActiveStatus() {
        updateActiveStatus(schematicModificationEnabled.get() || containerFillEnabled.get());
    }

    private void useItem(Event<HitResult> event) {
        if (event.isCancelled()
                || checkNull()
                || !schematicModificationEnabled.get()
                || !replaceHotkey.get().isAllPressed()) {
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

    private record SlotTransfer(int sourceSlotId, int destinationSlotId, int amount) {}

}
