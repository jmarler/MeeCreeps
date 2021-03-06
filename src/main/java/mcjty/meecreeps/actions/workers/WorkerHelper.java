package mcjty.meecreeps.actions.workers;

import mcjty.meecreeps.ForgeEventHandlers;
import mcjty.meecreeps.actions.*;
import mcjty.meecreeps.api.*;
import mcjty.meecreeps.blocks.ModBlocks;
import mcjty.meecreeps.entities.EntityMeeCreeps;
import mcjty.meecreeps.items.CreepCubeItem;
import mcjty.meecreeps.network.PacketHandler;
import mcjty.meecreeps.varia.GeneralTools;
import mcjty.meecreeps.varia.InventoryTools;
import mcjty.meecreeps.varia.SoundTools;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class WorkerHelper implements IWorkerHelper {

    private final double DISTANCE_TOLERANCE = 1.4;

    private IActionWorker worker;
    protected final ActionOptions options;
    protected final EntityMeeCreeps entity;
    protected boolean needsToPutAway = false;
    protected int waitABit = 10;
    private int speed = 10;

    private BlockPos movingToPos;
    private Entity movingToEntity;

    // To detect if we're stuck
    private double prevPosX;
    private double prevPosY;
    private double prevPosZ;
    private int stuckCounter;

    private int pathTries = 0;
    protected Consumer<BlockPos> job;
    protected List<EntityItem> itemsToPickup = new ArrayList<>();
    private BlockPos materialChest;

    private String lastMessage = "";

    public WorkerHelper(EntityMeeCreeps entity, IActionContext options) {
        this.options = (ActionOptions) options;
        this.entity = entity;
    }

    public void setWorker(IActionWorker worker) {
        this.worker = worker;
    }

    public IActionWorker getWorker() {
        return worker;
    }

    @Override
    public void setSpeed(int speed) {
        this.speed = speed;
    }

    @Override
    public int getSpeed() {
        return speed;
    }

    @Override
    public IActionContext getContext() {
        return options;
    }

    @Override
    public IMeeCreep getMeeCreep() {
        return entity;
    }

    private static final IDesiredBlock AIR = new IDesiredBlock() {
        @Override
        public String getName() {
            return "air";
        }

        @Override
        public int getAmount() {
            return 0;
        }

        @Override
        public Predicate<ItemStack> getMatcher() {
            return ItemStack::isEmpty;
        }

        @Override
        public Predicate<IBlockState> getStateMatcher() {
            return blockState -> blockState.getBlock() == Blocks.AIR;
        }
    };


    private static final IDesiredBlock IGNORE = new IDesiredBlock() {
        @Override
        public String getName() {
            return "IGNORE";
        }

        @Override
        public int getAmount() {
            return 0;
        }

        @Override
        public int getPass() {
            return -1;          // That way this is ignored
        }

        @Override
        public boolean isOptional() {
            return true;
        }

        @Override
        public Predicate<ItemStack> getMatcher() {
            return stack -> false;
        }

        @Override
        public Predicate<IBlockState> getStateMatcher() {
            return blockState -> false;
        }
    };


    @Override
    public IDesiredBlock getAirBlock() {
        return AIR;
    }

    @Override
    public IDesiredBlock getIgnoreBlock() {
        return IGNORE;
    }

    /**
     * Returns absolute position
     */
    @Override
    public BlockPos findSpotToFlatten(@Nonnull IBuildSchematic schematic) {
        BlockPos tpos = options.getTargetPos();
        BlockPos minPos = schematic.getMinPos();
        BlockPos maxPos = schematic.getMaxPos();

        List<BlockPos> todo = new ArrayList<>();
        for (int x = minPos.getX(); x <= maxPos.getX(); x++) {
            for (int y = minPos.getY(); y <= maxPos.getY(); y++) {
                for (int z = minPos.getZ(); z <= maxPos.getZ(); z++) {
                    BlockPos relativePos = new BlockPos(x, y, z);
                    BlockPos p = tpos.add(relativePos);
                    IBlockState state = entity.getWorld().getBlockState(p);
                    IDesiredBlock desired = schematic.getDesiredBlock(relativePos);
                    if (desired != IGNORE) {
                        if (!desired.getStateMatcher().test(state) && !entity.getWorld().isAirBlock(p)) {
                            todo.add(p);
                        }
                    }
                }
            }
        }
        if (todo.isEmpty()) {
            return null;
        }

        BlockPos position = entity.getEntity().getPosition();
        todo.sort((o1, o2) -> {
            double d1 = position.distanceSq(o1);
            double d2 = position.distanceSq(o2);
            return Double.compare(d1, d2);
        });
        return todo.get(0);
    }

    /**
     * Return the relative spot to build
     */
    @Override
    public BlockPos findSpotToBuild(@Nonnull IBuildSchematic schematic, @Nonnull BuildProgress progress, @Nonnull Set<BlockPos> toSkip) {
        BlockPos tpos = options.getTargetPos();
        BlockPos minPos = schematic.getMinPos();
        BlockPos maxPos = schematic.getMaxPos();

        List<BlockPos> todo = new ArrayList<>();
        for (int x = minPos.getX(); x <= maxPos.getX(); x++) {
            for (int z = minPos.getZ(); z <= maxPos.getZ(); z++) {
                BlockPos relativePos = new BlockPos(x, progress.getHeight(), z);
                if (toSkip == null || !toSkip.contains(relativePos)) {
                    BlockPos p = tpos.add(relativePos);
                    IBlockState state = entity.getWorld().getBlockState(p);
                    IDesiredBlock desired = schematic.getDesiredBlock(relativePos);
                    if (desired.getPass() == progress.getPass() && !desired.getStateMatcher().test(state)) {
                        todo.add(relativePos);
                    }
                }
            }
        }
        if (todo.isEmpty()) {
            if (!progress.next(schematic)) {
                return null;    // Done
            }
            return findSpotToBuild(schematic, progress, toSkip);
        }
        BlockPos position = entity.getEntity().getPosition().subtract(tpos);        // Make entity position relative for distance calculation
        todo.sort((o1, o2) -> {
            double d1 = position.distanceSq(o1);
            double d2 = position.distanceSq(o2);
            return Double.compare(d1, d2);
        });
        return todo.get(0);
    }

    @Override
    public boolean handleFlatten(@Nonnull IBuildSchematic schematic) {
        BlockPos flatSpot = findSpotToFlatten(schematic);
        if (flatSpot == null) {
            return false;
        } else {
            BlockPos navigate = findBestNavigationSpot(flatSpot);
            if (navigate != null) {
                navigateTo(navigate, p -> harvestAndDrop(flatSpot));
            } else {
                // We couldn't reach it. Just drop the block
                harvestAndDrop(flatSpot);
            }
            return true;
        }
    }


    @Override
    public boolean handleBuilding(@Nonnull IBuildSchematic schematic, @Nonnull BuildProgress progress, @Nonnull Set<BlockPos> toSkip) {
        BlockPos relativePos = findSpotToBuild(schematic, progress, toSkip);
        if (relativePos != null) {
            IDesiredBlock desired = schematic.getDesiredBlock(relativePos);
            if (!entity.hasItem(desired.getMatcher())) {
                if (entity.hasRoom(desired.getMatcher())) {
                    if (desired.isOptional()) {
                        if (!findItemOnGroundOrInChest(desired.getMatcher(), desired.getAmount())) {
                            // We don't have any of these. Just skip them
                            toSkip.add(relativePos);
                        }
                    } else {
                        findItemOnGroundOrInChest(desired.getMatcher(), "I cannot find any " + desired.getName(), desired.getAmount());
                    }
                } else {
                    // First put away stuff
                    putStuffAway();
                }
            } else {
                BlockPos buildPos = relativePos.add(options.getTargetPos());
                BlockPos navigate = findBestNavigationSpot(buildPos);
                if (navigate != null) {
                    navigateTo(navigate, p -> {
                        placeBuildingBlock(buildPos, desired);
                    });
                } else {
                    // We couldn't reach it. Just build the block
                    placeBuildingBlock(buildPos, desired);
                }
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void giveDropsToMeeCreeps(@Nonnull List<ItemStack> drops) {
        for (ItemStack stack : drops) {
            ItemStack remaining = entity.addStack(stack);
            if (!remaining.isEmpty()) {
                itemsToPickup.add(entity.entityDropItem(remaining, 0.0f));
                needsToPutAway = true;
            }
        }
    }

    @Override
    public void showMessage(String message) {
        if (lastMessage.equals(message)) {
            return;
        }
        lastMessage = message;
            EntityPlayerMP player = getPlayer();
            if (player != null) {
                PacketHandler.INSTANCE.sendTo(new PacketShowBalloonToClient(message), player);
            }
    }

    @Override
    public void registerHarvestableBlock(BlockPos pos) {
        ForgeEventHandlers.harvestableBlocksToCollect.put(pos, options.getActionId());
    }

    @Override
    public void navigateTo(BlockPos pos, Consumer<BlockPos> job) {
        double d = getSquareDist(entity, pos);
        if (d < DISTANCE_TOLERANCE) {
            job.accept(pos);
        } else if (!entity.getNavigator().tryMoveToXYZ(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 2.0)) {
            // We need to teleport
            entity.setPositionAndUpdate(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
            job.accept(pos);
        } else {
            this.movingToPos = pos;
            this.movingToEntity = null;
            pathTries = 1;
            this.job = job;
            prevPosX = entity.posX;
            prevPosY = entity.posY;
            prevPosZ = entity.posZ;
            stuckCounter = 0;
//            prevPosX = entity.posX;
        }
    }

    @Override
    public boolean navigateTo(Entity dest, Consumer<BlockPos> job, double maxDist) {
        if (dest == null || dest.isDead) {
            return false;
        }
        double d = getSquareDist(entity, dest);
        if (d > maxDist*maxDist) {
            return false;
        } else if (d < DISTANCE_TOLERANCE) {
            job.accept(dest.getPosition());
        } else if (!entity.getNavigator().tryMoveToEntityLiving(dest, 2.0)) {
            // We need to teleport
            entity.setPositionAndUpdate(dest.posX, dest.posY, dest.posZ);
            job.accept(dest.getPosition());
        } else {
            this.movingToPos = null;
            this.movingToEntity = dest;
            pathTries = 1;
            this.job = job;
        }
        return true;
    }

    private static double getSquareDist(Entity source, BlockPos dest) {
        double d0 = dest.distanceSqToCenter(source.posX, source.posY - 1, source.posZ);
        double d1 = dest.distanceSqToCenter(source.posX, source.posY, source.posZ);
        double d2 = dest.distanceSqToCenter(source.posX, source.posY + source.getEyeHeight(), source.posZ);
        return Math.min(Math.min(d0, d1), d2);
    }

    private static double getSquareDist(Entity source, Entity dest) {
        Vec3d lowPosition = new Vec3d(source.posX, source.posY-1, source.posZ);
        Vec3d position = new Vec3d(source.posX, source.posY, source.posZ);
        Vec3d eyePosition = new Vec3d(source.posX, source.posY+source.getEyeHeight(), source.posZ);
        double d0 = lowPosition.squareDistanceTo(dest.posX, dest.posY, dest.posZ);
        double d1 = position.squareDistanceTo(dest.posX, dest.posY, dest.posZ);
        double d2 = eyePosition.squareDistanceTo(dest.posX, dest.posY, dest.posZ);
        return Math.min(Math.min(d0, d1), d2);
    }

    @Override
    public boolean navigateTo(Entity dest, Consumer<BlockPos> job) {
        return navigateTo(dest, job, 1000000000);
    }

    private boolean isStuck() {
        return Math.abs(entity.posX-prevPosX) < 0.01 && Math.abs(entity.posY-prevPosY) < 0.01 && Math.abs(entity.posZ-prevPosZ) < 0.01;
    }

    private boolean isCube(ItemStack stack) {
        return stack.getItem() instanceof CreepCubeItem;
    }

    public void tick(boolean timeToWrapUp) {
        waitABit--;
        if (waitABit > 0) {
            return;
        }
        // @todo config
        waitABit = speed;

        if (job != null) {
            handleJob();
        } else if (entity.hasItem(this::isCube)) {
            spawnAngryCreep();
        } else if (findMeeCreepBoxOnGround()) {
            entity.dropInventory();
            setSpeed(20);
        } else if (!options.getDrops().isEmpty()) {
            handleDropCollection();
        } else if (needToFindChest(timeToWrapUp)) {
            handlePutAway();
        } else if (!itemsToPickup.isEmpty()) {
            tryFindingItemsToPickup();
        } else {
            worker.tick(timeToWrapUp);
        }
    }

    private void spawnAngryCreep() {
        entity.setHeldBlockState(ModBlocks.heldCubeBlock.getDefaultState());
        entity.setVariationFace(1);
        ServerActionManager manager = ServerActionManager.getManager();
        World world = entity.getWorld();

        Random r = entity.getRandom();
        BlockPos targetPos = new BlockPos(entity.posX + r.nextFloat()*8 - 4, entity.posY, entity.posZ + r.nextFloat()*8 - 4);
        int actionId = manager.createActionOptions(world, targetPos, EnumFacing.UP, getPlayer());
        ActionOptions.spawn(world, targetPos, EnumFacing.UP, actionId);
        manager.performAction(getPlayer(), actionId, new MeeCreepActionType("meecreeps.angry"), null);
    }

    private void handlePutAway() {
        if (!findChestToPutItemsIn()) {
            if (!navigateTo(getPlayer(), (p) -> giveToPlayerOrDrop(), 12)) {
                entity.dropInventory();
            }
        }
        needsToPutAway = false;
    }

    private void handleDropCollection() {
        // There are drops we need to collect first.
        for (Pair<BlockPos, ItemStack> pair : options.getDrops()) {
            ItemStack drop = pair.getValue();
            if (!drop.isEmpty()) {
                ItemStack remaining = entity.addStack(drop);
                if (!remaining.isEmpty()) {
                    entity.entityDropItem(remaining, 0.0f);
                    needsToPutAway = true;
                }
            }
        }
        options.clearDrops();
        ServerActionManager.getManager().save();
        waitABit = 1;   // Process faster
    }

    private void handleJob() {
        if (movingToEntity != null) {
            if (movingToEntity.isDead) {
                job = null;
            } else {
                double d = getSquareDist(entity, movingToEntity);
                if (d < DISTANCE_TOLERANCE) {
                    job.accept(movingToEntity.getPosition());
                    job = null;
                } else if (entity.getNavigator().noPath()) {
                    if (pathTries > 2) {
                        entity.setPositionAndUpdate(movingToEntity.posX, movingToEntity.posY, movingToEntity.posZ);
                        job.accept(movingToEntity.getPosition());
                        job = null;
                    } else {
                        pathTries++;
                        entity.getNavigator().tryMoveToEntityLiving(movingToEntity, 2.0);
                        stuckCounter = 0;
                    }
                } else if (isStuck()) {
                    stuckCounter++;
                    if (stuckCounter > 5) {
                        entity.setPositionAndUpdate(movingToEntity.posX, movingToEntity.posY, movingToEntity.posZ);
                        job.accept(movingToEntity.getPosition());
                        job = null;
                    }
                }
            }
        } else {
            double d = getSquareDist(entity, movingToPos);
            if (d < DISTANCE_TOLERANCE) {
                job.accept(movingToPos);
                job = null;
            } else if (entity.getNavigator().noPath()) {
                if (pathTries > 2) {
                    entity.setPositionAndUpdate(movingToPos.getX() + .5, movingToPos.getY(), movingToPos.getZ() + .5);
                    job.accept(movingToPos);
                    job = null;
                } else {
                    pathTries++;
                    entity.getNavigator().tryMoveToXYZ(movingToPos.getX() + .5, movingToPos.getY(), movingToPos.getZ() + .5, 2.0);
                    stuckCounter = 0;
                }
            } else if (isStuck()) {
                stuckCounter++;
                if (stuckCounter > 5) {
                    entity.setPositionAndUpdate(movingToPos.getX() + .5, movingToPos.getY(), movingToPos.getZ() + .5);
                    job.accept(movingToPos);
                    job = null;
                }
            }
        }
        prevPosX = entity.posX;
        prevPosY = entity.posY;
        prevPosZ = entity.posZ;
    }

    @Override
    public void placeBuildingBlock(BlockPos pos, IDesiredBlock desiredBlock) {
        World world = entity.getWorld();
        if (!world.isAirBlock(pos) && !world.getBlockState(pos).getBlock().isReplaceable(world, pos)) {
            harvestAndDrop(pos);
        }
        ItemStack blockStack = entity.consumeItem(desiredBlock.getMatcher(), 1);
        if (!blockStack.isEmpty()) {
            if (blockStack.getItem() instanceof ItemBlock) {
                Block block = ((ItemBlock) blockStack.getItem()).getBlock();
                IBlockState stateForPlacement = block.getStateForPlacement(world, pos, EnumFacing.UP, 0, 0, 0, blockStack.getItem().getMetadata(blockStack), GeneralTools.getHarvester(), EnumHand.MAIN_HAND);
                entity.getWorld().setBlockState(pos, stateForPlacement, 3);
                SoundTools.playSound(world, block.getSoundType().getPlaceSound(), pos.getX(), pos.getY(), pos.getZ(), 1.0f, 1.0f);
            } else {
                GeneralTools.getHarvester().setPosition(entity.getEntity().posX, entity.getEntity().posY, entity.getEntity().posZ);
                blockStack.getItem().onItemUse(GeneralTools.getHarvester(), world, pos, EnumHand.MAIN_HAND, EnumFacing.UP, 0, 0, 0);
            }
            boolean jump = !entity.isNotColliding();
            if (jump) {
                entity.getEntity().getJumpHelper().setJumping();
            }
        }
    }


    @Override
    public void harvestAndPickup(BlockPos pos) {
        World world = entity.getEntityWorld();
        if (world.isAirBlock(pos)) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        List<ItemStack> drops = block.getDrops(world, pos, state, 0);
        net.minecraftforge.event.ForgeEventFactory.fireBlockHarvesting(drops, world, pos, state, 0, 1.0f, false, GeneralTools.getHarvester());
        SoundTools.playSound(world, block.getSoundType().getBreakSound(), pos.getX(), pos.getY(), pos.getZ(), 1.0f, 1.0f);
        entity.getEntityWorld().setBlockToAir(pos);
        giveDropsToMeeCreeps(drops);
    }


    @Override
    public void harvestAndDrop(BlockPos pos) {
        World world = entity.getEntityWorld();
        if (world.isAirBlock(pos)) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        List<ItemStack> drops = block.getDrops(world, pos, state, 0);
        net.minecraftforge.event.ForgeEventFactory.fireBlockHarvesting(drops, world, pos, state, 0, 1.0f, false, GeneralTools.getHarvester());
        SoundTools.playSound(world, block.getSoundType().getBreakSound(), pos.getX(), pos.getY(), pos.getZ(), 1.0f, 1.0f);
        entity.getEntityWorld().setBlockToAir(pos);
        for (ItemStack stack : drops) {
            entity.entityDropItem(stack, 0.0f);
        }
    }


    @Override
    public void pickup(EntityItem item) {
        ItemStack remaining = entity.addStack(item.getItem().copy());
        if (remaining.isEmpty()) {
            item.setDead();
        } else {
            item.setItem(remaining);
            needsToPutAway = true;
        }
    }

    @Override
    public boolean allowedToHarvest(IBlockState state, World world, BlockPos pos, EntityPlayer entityPlayer) {
        if (state.getBlock().getBlockHardness(state, world, pos) < 0) {
            return false;
        }
        if (!state.getBlock().canEntityDestroy(state, world, pos, entityPlayer)) {
            return false;
        }
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(world, pos, state, entityPlayer);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return false;
        }
        return state.getBlock().canHarvestBlock(world, pos, entityPlayer);
    }

    @Override
    public void done() {
        options.setStage(Stage.DONE);
        ServerActionManager.getManager().save();
    }

    // Indicate the task is done and that it is time to do the last task (putting back stuff etc)
    @Override
    public void taskIsDone() {
        options.setStage(Stage.TASK_IS_DONE);
        ServerActionManager.getManager().save();
    }

    @Override
    public void putStuffAway() {
        needsToPutAway = true;
    }

    @Override
    public void speedUp(int t) {
        waitABit = t;
    }

    @Override
    public void dropAndPutAwayLater(ItemStack stack) {
        EntityItem entityItem = entity.getEntity().entityDropItem(stack, 0.0f);
        itemsToPickup.add(entityItem);
        putStuffAway();
    }

    @Override
    public void giveToPlayerOrDrop() {
        EntityPlayerMP player = getPlayer();
        BlockPos position = entity.getPosition();
        if (player == null || position.distanceSq(player.getPosition()) > 2 * 2) {
            entity.dropInventory();
        } else {
            List<ItemStack> remaining = new ArrayList<>();
            for (ItemStack stack : entity.getInventory()) {
                if (!player.inventory.addItemStackToInventory(stack)) {
                    remaining.add(stack);
                }
            }
            player.openContainer.detectAndSendChanges();
            for (ItemStack stack : remaining) {
                entity.entityDropItem(stack, 0.0f);
            }
            entity.getInventory().clear();
        }

    }

    @Nullable
    protected EntityPlayerMP getPlayer() {
        return (EntityPlayerMP) options.getPlayer();
    }

    @Override
    public void findItemOnGroundOrInChest(Predicate<ItemStack> matcher, String message, int maxAmount) {
        List<BlockPos> meeCreepChests = findMeeCreepChests(worker.getSearchBox());
        if (!findItemOnGround(worker.getSearchBox(), matcher, this::pickup)) {
            if (!findInventoryContainingMost(worker.getSearchBox(), matcher, p -> fetchFromInventory(p, matcher, maxAmount))) {
                showMessage(message);
            }
        }
    }

    @Override
    public boolean findItemOnGroundOrInChest(Predicate<ItemStack> matcher, int maxAmount) {
        List<BlockPos> meeCreepChests = findMeeCreepChests(worker.getSearchBox());
        if (meeCreepChests.isEmpty()) {
            if (!findItemOnGround(worker.getSearchBox(), matcher, this::pickup)) {
                if (!findInventoryContainingMost(worker.getSearchBox(), matcher, p -> fetchFromInventory(p, matcher, maxAmount))) {
                    return false;
                }
            }
        } else {
            if (!findInventoryContainingMost(meeCreepChests, matcher, p -> fetchFromInventory(p, matcher, maxAmount))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Find all chests that have an item frame attached to them with an meecreep cube in them
     */
    private List<BlockPos> findMeeCreepChests(AxisAlignedBB box) {
        List<EntityItemFrame> frames = entity.getEntityWorld().getEntitiesWithinAABB(EntityItemFrame.class, box, input -> {
            if (!input.getDisplayedItem().isEmpty() && input.getDisplayedItem().getItem() instanceof CreepCubeItem) {
                BlockPos position = input.getHangingPosition();
                if (InventoryTools.isInventory(entity.getEntityWorld(), position)) {
                    return true;
                }
            }
            return false;
        });
        return frames.stream().map(EntityHanging::getHangingPosition).collect(Collectors.toList());
    }

    private boolean findMeeCreepBoxOnGround() {
        BlockPos position = entity.getEntity().getPosition();
        List<EntityItem> items = entity.getWorld().getEntitiesWithinAABB(EntityItem.class, worker.getSearchBox(),
                input -> !input.getItem().isEmpty() && input.getItem().getItem() instanceof CreepCubeItem);
        if (!items.isEmpty()) {
            items.sort((o1, o2) -> {
                double d1 = position.distanceSq(o1.posX, o1.posY, o1.posZ);
                double d2 = position.distanceSq(o2.posX, o2.posY, o2.posZ);
                return Double.compare(d1, d2);
            });
            EntityItem entityItem = items.get(0);
            navigateTo(entityItem, (pos) -> pickup(entityItem));
            return true;
        }
        return false;
    }

    /**
     * See if there is a specific item around. If so start navigating to it and return true
     */
    @Override
    public boolean findItemOnGround(AxisAlignedBB box, Predicate<ItemStack> matcher, Consumer<EntityItem> job) {
        BlockPos position = entity.getPosition();
        List<EntityItem> items = entity.getEntityWorld().getEntitiesWithinAABB(EntityItem.class, box, input -> matcher.test(input.getItem()));
        if (!items.isEmpty()) {
            items.sort((o1, o2) -> {
                double d1 = position.distanceSq(o1.posX, o1.posY, o1.posZ);
                double d2 = position.distanceSq(o2.posX, o2.posY, o2.posZ);
                return Double.compare(d1, d2);
            });
            EntityItem entityItem = items.get(0);
            navigateTo(entityItem, (pos) -> job.accept(entityItem));
            return true;
        }
        return false;
    }

    @Override
    public void putInventoryInChest(BlockPos pos) {
        TileEntity te = entity.getEntityWorld().getTileEntity(pos);
        IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP);
        for (ItemStack stack : entity.getInventory()) {
            if (!stack.isEmpty()) {
                ItemStack remaining = ItemHandlerHelper.insertItem(handler, stack, false);
                if (!remaining.isEmpty()) {
                    entity.entityDropItem(remaining, 0.0f);
                }
            }
        }
        entity.getInventory().clear();
    }

    private void fetchFromInventory(BlockPos pos, Predicate<ItemStack> matcher, int maxAmount) {
        materialChest = pos;
        TileEntity te = entity.getEntityWorld().getTileEntity(pos);
        IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP);
        for (int i = 0 ; i < handler.getSlots() ; i++) {
            if (maxAmount <= 0) {
                return;
            }
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                ItemStack extracted = handler.extractItem(i, Math.min(maxAmount, stack.getCount()), false);
                ItemStack remaining = entity.addStack(extracted);
                maxAmount -= extracted.getCount() - remaining.getCount();
                if (!remaining.isEmpty()) {
                    handler.insertItem(i, remaining, false);
                }
            }
        }
    }

    private float calculateScore(int countMatching, int countFreeForMatching) {
        return 2.0f * countMatching + countFreeForMatching;
    }

    protected boolean findInventoryContainingMost(List<BlockPos> inventoryList, Predicate<ItemStack> matcher, Consumer<BlockPos> job) {
        World world = entity.getEntityWorld();
        List<BlockPos> inventories = new ArrayList<>();
        Map<BlockPos, Float> countMatching = new HashMap<>();
        for (BlockPos pos : inventoryList) {
            IBlockState state = world.getBlockState(pos);
            TileEntity te = world.getTileEntity(pos);
            IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP);
            int cnt = 0;
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack stack = handler.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    if (matcher.test(stack)) {
                        cnt += stack.getCount();
                    }
                }
            }
            if (cnt > 0) {
                inventories.add(pos);
                countMatching.put(pos, (float) cnt);
            }
        }
        if (inventories.isEmpty()) {
            return false;
        } else {
            // Sort so that highest score goes first
            inventories.sort((p1, p2) -> Float.compare(countMatching.get(p2), countMatching.get(p1)));
            navigateTo(inventories.get(0), job);
            return true;
        }
    }

    protected boolean findInventoryContainingMost(AxisAlignedBB box, Predicate<ItemStack> matcher, Consumer<BlockPos> job) {
        World world = entity.getEntityWorld();
        List<BlockPos> inventories = new ArrayList<>();
        Map<BlockPos, Float> countMatching = new HashMap<>();
        GeneralTools.traverseBox(world, box,
                (pos, state) -> InventoryTools.isInventory(world, pos),
                (pos, state) -> {
                    TileEntity te = world.getTileEntity(pos);
                    IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP);
                    int cnt  = 0;
                    for (int i = 0 ; i < handler.getSlots() ; i++) {
                        ItemStack stack = handler.getStackInSlot(i);
                        if (!stack.isEmpty()) {
                            if (matcher.test(stack)) {
                                cnt += stack.getCount();
                            }
                        }
                    }
                    if (cnt > 0) {
                        inventories.add(pos);
                        countMatching.put(pos, (float) cnt);
                    }
                });
        if (inventories.isEmpty()) {
            return false;
        } else {
            // Sort so that highest score goes first
            inventories.sort((p1, p2) -> Float.compare(countMatching.get(p2), countMatching.get(p1)));
            navigateTo(inventories.get(0), job);
            return true;
        }
    }

    // Default implementation checks materialChest first and otherwise assumes the action was centered on the chest. Override if that's not applicable
    protected boolean findChestToPutItemsIn() {
        for (PreferedChest chest : worker.getPreferedChests()) {
            switch (chest) {
                case TARGET:
                    BlockPos pos = options.getTargetPos();
                    if (InventoryTools.isInventory(entity.getEntityWorld(), pos)) {
                        navigateTo(pos, this::putInventoryInChest);
                        return true;
                    }
                    break;
                case FIND_MATCHING_INVENTORY:
                    if (findSuitableInventory(worker.getSearchBox(), entity.getInventoryMatcher(), this::putInventoryInChest)) {
                        return true;
                    }
                    break;
                case LAST_CHEST:
                    if (materialChest != null) {
                        if (InventoryTools.isInventory(entity.getEntityWorld(), materialChest)) {
                            navigateTo(materialChest, this::putInventoryInChest);
                            return true;
                        }
                    }
                    break;
            }
        }

        return false;
    }

    protected boolean needToFindChest(boolean timeToWrapUp) {
        return needsToPutAway || (timeToWrapUp && entity.hasStuffInInventory());
    }

    @Override
    public boolean findSuitableInventory(AxisAlignedBB box, Predicate<ItemStack> matcher, Consumer<BlockPos> job) {
        World world = entity.getEntityWorld();
        List<BlockPos> inventories = new ArrayList<>();
        Map<BlockPos, Float> countMatching = new HashMap<>();
        GeneralTools.traverseBox(world, box,
                (pos, state) -> InventoryTools.isInventory(world, pos),
                (pos, state) -> {
                    TileEntity te = world.getTileEntity(pos);
                    IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP);
                    // @todo config?
                    if (handler.getSlots() > 8) {
                        int cnt = 0;
                        int free = 0;
                        for (int i = 0 ; i < handler.getSlots() ; i++) {
                            ItemStack stack = handler.getStackInSlot(i);
                            if (!stack.isEmpty()) {
                                if (matcher.test(stack)) {
                                    cnt += stack.getCount();
                                    free += handler.getSlotLimit(i) - stack.getCount();
                                }
                            } else {
                                free += handler.getSlotLimit(i);
                            }
                        }
                        if (cnt >= 0) {
                            inventories.add(pos);
                            countMatching.put(pos, calculateScore(cnt, free));
                        }
                    }
                });
        if (inventories.isEmpty()) {
            return false;
        } else {
            // Sort so that highest score goes first
            inventories.sort((p1, p2) -> Float.compare(countMatching.get(p2), countMatching.get(p1)));
            navigateTo(inventories.get(0), job);
            return true;
        }
    }

//    @Override
//    public List<BlockPos> findInventoriesWithMostSpace(AxisAlignedBB box) {
//        World world = entity.getEntityWorld();
//        List<BlockPos> inventories = new ArrayList<>();
//        Map<BlockPos, Float> countMatching = new HashMap<>();
//        GeneralTools.traverseBox(world, box,
//                (pos, state) -> InventoryTools.isInventory(world, pos),
//                (pos, state) -> {
//                    TileEntity te = world.getTileEntity(pos);
//                    IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP);
//                    // @todo config?
//                    if (handler.getSlots() > 8) {
//                        int free = 0;
//                        for (int i = 0 ; i < handler.getSlots() ; i++) {
//                            ItemStack stack = handler.getStackInSlot(i);
//                            if (stack.isEmpty()) {
//                                free += handler.getSlotLimit(i);
//                            }
//                        }
//                        inventories.add(pos);
//                        countMatching.put(pos, (float) free);
//                    }
//                });
//        // Sort so that highest score goes first
//        inventories.sort((p1, p2) -> Float.compare(countMatching.get(p2), countMatching.get(p1)));
//        return inventories;
//    }

    private boolean tryFindingItemsToPickup() {
        BlockPos position = entity.getPosition();
        List<EntityItem> items = itemsToPickup;
        if (!items.isEmpty()) {
            items.sort((o1, o2) -> {
                double d1 = position.distanceSq(o1.posX, o1.posY, o1.posZ);
                double d2 = position.distanceSq(o2.posX, o2.posY, o2.posZ);
                return Double.compare(d1, d2);
            });
            EntityItem entityItem = items.get(0);
            items.remove(0);
            navigateTo(entityItem, (p) -> pickup(entityItem));
            return true;
        }
        return false;
    }

    /**
     * Return true if the given postion is air, the postion below is not and the postion above is also air
     */
    boolean isStandable(BlockPos pos) {
        World world = entity.getWorld();
        return !world.isAirBlock(pos.down()) && world.isAirBlock(pos) && world.isAirBlock(pos.up());
    }

    /**
     * Find the nearest suitable spot to stand on at this x,z
     * Or null if there is no suitable position
     */
    private BlockPos findSuitableSpot(BlockPos pos) {
        if (isStandable(pos)) {
            return pos;
        }
        if (isStandable(pos.down())) {
            return pos.down();
        }
        if (isStandable(pos.up())) {
            return pos.up();
        }
        if (isStandable(pos.down(2))) {
            return pos.down(2);
        }
        return null;
    }

    /**
     * Calculate the best spot to move too for reaching the given position
     */
    @Override
    public BlockPos findBestNavigationSpot(BlockPos pos) {
        Entity ent = entity.getEntity();
        World world = entity.getWorld();

        BlockPos spotN = findSuitableSpot(pos.north());
        BlockPos spotS = findSuitableSpot(pos.south());
        BlockPos spotW = findSuitableSpot(pos.west());
        BlockPos spotE = findSuitableSpot(pos.east());

        double dn = spotN == null ? Double.MAX_VALUE : spotN.distanceSqToCenter(ent.posX, ent.posY, ent.posZ);
        double ds = spotS == null ? Double.MAX_VALUE : spotS.distanceSqToCenter(ent.posX, ent.posY, ent.posZ);
        double de = spotE == null ? Double.MAX_VALUE : spotE.distanceSqToCenter(ent.posX, ent.posY, ent.posZ);
        double dw = spotW == null ? Double.MAX_VALUE : spotW.distanceSqToCenter(ent.posX, ent.posY, ent.posZ);
        BlockPos p;
        if (dn <= ds && dn <= de && dn <= dw) {
            p = spotN;
        } else if (ds <= de && ds <= dw && ds <= dn) {
            p = spotS;
        } else if (de <= dn && de <= dw && de <= ds) {
            p = spotE;
        } else {
            p = spotW;
        }

        if (p == null) {
            // No suitable spot. Try standing on top
            p = findSuitableSpot(pos);
            // We also need to be able to jump up one spot
            if (p != null && !world.isAirBlock(p.up(2))) {
                p = null;
            }
        }

        return p;
    }

    public void readFromNBT(NBTTagCompound tag) {
        worker.readFromNBT(tag);
        if (tag.hasKey("materialChest")) {
            materialChest = BlockPos.fromLong(tag.getLong("materialChest"));
        }
    }

    public void writeToNBT(NBTTagCompound tag) {
        worker.writeToNBT(tag);
        if (materialChest != null) {
            tag.setLong("materialChest", materialChest.toLong());
        }
    }
}
