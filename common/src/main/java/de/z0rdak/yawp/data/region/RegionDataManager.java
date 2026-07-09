package de.z0rdak.yawp.data.region;

import de.z0rdak.yawp.api.core.RegionManager;
import de.z0rdak.yawp.constants.Constants;
import de.z0rdak.yawp.core.flag.BooleanFlag;
import de.z0rdak.yawp.core.flag.RegionFlag;
import de.z0rdak.yawp.core.region.DimensionalRegion;
import de.z0rdak.yawp.core.region.GlobalRegion;
import de.z0rdak.yawp.core.region.IMarkableRegion;
import de.z0rdak.yawp.core.region.IProtectedRegion;
import de.z0rdak.yawp.platform.Services;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static de.z0rdak.yawp.data.region.GlobalRegionData.GLOBAL_REGION_FILE_NAME;
import static de.z0rdak.yawp.handler.HandlerUtil.isServerSide;

public class RegionDataManager {

    public static final Logger LOGGER = LogManager.getLogger(Constants.MOD_ID.toUpperCase() + "-RegionDataManager");
    private static MinecraftServer serverInstance;
    /**
     * Implicit assumption: If savedLevelData contains a level, its also present in dimRegionStorage
     */
    private static LevelListData trackedLevelData;
    private static GlobalRegionData globalRegionData = new GlobalRegionData();
    private static final Map<ResourceLocation, LevelRegionData> levelRegionData = new  HashMap<>();
    
    // Save batching system - prevents disk thrashing
    private static long lastSaveTime = 0;
    private static final long SAVE_BATCH_INTERVAL_MS = 5000; // 5 second minimum between full saves
    private static boolean hasPendingSaves = false;
    private static final Set<ResourceLocation> pendingLevelSaves = new HashSet<>();

    public static LevelListData getTrackedLevelData() {
        return trackedLevelData;
    }

    public static Set<ResourceLocation> getLevels() {
        return new HashSet<>(trackedLevelData.getLevels());
    }

    public static boolean hasLevel(ResourceLocation level) {
        return trackedLevelData.doesTrack(level);
    }

    public static Set<String> getLevelNames() {
        return getLevels().stream().map(ResourceLocation::toString).collect(Collectors.toSet());
    }

    public static GlobalRegionData getGlobalRegionData() {
        return globalRegionData;
    }
    public static GlobalRegion getGlobalRegion() {
        return getGlobalRegionData().getGlobal();
    }

    private RegionDataManager() {
    }

    /**
     * Schedules a batched save operation. Multiple calls within SAVE_BATCH_INTERVAL_MS
     * will be coalesced into a single disk operation. This dramatically reduces I/O thrashing.
     */
    public static void save() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastSave = currentTime - lastSaveTime;
        
        // If enough time has passed, save immediately
        if (timeSinceLastSave >= SAVE_BATCH_INTERVAL_MS) {
            performSave();
            lastSaveTime = currentTime;
            hasPendingSaves = false;
            pendingLevelSaves.clear();
        } else {
            // Mark that we have pending saves to do later
            hasPendingSaves = true;
        }
    }
    
    /**
     * Force an immediate save. Only call for critical operations (shutdown, level unload).
     */
    public static void forceSave() {
        performSave();
        lastSaveTime = System.currentTimeMillis();
        hasPendingSaves = false;
        pendingLevelSaves.clear();
    }
    
    /**
     * Performs the actual save operation to disk.
     */
    private static void performSave() {
        saveTrackedLevelList();
        saveGlobalData();
        
        // Only save the levels that have pending changes
        if (pendingLevelSaves.isEmpty()) {
            // Fall back to saving all tracked levels if no specific ones marked
            saveTrackedLevels();
        } else {
            // Only save the dirty levels
            pendingLevelSaves.forEach(RegionDataManager::saveLevelData);
            pendingLevelSaves.clear();
        }
    }
    
    /**
     * Mark a specific level as having pending saves
     */
    private static void markLevelDirty(ResourceLocation levelRl) {
        pendingLevelSaves.add(levelRl);
    }

    public static LevelListData getSavedDims(@Nullable Supplier<LevelListData> defaultSupplier) {
        var overworld = serverInstance.overworld();
        Supplier<LevelListData> supplier = defaultSupplier == null ? LevelListData::new : defaultSupplier;
        DimensionDataStorage storage = overworld.getDataStorage();

        trackedLevelData = storage.computeIfAbsent(
                new SavedData.Factory<LevelListData>(supplier, LevelListData::load, DataFixTypes.SAVED_DATA_MAP_DATA),
                LevelListData.TYPE);
        return trackedLevelData;
    }

    public static void onServerStarting(MinecraftServer server) {
        LOGGER.debug("Initializing RegionDataManager");
        serverInstance = server;
        checkYawpDir(server);
    }

    private static void saveTrackedLevels(){
        serverInstance.getAllLevels().forEach(RegionDataManager::saveLevelData);
    }

    public static void save(MinecraftServer server, boolean flush, boolean force) {
        if (serverInstance == null) serverInstance = server;
        save();
    }

    public static void saveLevel(ResourceLocation rl) {
        if (!trackedLevelData.doesTrack(rl)) {
            return;
        }
        markLevelDirty(rl);
        save();
    }

    public static void saveLevel(ServerLevel level) {
        saveLevel(level.dimension().location());
    }

    // Duplicated because I want the logging info at a common place and not in the hooks of the mod-loaders
    public static void saveOnUnload(ServerLevel level) {
        var levelRl = level.dimension().location();
        if (!trackedLevelData.doesTrack(levelRl)) {
            return;
        }
        LOGGER.info(Component.translatableWithFallback(  "data.region.levels.save.unload", "Unloading level '%s'. Saving region data", level.dimension().location().toString()).getString());
        // Force immediate save on unload
        saveLevelData(level);
        forceSave();
    }

    private static void saveTrackedLevelList() {
        DimensionDataStorage dataStorage = serverInstance.overworld().getDataStorage();
        dataStorage.set(LevelListData.TYPE, trackedLevelData);
        trackedLevelData.setDirty();
    }

    public static void saveGlobalData() {
        DimensionDataStorage dataStorage = serverInstance.overworld().getDataStorage();
        dataStorage.set(Constants.MOD_ID + "/" + GLOBAL_REGION_FILE_NAME, globalRegionData);
        globalRegionData.setDirty();
    }

    private static void saveLevelData(ServerLevel level) {
        saveLevelData(level.dimension().location());
    }

    private static void saveLevelData(ResourceLocation levelRl) {
        if (trackedLevelData.doesTrack(levelRl)) {
            DimensionDataStorage storage = serverInstance.overworld().getDataStorage();
            LevelRegionData levelRegionData = RegionDataManager.levelRegionData.get(levelRl);
            LOGGER.debug(Component.translatableWithFallback("data.region.levels.save", "Saving region data for level '%s' (%s local region(s))", levelRl.toString(), levelRegionData.regionCount()).getString());
            storage.set(LevelRegionData.buildSavedDataType(levelRl), levelRegionData);
            levelRegionData.setDirty();
        }
    }

    public static void saveOnStop(MinecraftServer server) {
        if (serverInstance == null) serverInstance = server;
        LOGGER.info(Component.translatableWithFallback("data.region.levels.save.stopped", "Stopping server. Saving region data for all levels").getString());
        forceSave();
    }

    public static void saveOnUnload(MinecraftServer server, ServerLevel level) {
        if (trackedLevelData.doesTrack(level.dimension().location())) {
            LOGGER.info(Component.translatableWithFallback("data.region.levels.save.unload", "Unloading level '%s'. Saving region data", level.dimension().location().toString()).getString());
            saveLevelData(level);
            forceSave();
        }
    }

    public static void loadLevelListData(MinecraftServer server) {
        try {
            if (serverInstance == null)
                serverInstance = server;
            var dataStorage = server.overworld().getDataStorage();
            trackedLevelData = LevelListData.get(dataStorage, () -> {
                LOGGER.info(Component.translatableWithFallback("data.region.levels.load.missing", "Missing level list for region data (ignore on first startup). Initializing...").getString());
                return new LevelListData();
            });
            saveTrackedLevelList();
            LOGGER.info(Component.translatableWithFallback("data.region.levels.load.found", "Found region data for %s tracked level(s)", trackedLevelData.getLevels().size()).getString());

            globalRegionData = GlobalRegionData.get(dataStorage, () -> {
                LOGGER.info(Component.translatableWithFallback("data.region.global.missing", "Missing global region data (ignore on first startup). Initializing...").getString());
                return new GlobalRegionData();
            });
            saveGlobalData();
        } catch (NullPointerException npe) {
            LOGGER.error(Component.translatableWithFallback("data.region.level.local.load.failed", "Loading level region list failed!").getString(), npe);
        }
    }

    public static void worldLoad(MinecraftServer server, ServerLevel level) {
        try {
            if (serverInstance == null)
                serverInstance = server;
            var levelRl = level.dimension().location();
            var dataStorage = server.overworld().getDataStorage();
            // init level data
            if (trackedLevelData.doesTrack(levelRl)) {
                LevelRegionData newLevelRegionData = LevelRegionData.get(dataStorage, levelRl, () -> {
                    LOGGER.info(Component.translatableWithFallback("data.region.levels.load.missing", "Missing level list for region data (ignore on first startup). Initializing...", levelRl.toString()).getString());
                    return new LevelRegionData(levelRl);
                });
                LOGGER.info(Component.translatableWithFallback("data.region.level.local.load.success", "Loaded %s region(s) for '%s'", newLevelRegionData.regionCount(), levelRl.toString()).getString());
                levelRegionData.put(levelRl, newLevelRegionData);
                trackedLevelData.addTrackingFor(levelRl);
                // restoring region hierarchy
                LOGGER.debug(Component.translatableWithFallback("data.region.level.local.load.restore", "Restoring region hierarchy for '%s'", levelRl.toString()).getString());

                // restore dim <-> global hierarchy
                DimensionalRegion dimensionalRegion = newLevelRegionData.getDim();
                RegionManager.get().getGlobalRegion().addChild(dimensionalRegion);
                restoreHierarchy(newLevelRegionData, dimensionalRegion);

                // restore dim <-> local <-> local hierarchy
                newLevelRegionData.getLocals().forEach((regionName, region) -> {
                    restoreHierarchy(RegionDataManager.levelRegionData.get(levelRl), region);
                });
            }
            Services.YAWP_EVENT_DISPATCHER.post(level);
        } catch (NullPointerException npe) {
            LOGGER.error(Component.translatableWithFallback(  "data.region.levels.load.failure", "Loading regions failed!").getString(), npe);
        }
    }
    
    private static void restoreHierarchy(LevelRegionData levelRegionData, IProtectedRegion region) {
        ArrayList<String> childNames = new ArrayList<>(region.getChildrenNames());
        childNames.forEach(childName -> {
            if (!levelRegionData.hasLocal(childName)) {
                LOGGER.warn(Component.translatableWithFallback("data.region.level.local.load.restore.failed", "No region with name '%s' found in save data of '%s'! Your region data is most likely corrupt.", childName, levelRegionData.getId().toString()).getString());
            } else {
                IMarkableRegion child = levelRegionData.getLocal(childName);
                if (child != null) {
                    levelRegionData.getDim().removeChild(child);
                    region.addChild(child);
                }
            }
        });
    }

    private static void checkYawpDir(MinecraftServer server) {
        Path worldRootPath = server.getWorldPath(LevelResource.ROOT).normalize();
        Path dataDirPath = worldRootPath.resolve("data/" + Constants.MOD_ID);
        if (Files.notExists(dataDirPath)) {
            try {
                Files.createDirectories(dataDirPath);
                LOGGER.info(Component.translatableWithFallback("data.region.env.init", "Created region data directory '%s'", dataDirPath.toString()).getString());
            } catch (IOException e) {
                LOGGER.error(Component.translatableWithFallback("data.region.env.error", "Failed to create directory for region data: '%s'").getString(), e);
                throw new RuntimeException(e);
            }
        }
    }


    /**
     * Called periodically to process any pending batched saves.
     * Can be called from server tick or level save events.
     */
    public static void processPendingSaves() {
        if (hasPendingSaves) {
            long currentTime = System.currentTimeMillis();
            long timeSinceLastSave = currentTime - lastSaveTime;
            
            // If batch interval has elapsed, perform the save
            if (timeSinceLastSave >= SAVE_BATCH_INTERVAL_MS) {
                performSave();
                lastSaveTime = currentTime;
                hasPendingSaves = false;
                pendingLevelSaves.clear();
            }
        }
    }

    public static void initLevelDataOnLogin(Entity entity, Level level) {
        if (isServerSide(level) && entity instanceof Player) {
            var shouldCreateNewLevelData = Services.FEATURE_MANAGER.shouldCreateNewLevelData();
            if (shouldCreateNewLevelData) {
                RegionManager.get().trackLevel(level.dimension());
            }
        }
    }

    public static void initLevelDataOnChangeWorld(Player player, Level srcLvl, Level dstLvl) {
        if (isServerSide(srcLvl)) {
            var shouldCreateNewLevelData = Services.FEATURE_MANAGER.shouldCreateNewLevelData();
            if (shouldCreateNewLevelData) {
                RegionManager.get().trackLevel(dstLvl.dimension());
            }
        }
    }

    public static void removeTrackingFor(ResourceLocation rl){
        saveLevel(rl);
        trackedLevelData.removeTrackingFor(rl);
        levelRegionData.remove(rl);
        forceSave();
    }

    public static LevelRegionData addTrackingFor(ResourceLocation rl){
        if (trackedLevelData.doesTrack(rl) && levelRegionData.containsKey(rl)) {
            return levelRegionData.get(rl);
        }
        LevelRegionData newLevelRegion = new LevelRegionData(rl);
        trackedLevelData.addTrackingFor(rl);
        levelRegionData.put(rl, newLevelRegion);
        DimensionalRegion dimensionalRegion = newLevelRegion.getDim();
        // add default flags from config
        Set<String> defaultDimFlags = Services.REGION_CONFIG.getDefaultDimFlags();
        defaultDimFlags.stream()
                .map(RegionFlag::fromId)
                .forEach(flag -> dimensionalRegion.addFlag(new BooleanFlag(flag)));
        // set state from config
        dimensionalRegion.setIsActive(Services.REGION_CONFIG.shouldActivateNewDimRegion());
        // add as child of global
        RegionManager.get().getGlobalRegion().addChild(dimensionalRegion);
        LOGGER.info(Component.translatableWithFallback("data.region.levels.init", "Initializing region data for level '%s'", rl.toString()).getString());
        // Force immediate save on new tracking
        markLevelDirty(rl);
        forceSave();
        return newLevelRegion;
    }

    public static Optional<LevelRegionData> getLevelRegionData(ResourceLocation rl) {
        if (!trackedLevelData.doesTrack(rl)) {
            return Optional.empty();
        }
        return Optional.of(levelRegionData.get(rl));
    }

    public static Optional<LevelRegionData> getLevelRegionData(ResourceKey<Level> dim) {
        return getLevelRegionData(dim.location());
    }

    public static Collection<IMarkableRegion> getLocalsFor(ResourceKey<Level> dim) {
        var maybeRld = getLevelRegionData(dim.location());
        return maybeRld.isPresent() ? maybeRld.get().getLocalList() : new ArrayList<>();
    }

    public static void resetLevelData(ResourceLocation rl) {
        levelRegionData.remove(rl);
    }

    public static void resetLevelData(ResourceKey<Level> dim) {
        resetLevelData(dim.location());
    }
}
