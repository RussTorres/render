package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.trakem2.transform.CoordinateTransform;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.alignment.warp.AbstractWarpTransformBuilder;
import org.janelia.alignment.warp.ThinPlateSplineBuilder;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.janelia.render.client.parameter.WarpStackParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for copying tiles from one stack to another.
 *
 * @author Eric Trautman
 */
public class WarpTransformClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        TileSpecValidatorParameters tileSpecValidator = new TileSpecValidatorParameters();

        @ParametersDelegate
        WarpStackParameters warp = new WarpStackParameters();

        @ParametersDelegate
        TileClusterParameters tileCluster = new TileClusterParameters();

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--z",
                description = "Explicit z values for sections to be processed",
                variableArity = true) // e.g. --z 20.0 21.0 22.0
        public List<Double> zValues;

        Set<Double> getZValues() {
            return (zValues == null) ? Collections.emptySet() : new HashSet<>(zValues);
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.tileCluster.validate();
                parameters.warp.initDefaultValues(parameters.renderWeb);

                LOG.info("runClient: entry, parameters={}", parameters);

                final WarpTransformClient client = new WarpTransformClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private WarpTransformClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException {

        final SparkConf conf = new SparkConf().setAppName("WarpTransformClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);


        final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

        final List<SectionData> sectionDataList = sourceDataClient.getStackSectionData(parameters.warp.montageStack,
                                                                                       parameters.layerRange.minZ,
                                                                                       parameters.layerRange.maxZ,
                                                                                       parameters.getZValues());
        if (sectionDataList.size() == 0) {
            throw new IllegalArgumentException("montage stack does not contain any matching z values");
        }

        // batch layers by tile count in attempt to distribute work load as evenly as possible across cores
        final int numberOfCores = sparkContext.defaultParallelism();
        final LayerDistributor layerDistributor = new LayerDistributor(numberOfCores);
        final List<List<Double>> batchedZValues = layerDistributor.distribute(sectionDataList);

        final RenderDataClient targetDataClient = parameters.warp.getTargetDataClient();

        final StackMetaData montageStackMetaData = sourceDataClient.getStackMetaData(parameters.warp.montageStack);
        targetDataClient.setupDerivedStack(montageStackMetaData, parameters.warp.targetStack);

        final JavaRDD<List<Double>> rddZValues = sparkContext.parallelize(batchedZValues);

        final Function<List<Double>, Long> warpFunction = (Function<List<Double>, Long>) zBatch -> {

            LOG.info("warpFunction: entry");

            final TileSpecValidator tileSpecValidator = parameters.tileSpecValidator.getValidatorInstance();
            final RenderDataClient montageDataClient = parameters.renderWeb.getDataClient();
            final RenderDataClient alignDataClient = parameters.warp.getAlignDataClient();
            final RenderDataClient targetDataClient1 = parameters.warp.getTargetDataClient();
            final RenderDataClient matchDataClient =
                    parameters.tileCluster.getMatchDataClient(parameters.renderWeb.baseDataUrl,
                                                              parameters.renderWeb.owner);

            long processedTileCount = 0;

            for (int i = 0; i < zBatch.size(); i++) {

                final Double z = zBatch.get(i);

                LogUtilities.setupExecutorLog4j("z " + z);

                LOG.info("warpFunction: processing layer {} of {}, remaining layer z values are {}",
                         i + 1, zBatch.size(), zBatch.subList(i+1, zBatch.size()));

                final ResolvedTileSpecCollection montageTiles =
                        montageDataClient.getResolvedTiles(parameters.warp.montageStack, z);
                final ResolvedTileSpecCollection alignTiles =
                        alignDataClient.getResolvedTiles(parameters.warp.alignStack, z);

                if (parameters.warp.excludeTilesNotInBothStacks) {
                    montageTiles.removeDifferentTileSpecs(alignTiles.getTileIds());
                    alignTiles.removeDifferentTileSpecs(montageTiles.getTileIds());
                }

                if (parameters.tileCluster.isDefined()) {
                    buildTransformsForClusters(montageTiles,
                                               alignTiles,
                                               sectionDataList,
                                               matchDataClient,
                                               z,
                                               parameters.tileCluster);
                } else {
                    buildTransformForZ(montageTiles,
                                       alignTiles,
                                       z);
                }

                final int totalNumberOfTiles = montageTiles.getTileCount();
                if (tileSpecValidator != null) {
                    montageTiles.setTileSpecValidator(tileSpecValidator);
                    montageTiles.removeInvalidTileSpecs();
                }
                final int numberOfRemovedTiles = totalNumberOfTiles - montageTiles.getTileCount();

                LOG.info("warpFunction: added transform and derived bounding boxes for {} tiles, removed {} bad tiles",
                         totalNumberOfTiles, numberOfRemovedTiles);

                if (montageTiles.getTileCount() == 0) {
                    throw new IllegalStateException("no tiles left to save after filtering invalid tiles");
                }

                targetDataClient1.saveResolvedTiles(montageTiles, parameters.warp.targetStack, z);

                processedTileCount += montageTiles.getTileCount();
            }

            LOG.info("warpFunction: exit");

            return processedTileCount;
        };

        final JavaRDD<Long> rddTileCounts = rddZValues.map(warpFunction);

        final List<Long> tileCountList = rddTileCounts.collect();
        long total = 0;
        for (final Long tileCount : tileCountList) {
            total += tileCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: copied {} tiles", total);

        sparkContext.stop();

        if (parameters.warp.completeTargetStack) {
            targetDataClient.setStackState(parameters.warp.targetStack, StackMetaData.StackState.COMPLETE);
        }
    }

    private static void buildTransformForZ(final ResolvedTileSpecCollection montageTiles,
                                           final ResolvedTileSpecCollection alignTiles,
                                           final Double z)
            throws Exception {

        final String transformId = z + "_TPS";
        final TransformSpec warpTransformSpec = buildTransform(montageTiles.getTileSpecs(),
                                                               alignTiles.getTileSpecs(),
                                                               transformId);

        montageTiles.addTransformSpecToCollection(warpTransformSpec);
        montageTiles.addReferenceTransformToAllTiles(warpTransformSpec.getId(), false);

        LOG.info("buildTransformForZ: processed {} tiles for z {}",
                 montageTiles.getTileCount(), z);

    }

    private static void buildTransformsForClusters(final ResolvedTileSpecCollection montageTiles,
                                           final ResolvedTileSpecCollection alignTiles,
                                           final List<SectionData> sectionDataList,
                                           final RenderDataClient matchDataClient,
                                           final Double z,
                                           final TileClusterParameters tileClusterParameters)
            throws Exception {

        final List<CanvasMatches> matchesList = new ArrayList<>();
        for (final SectionData sectionData : sectionDataList) {
            if (z.equals(sectionData.getZ())) {
                matchesList.addAll(matchDataClient.getMatchesWithinGroup(sectionData.getSectionId()));
            }
        }

        if (matchesList.size() == 0) {
            throw new IllegalStateException("cannot determine clusters because no matches were found for z " + z);
        }

        final SortedConnectedCanvasIdClusters clusters = new SortedConnectedCanvasIdClusters(matchesList);
        final List<Set<String>> connectedTileSets = clusters.getSortedConnectedTileIdSets();

        LOG.info("buildTransformsForClusters: for z {}, found {} connected tile sets with sizes {}",
                 z, clusters.size(), clusters.getClusterSizes());

        final Set<String> largestCluster = connectedTileSets.get(connectedTileSets.size() - 1);
        final int maxSmallClusterSize = tileClusterParameters.getEffectiveMaxSmallClusterSize(largestCluster.size());

        final int tileCountBeforeRemoval = montageTiles.getTileCount();
        int smallClusterCount = 0;

        final List<Set<String>> largestConnectedTileSets = new ArrayList<>(connectedTileSets.size());
        for (final Set<String> clusterTileIds : connectedTileSets) {

            if (clusterTileIds.size() <= maxSmallClusterSize) {

                montageTiles.removeTileSpecs(clusterTileIds);
                smallClusterCount++;

            } else {

                final int beforeSize = clusterTileIds.size();

                clusterTileIds.removeIf(tileId -> ! montageTiles.hasTileSpec(tileId));

                if (beforeSize > clusterTileIds.size()) {
                    LOG.info("buildTransformsForClusters: removed {} large cluster tiles that have matches but are missing from the montage stack",
                             (beforeSize - clusterTileIds.size()));
                }

                largestConnectedTileSets.add(clusterTileIds);
            }
        }

        final int removedTileCount = tileCountBeforeRemoval - montageTiles.getTileCount();

        LOG.info("buildTransformsForClusters: removed {} tiles found in {} small ({}-tile or less) clusters",
                 removedTileCount, smallClusterCount, maxSmallClusterSize);

        // resolve the remaining montage tile spec transform references so that TPS calculations work
        montageTiles.resolveTileSpecs();

        final Collection<TileSpec> alignTileSpecs = alignTiles.getTileSpecs(); // note: resolves align transforms

        int clusterIndex = 0;
        for (final Set<String> clusterTileIds : largestConnectedTileSets) {

            final List<TileSpec> clusterTileSpecs = new ArrayList<>(montageTiles.getTileCount());
            final AtomicInteger alignCount = new AtomicInteger(0);
            clusterTileIds.forEach(tileId -> {
                final TileSpec tileSpec = montageTiles.getTileSpec(tileId);
                if (tileSpec != null) {
                    clusterTileSpecs.add(tileSpec);
                    if (alignTiles.hasTileSpec(tileId)) {
                        alignCount.getAndIncrement();
                    }
                }
            });

            if (clusterTileSpecs.size() == 0) {

                LOG.info("buildTransformsForClusters: skipped build for z {} cluster {} because none of the {} tiles were found in the montage stack, missing tile ids are: {}",
                         z, clusterIndex, clusterTileIds.size(), clusterTileIds);

            } else if (alignCount.get() < 3) {

                // Saalfeld said that there needs to be at least 3 aligned center points for TPS to work.
                // He later clarified that the points must also not be co-linear, but we're not going to check for that here.
                montageTiles.removeTileSpecs(clusterTileIds);

                LOG.info("buildTransformsForClusters: removed {} montage tiles and skipped build for z {} cluster {} because less than 3 of the tiles were found in the align stack, removed tile ids are: {}",
                         clusterTileIds.size(), z, clusterIndex, clusterTileIds);

            } else {
                
                final String transformId = z + "_cluster_" + clusterIndex + "_TPS";

                final TransformSpec warpTransformSpec = buildTransform(clusterTileSpecs,
                                                                       alignTileSpecs,
                                                                       transformId);
                montageTiles.addTransformSpecToCollection(warpTransformSpec);
                montageTiles.addReferenceTransformToTilesWithIds(warpTransformSpec.getId(), clusterTileIds, false);

                LOG.info("buildTransformsForClusters: processed {} tiles for z {} cluster {}",
                         clusterTileIds.size(), z, clusterIndex);

            }

            clusterIndex++;
        }

    }

    private static TransformSpec buildTransform(final Collection<TileSpec> montageTiles,
                                                final Collection<TileSpec> alignTiles,
                                                final String transformId)
            throws Exception {

        LOG.info("buildTransform: deriving transform {}", transformId);

        final AbstractWarpTransformBuilder< ? extends CoordinateTransform > transformBuilder =
                new ThinPlateSplineBuilder(montageTiles, alignTiles);

        final CoordinateTransform transform;

        transform = transformBuilder.call();

        LOG.info("buildTransform: completed {} transform derivation", transformId);

        return new LeafTransformSpec(transformId,
                                     null,
                                     transform.getClass().getName(),
                                     transform.toDataString());
    }

    private static final Logger LOG = LoggerFactory.getLogger(WarpTransformClient.class);
}
