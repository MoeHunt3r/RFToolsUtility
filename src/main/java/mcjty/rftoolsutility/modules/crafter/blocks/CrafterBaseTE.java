package mcjty.rftoolsutility.modules.crafter.blocks;

import mcjty.lib.api.container.CapabilityContainerProvider;
import mcjty.lib.api.container.DefaultContainerProvider;
import mcjty.lib.api.infusable.CapabilityInfusable;
import mcjty.lib.api.infusable.DefaultInfusable;
import mcjty.lib.api.infusable.IInfusable;
import mcjty.lib.container.AutomationFilterItemHander;
import mcjty.lib.container.InventoryHelper;
import mcjty.lib.container.NoDirectionItemHander;
import mcjty.lib.container.UndoableItemHandler;
import mcjty.lib.gui.widgets.ImageChoiceLabel;
import mcjty.lib.tileentity.GenericEnergyStorage;
import mcjty.lib.tileentity.GenericTileEntity;
import mcjty.lib.typed.TypedMap;
import mcjty.lib.varia.ItemStackList;
import mcjty.lib.varia.Logging;
import mcjty.lib.varia.RedstoneMode;
import mcjty.rftoolsbase.api.compat.JEIRecipeAcceptor;
import mcjty.rftoolsutility.modules.crafter.CraftingRecipe;
import mcjty.rftoolsutility.modules.crafter.CrafterConfiguration;
import mcjty.rftoolsutility.modules.crafter.StorageFilterCache;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.INamedContainerProvider;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntityType;
import net.minecraft.util.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static mcjty.rftoolsutility.modules.crafter.CraftingRecipe.CraftMode.EXTC;
import static mcjty.rftoolsutility.modules.crafter.CraftingRecipe.CraftMode.INT;
import static mcjty.rftoolsutility.modules.crafter.blocks.CrafterContainer.CONTAINER_FACTORY;

public class CrafterBaseTE extends GenericTileEntity implements ITickableTileEntity, JEIRecipeAcceptor {
    public static final int SPEED_SLOW = 0;
    public static final int SPEED_FAST = 1;

    public static final String CMD_MODE = "crafter.setMode";
    public static final String CMD_RSMODE = "crafter.setRsMode";
    public static final String CMD_REMEMBER = "crafter.remember";
    public static final String CMD_FORGET = "crafter.forget";

    private NoDirectionItemHander items = createItemHandler();
    private LazyOptional<NoDirectionItemHander> itemHandler = LazyOptional.of(() -> items);
    private LazyOptional<AutomationFilterItemHander> automationItemHandler = LazyOptional.of(() -> new AutomationFilterItemHander(items));

    private LazyOptional<GenericEnergyStorage> energyHandler = LazyOptional.of(() -> new GenericEnergyStorage(this, true, CrafterConfiguration.MAXENERGY.get(), CrafterConfiguration.RECEIVEPERTICK.get()));
    private LazyOptional<INamedContainerProvider> screenHandler = LazyOptional.of(() -> new DefaultContainerProvider<CrafterContainer>("Crafter")
        .containerSupplier((windowId,player) -> new CrafterContainer(windowId, CrafterContainer.CONTAINER_FACTORY, getPos(), CrafterBaseTE.this))
        .itemHandler(itemHandler)
        .energyHandler(energyHandler));
    private LazyOptional<IInfusable> infusableHandler = LazyOptional.of(() -> new DefaultInfusable(CrafterBaseTE.this));

    private ItemStackList ghostSlots = ItemStackList.create(CrafterContainer.BUFFER_SIZE + CrafterContainer.BUFFEROUT_SIZE);

    private final CraftingRecipe[] recipes;

    private StorageFilterCache filterCache = null;

    private int speedMode = SPEED_SLOW;

    // If the crafter tries to craft something, but there's nothing it can make,
    // this gets set to true, preventing further ticking. It gets cleared whenever
    // any of its inventories or recipes change.
    public boolean noRecipesWork = false;

    private CraftingInventory workInventory = new CraftingInventory(new Container(null, -1) {
        @SuppressWarnings("NullableProblems")
        @Override
        public boolean canInteractWith(PlayerEntity var1) {
            return false;
        }
    }, 3, 3);

    public CrafterBaseTE(TileEntityType type, int supportedRecipes) {
        super(type);
        recipes = new CraftingRecipe[supportedRecipes];
        for (int i = 0; i < recipes.length; ++i) {
            recipes[i] = new CraftingRecipe();
        }
    }

    @Override
    protected boolean needsRedstoneMode() {
        return true;
    }


    public ItemStackList getGhostSlots() {
        return ghostSlots;
    }

    @Override
    public void setGridContents(List<ItemStack> stacks) {
        items.setStackInSlot(CrafterContainer.SLOT_CRAFTOUTPUT, stacks.get(0));
        for (int i = 1; i < stacks.size(); i++) {
            items.setStackInSlot(CrafterContainer.SLOT_CRAFTINPUT + i - 1, stacks.get(i));
        }
    }

    public void selectRecipe(int index) {
        CraftingRecipe recipe = recipes[index];
        items.setStackInSlot(CrafterContainer.SLOT_CRAFTOUTPUT, recipe.getResult());
        CraftingInventory inv = recipe.getInventory();
        int size = inv.getSizeInventory();
        for (int i = 0; i < size; ++i) {
            items.setStackInSlot(CrafterContainer.SLOT_CRAFTINPUT + i, inv.getStackInSlot(i));
        }
    }

    public int getSupportedRecipes() {
        return recipes.length;
    }

    public int getSpeedMode() {
        return speedMode;
    }

    public void setSpeedMode(int speedMode) {
        this.speedMode = speedMode;
        markDirtyClient();
    }

    public CraftingRecipe getRecipe(int index) {
        return recipes[index];
    }

    private void getFilterCache() {
        if (filterCache == null) {
            // @todo 1.14
//            filterCache = StorageFilterItem.getCache(inventoryHelper.getStackInSlot(CrafterContainer.SLOT_FILTER_MODULE));
        }
    }

    @Override
    public void read(CompoundNBT tagCompound) {
        super.read(tagCompound);
        CompoundNBT info = tagCompound.getCompound("Info");
        readGhostBufferFromNBT(info);
        readRecipesFromNBT(info);
        speedMode = info.getByte("speedMode");
    }

    private void readGhostBufferFromNBT(CompoundNBT tagCompound) {
        ListNBT bufferTagList = tagCompound.getList("GItems", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < bufferTagList.size(); i++) {
            ghostSlots.set(i, ItemStack.read(bufferTagList.getCompound(i)));
        }
    }

    private void readRecipesFromNBT(CompoundNBT tagCompound) {
        ListNBT recipeTagList = tagCompound.getList("Recipes", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < recipeTagList.size(); i++) {
            recipes[i].readFromNBT(recipeTagList.getCompound(i));
        }
    }

    @Override
    public CompoundNBT write(CompoundNBT tagCompound) {
        super.write(tagCompound);
        CompoundNBT info = getOrCreateInfo(tagCompound);
        writeGhostBufferToNBT(info);
        writeRecipesToNBT(info);
        info.putByte("speedMode", (byte) speedMode);
        return tagCompound;
    }

    private void writeGhostBufferToNBT(CompoundNBT tagCompound) {
        ListNBT bufferTagList = new ListNBT();
        for (ItemStack stack : ghostSlots) {
            CompoundNBT CompoundNBT = new CompoundNBT();
            if (!stack.isEmpty()) {
                stack.write(CompoundNBT);
            }
            bufferTagList.add(CompoundNBT);
        }
        tagCompound.put("GItems", bufferTagList);
    }

    private void writeRecipesToNBT(CompoundNBT tagCompound) {
        ListNBT recipeTagList = new ListNBT();
        for (CraftingRecipe recipe : recipes) {
            CompoundNBT CompoundNBT = new CompoundNBT();
            recipe.writeToNBT(CompoundNBT);
            recipeTagList.add(CompoundNBT);
        }
        tagCompound.put("Recipes", recipeTagList);
    }

    @Override
    public void tick() {
        if (!world.isRemote) {
            checkStateServer();
        }
    }

    protected void checkStateServer() {
        if (!isMachineEnabled() || noRecipesWork) {
            return;
        }

        energyHandler.ifPresent(h -> {
            // 0%: rf -> rf
            // 100%: rf -> rf / 2
            int defaultCost = CrafterConfiguration.rfPerOperation.get();
            int rf = infusableHandler.map(inf -> (int) (defaultCost * (2.0f - inf.getInfusedFactor()) / 2.0f)).orElse(defaultCost);

            int steps = speedMode == SPEED_FAST ? CrafterConfiguration.speedOperations.get() : 1;
            if (rf > 0) {
                steps = (int) Math.min(steps, h.getEnergy() / rf);
            }

            int i;
            for (i = 0; i < steps; ++i) {
                if (!craftOneCycle()) {
                    noRecipesWork = true;
                    break;
                }
            }
            rf *= i;
            if (rf > 0) {
                h.consumeEnergy(rf);
            }
        });
    }

    private boolean craftOneCycle() {
        boolean craftedAtLeastOneThing = false;

        for (CraftingRecipe craftingRecipe : recipes) {
            if (craftOneItemNew(craftingRecipe)) {
                craftedAtLeastOneThing = true;
            }
        }

        return craftedAtLeastOneThing;
    }

    private boolean craftOneItemNew(CraftingRecipe craftingRecipe) {
        IRecipe recipe = craftingRecipe.getCachedRecipe(world);
        if (recipe == null) {
            return false;
        }

        UndoableItemHandler undoHandler = new UndoableItemHandler(items);;

        // 'testCrafting' will setup the workInventory and return true if it matches
        if (!testAndConsume(craftingRecipe, undoHandler, true)) {
            undoHandler.restore();
            if (!testAndConsume(craftingRecipe, undoHandler, false)) {
                undoHandler.restore();
                return false;
            }
        }

//        ItemStack result = recipe.getCraftingResult(craftingRecipe.getInventory());
        ItemStack result = ItemStack.EMPTY;
        try {
            result = recipe.getCraftingResult(workInventory);
        } catch (RuntimeException e) {
            // Ignore this error for now to make sure we don't crash on bad recipes.
            Logging.logError("Problem with recipe!", e);
        }

        // Try to merge the output. If there is something that doesn't fit we undo everything.
        CraftingRecipe.CraftMode mode = craftingRecipe.getCraftMode();
        if (!result.isEmpty() && placeResult(mode, undoHandler, result)) {
            List<ItemStack> remaining = recipe.getRemainingItems(workInventory);
            CraftingRecipe.CraftMode remainingMode = mode == EXTC ? INT : mode;
            for (ItemStack s : remaining) {
                if (!s.isEmpty()) {
                    if (!placeResult(remainingMode, undoHandler, s)) {
                        // Not enough room.
                        undoHandler.restore();
                        return false;
                    }
                }
            }
            return true;
        } else {
            // We don't have place. Undo the operation.
            undoHandler.restore();
            return false;
        }
    }

    private static boolean match(ItemStack target, ItemStack input, boolean strictDamage) {
        if ((input.isEmpty() && !target.isEmpty())
                || (!input.isEmpty() && target.isEmpty())) {
            return false;
        }
        if (strictDamage) {
//            return OreDictionary.itemMatches(target, input, false);
            // @todo 1.14   tags/oredict
            return target.getItem() == input.getItem() && target.getDamage() == input.getDamage();
        } else {
            return target.getItem() == input.getItem();
        }
    }

    private boolean testAndConsume(CraftingRecipe craftingRecipe, UndoableItemHandler undoHandler, boolean strictDamage) {
        int keep = craftingRecipe.isKeepOne() ? 1 : 0;
        for (int i = 0; i < workInventory.getSizeInventory(); i++) {
            workInventory.setInventorySlotContents(i, ItemStack.EMPTY);
        }

        for (CraftingRecipe.CompressedIngredient ingredient : craftingRecipe.getCompressedIngredients()) {
            ItemStack stack = ingredient.getStack();
            int[] distribution = new int[9];
            System.arraycopy(ingredient.getGridDistribution(), 0, distribution, 0, distribution.length);

            if (!stack.isEmpty()) {
                AtomicInteger count = new AtomicInteger(stack.getCount());
                for (int j = 0; j < CrafterContainer.BUFFER_SIZE; j++) {
                    int slotIdx = CrafterContainer.SLOT_BUFFER + j;
                    ItemStack input = undoHandler.getStackInSlot(slotIdx);
                    if (!input.isEmpty() && input.getCount() > keep) {
                        if (match(stack, input, strictDamage)) {
                            for (int i = 0; i < distribution.length; i++) {
                                if (distribution[i] > 0) {
                                    int amount = Math.min(input.getCount() - keep, distribution[i]);
                                    distribution[i] -= amount;
                                    count.addAndGet(-amount);
                                    undoHandler.remember(slotIdx);
                                    ItemStack copy = input.copy();
                                    input.shrink(amount);
                                    if (workInventory.getStackInSlot(i).isEmpty()) {
                                        copy.setCount(amount);
                                        workInventory.setInventorySlotContents(i, copy);
                                    } else {
                                        workInventory.getStackInSlot(i).grow(amount);
                                    }
                                }
                            }
                        }
                    }
                    if (count.get() == 0) {
                        break;
                    }
                }
                if (count.get() > 0) {
                    return false;   // Couldn't find all items.
                }
            }
        }

        IRecipe recipe = craftingRecipe.getCachedRecipe(world);
        return recipe.matches(workInventory, world);
    }

/*
    private boolean testAndConsumeCraftingItems(CraftingRecipe craftingRecipe, boolean strictDamage) {
        int keep = craftingRecipe.isKeepOne() ? 1 : 0;
        CraftingInventory inventory = craftingRecipe.getInventory();
        for (int i = 0 ; i < workInventory.getSizeInventory() ; i++) {
            workInventory.setInventorySlotContents(i, ItemStack.EMPTY);
        }

        for (CraftingRecipe.CompressedIngredient ingredient : craftingRecipe.getCompressedIngredients()) {
            ItemStack stack = ingredient.getStack();
            if (!stack.isEmpty()) {
                AtomicInteger count = new AtomicInteger(stack.getCount());
                for (int j = 0 ; j < CrafterContainer.BUFFER_SIZE ; j++) {
                    int slotIdx = CrafterContainer.SLOT_BUFFER + j;
                    itemHandler.ifPresent(bufferHandler -> {
                        ItemStack input = bufferHandler.getStackInSlot(slotIdx);
                        if (!input.isEmpty() && input.getCount() > keep) {
                            if (match(stack, input, strictDamage)) {
                                int[] distribution = ingredient.getGridDistribution();
                                for (int i = 0; i < distribution.length ; i++) {
                                    workInventory.setInventorySlotContents(finalI, input.copy());
                                }
                                int ss = count.get();
                                if (input.getCount() - ss < keep) {
                                    ss = input.getCount() - keep;
                                }
                                count.addAndGet(-ss);
                                input.split(ss);        // This consumes the items
                                if (input.isEmpty()) {
                                    bufferHandler.setStackInSlot(slotIdx, ItemStack.EMPTY);
                                }
                            }
                        }
                    });
                    if (count.get() == 0) {
                        break;
                    }
                }
                if (count.get() > 0) {
                    return false;   // Couldn't find all items.
                }
            }
        }

        IRecipe recipe = craftingRecipe.getCachedRecipe(world);
        return recipe.matches(workInventory, world);
    }
 */

    private boolean placeResult(CraftingRecipe.CraftMode mode, IItemHandlerModifiable undoHandler, ItemStack result) {
        int start;
        int stop;
        if (mode == INT) {
            start = CrafterContainer.SLOT_BUFFER;
            stop = CrafterContainer.SLOT_BUFFER + CrafterContainer.BUFFER_SIZE;
        } else {
            // EXT and EXTC are handled the same here
            start = CrafterContainer.SLOT_BUFFEROUT;
            stop = CrafterContainer.SLOT_BUFFEROUT + CrafterContainer.BUFFEROUT_SIZE;
        }
        ItemStack remaining = InventoryHelper.insertItemRanged(undoHandler, result, start, stop, true);
        if (remaining.isEmpty()) {
            InventoryHelper.insertItemRanged(undoHandler, result, start, stop, false);
            return true;
        }
        return false;
    }

    private void rememberItems() {
        for (int i = 0; i < ghostSlots.size(); i++) {
            int slotIdx;
            if (i < CrafterContainer.BUFFER_SIZE) {
                slotIdx = i + CrafterContainer.SLOT_BUFFER;
            } else {
                slotIdx = i + CrafterContainer.SLOT_BUFFEROUT - CrafterContainer.BUFFER_SIZE;
            }
            if (!items.getStackInSlot(slotIdx).isEmpty()) {
                ItemStack stack = items.getStackInSlot(slotIdx).copy();
                stack.setCount(1);
                ghostSlots.set(i, stack);
            }
        }
        noRecipesWork = false;
        markDirtyClient();
    }

    private void forgetItems() {
        for (int i = 0; i < ghostSlots.size(); i++) {
            ghostSlots.set(i, ItemStack.EMPTY);
        }
        noRecipesWork = false;
        markDirtyClient();
    }

    @Override
    public boolean execute(PlayerEntity playerMP, String command, TypedMap params) {
        boolean rc = super.execute(playerMP, command, params);
        if (rc) {
            return true;
        }
        if (CMD_RSMODE.equals(command)) {
            setRSMode(RedstoneMode.values()[params.get(ImageChoiceLabel.PARAM_CHOICE_IDX)]);
            return true;
        } else if (CMD_MODE.equals(command)) {
            setSpeedMode(params.get(ImageChoiceLabel.PARAM_CHOICE_IDX));
            return true;
        } else if (CMD_REMEMBER.equals(command)) {
            rememberItems();
            return true;
        } else if (CMD_FORGET.equals(command)) {
            forgetItems();
            return true;
        }
        return false;
    }

    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
        if (slot >= CrafterContainer.SLOT_CRAFTINPUT && slot <= CrafterContainer.SLOT_CRAFTOUTPUT) {
            return false;
        }
        if (slot >= CrafterContainer.SLOT_BUFFER && slot < CrafterContainer.SLOT_BUFFEROUT) {
            ItemStack ghostSlot = ghostSlots.get(slot - CrafterContainer.SLOT_BUFFER);
            if (!ghostSlot.isEmpty()) {
                if (!ghostSlot.isItemEqual(stack)) {
                    return false;
                }
            }
            ItemStack filterModule = items.getStackInSlot(CrafterContainer.SLOT_FILTER_MODULE);
            if (!filterModule.isEmpty()) {
                getFilterCache();
                if (filterCache != null) {
                    return filterCache.match(stack);
                }
            }
        } else if (slot >= CrafterContainer.SLOT_BUFFEROUT && slot < CrafterContainer.SLOT_FILTER_MODULE) {
            ItemStack ghostSlot = ghostSlots.get(slot - CrafterContainer.SLOT_BUFFEROUT + CrafterContainer.BUFFER_SIZE);
            if (!ghostSlot.isEmpty()) {
                if (!ghostSlot.isItemEqual(stack)) {
                    return false;
                }
            }
        }
        return true;
    }



    private NoDirectionItemHander createItemHandler() {
        return new NoDirectionItemHander(CrafterBaseTE.this, CONTAINER_FACTORY) {

            @Override
            protected void onUpdate(int index) {
                super.onUpdate(index);
                noRecipesWork = false;
                if (index == CrafterContainer.SLOT_FILTER_MODULE) {
                    filterCache = null;
                }
            }

            @Override
            public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
                return isItemValidForSlot(slot, stack);
            }
        };
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction facing) {
        if (cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return automationItemHandler.cast();
        }
        if (cap == CapabilityEnergy.ENERGY) {
            return energyHandler.cast();
        }
        if (cap == CapabilityContainerProvider.CONTAINER_PROVIDER_CAPABILITY) {
            return screenHandler.cast();
        }
        if (cap == CapabilityInfusable.INFUSABLE_CAPABILITY) {
            return infusableHandler.cast();
        }
        return super.getCapability(cap, facing);
    }


}
