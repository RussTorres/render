package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureMatchResult;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.render.client.parameter.FeatureRenderParameters;
import org.janelia.render.client.parameter.FeatureStorageParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.janelia.render.client.spark.cache.CachedCanvasFeatures;
import org.janelia.render.client.spark.cache.CanvasDataCache;
import org.janelia.render.client.spark.cache.CanvasFeatureListLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for generating and storing SIFT point matches for a specified set of canvas (e.g. tile) pairs.
 *
 * @author Eric Trautman
 */
public class SIFTPointMatchClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

        @ParametersDelegate
        FeatureRenderParameters featureRender = new FeatureRenderParameters();

        @ParametersDelegate
        FeatureRenderClipParameters featureRenderClip = new FeatureRenderClipParameters();

        @ParametersDelegate
        FeatureExtractionParameters featureExtraction = new FeatureExtractionParameters();

        @ParametersDelegate
        FeatureStorageParameters featureStorage = new FeatureStorageParameters();

        @ParametersDelegate
        MatchDerivationParameters matchDerivation = new MatchDerivationParameters();

        @Parameter(
                names = "--pairJson",
                description = "JSON file where tile pairs are stored (.json, .gz, or .zip)",
                required = true,
                order = 5)
        public List<String> pairJson;

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final SIFTPointMatchClient client = new SIFTPointMatchClient(parameters);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    private SIFTPointMatchClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
    }

    public void run() throws IOException, URISyntaxException {

        final SparkConf conf = new SparkConf().setAppName("SIFTPointMatchClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

        for (final String pairJsonFileName : parameters.pairJson) {
            generateMatchesForPairFile(sparkContext, pairJsonFileName);
        }

        sparkContext.stop();
    }

    private void generateMatchesForPairFile(final JavaSparkContext sparkContext,
                                            final String pairJsonFileName)
            throws IOException, URISyntaxException {

        LOG.info("generateMatchesForPairFile: pairJsonFileName is {}", pairJsonFileName);

        final RenderableCanvasIdPairs renderableCanvasIdPairs = RenderableCanvasIdPairs.load(pairJsonFileName);

        final MatchStorageFunction matchStorageFunction = new MatchStorageFunction(parameters.matchClient.baseDataUrl,
                                                                                   parameters.matchClient.owner,
                                                                                   parameters.matchClient.collection);
        generateMatchesForPairs(sparkContext,
                                renderableCanvasIdPairs,
                                parameters.matchClient.baseDataUrl,
                                parameters.featureRender,
                                parameters.featureRenderClip,
                                parameters.featureExtraction,
                                parameters.featureStorage,
                                parameters.matchDerivation,
                                matchStorageFunction);
    }

    static long generateMatchesForPairs(final JavaSparkContext sparkContext,
                                        final RenderableCanvasIdPairs renderableCanvasIdPairs,
                                        final String baseDataUrl,
                                        final FeatureRenderParameters featureRenderParameters,
                                        final FeatureRenderClipParameters featureRenderClipParameters,
                                        final FeatureExtractionParameters featureExtractionParameters,
                                        final FeatureStorageParameters featureStorageParameters,
                                        final MatchDerivationParameters matchDerivationParameters,
                                        final MatchStorageFunction matchStorageFunction)
            throws URISyntaxException {

        final CanvasRenderParametersUrlTemplate urlTemplateForRun =
                CanvasRenderParametersUrlTemplate.getTemplateForRun(
                        renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl),
                        featureRenderParameters.renderFullScaleWidth,
                        featureRenderParameters.renderFullScaleHeight,
                        featureRenderParameters.renderScale,
                        featureRenderParameters.renderWithFilter,
                        featureRenderParameters.renderFilterListName,
                        featureRenderParameters.renderWithoutMask);

        urlTemplateForRun.setClipInfo(featureRenderClipParameters.clipWidth, featureRenderClipParameters.clipHeight);

        final long cacheMaxKilobytes = featureStorageParameters.maxCacheGb * 1000000;
        final CanvasFeatureListLoader featureLoader =
                new CanvasFeatureListLoader(
                        urlTemplateForRun,
                        getCanvasFeatureExtractor(featureExtractionParameters, featureRenderParameters),
                        featureStorageParameters.getRootFeatureDirectory(),
                        featureStorageParameters.requireStoredFeatures);

        final double renderScale = featureRenderParameters.renderScale;

        // broadcast to all nodes
        final Broadcast<Long> broadcastCacheMaxKilobytes = sparkContext.broadcast(cacheMaxKilobytes);
        final Broadcast<CanvasFeatureListLoader> broadcastFeatureLoader = sparkContext.broadcast(featureLoader);
        final Broadcast<CanvasFeatureMatcher> broadcastFeatureMatcher =
                sparkContext.broadcast(getCanvasFeatureMatcher(matchDerivationParameters));

        final JavaRDD<OrderedCanvasIdPair> rddCanvasIdPairs =
                sparkContext.parallelize(renderableCanvasIdPairs.getNeighborPairs());

        final JavaRDD<CanvasMatches> rddMatches = rddCanvasIdPairs.mapPartitionsWithIndex(
                (Function2<Integer, Iterator<OrderedCanvasIdPair>, Iterator<CanvasMatches>>) (partitionIndex, pairIterator) -> {

                    LogUtilities.setupExecutorLog4j("partition " + partitionIndex);

                    final Logger log = LoggerFactory.getLogger(SIFTPointMatchClient.class);

                    final CanvasDataCache dataCache =
                            CanvasDataCache.getSharedCache(broadcastCacheMaxKilobytes.getValue(),
                                                           broadcastFeatureLoader.getValue());
                    final CanvasFeatureMatcher featureMatcher = broadcastFeatureMatcher.getValue();

                    final List<CanvasMatches> matchList = new ArrayList<>();
                    int pairCount = 0;

                    OrderedCanvasIdPair pair;
                    CanvasId p;
                    CanvasId q;
                    CachedCanvasFeatures pFeatures;
                    CachedCanvasFeatures qFeatures;
                    CanvasFeatureMatchResult matchResult;
                    while (pairIterator.hasNext()) {

                        pair = pairIterator.next();
                        pairCount++;

                        p = pair.getP();
                        q = pair.getQ();

                        pFeatures = dataCache.getCanvasFeatures(p);
                        qFeatures = dataCache.getCanvasFeatures(q);

                        log.info("derive matches between {} and {}", p, q);

                        matchResult = featureMatcher.deriveMatchResult(pFeatures.getFeatureList(),
                                                                       qFeatures.getFeatureList());

                        final double[] pClipOffsets = pFeatures.getClipOffsets();
                        final double[] qClipOffsets = qFeatures.getClipOffsets();

                        if (matchResult.foundMatches()) {
                            matchList.addAll(
                                    matchResult.getInlierMatchesList(p.getGroupId(),
                                                                     p.getId(),
                                                                     q.getGroupId(),
                                                                     q.getId(),
                                                                     renderScale,
                                                                     pClipOffsets,
                                                                     qClipOffsets));
                        }

                    }

                    log.info("derived matches for {} out of {} pairs, cache stats are {}",
                             matchList.size(), pairCount, dataCache.stats());

                    return matchList.iterator();
                },
                true
        );

        final JavaRDD<Integer> rddSavedMatchPairCounts = rddMatches.mapPartitionsWithIndex(matchStorageFunction, true);

        final int numPartitions = rddSavedMatchPairCounts.getNumPartitions();

        LOG.info("generateMatchesForPairs: {} partitions, debug string is: \n{}",
                 numPartitions, rddSavedMatchPairCounts.toDebugString());

        final List<Integer> matchPairCountList = rddSavedMatchPairCounts.collect();

        LOG.info("generateMatchesForPairs: collected stats");

        long totalSaved = 0;
        for (final Integer matchCount : matchPairCountList) {
            totalSaved += matchCount;
        }

        final long totalProcessed = renderableCanvasIdPairs.size();
        final int percentSaved = (int) ((totalSaved / (double) totalProcessed) * 100);

        LOG.info("generateMatchesForPairs: saved matches for {} out of {} pairs ({}%) on {} partitions",
                 totalSaved, totalProcessed, percentSaved, matchPairCountList.size());

        return totalSaved;
    }

    private static CanvasFeatureExtractor getCanvasFeatureExtractor(final FeatureExtractionParameters featureExtraction,
                                                                    final FeatureRenderParameters featureRender) {

        final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
        siftParameters.fdSize = featureExtraction.fdSize;
        siftParameters.steps = featureExtraction.steps;

        return new CanvasFeatureExtractor(siftParameters,
                                          featureExtraction.minScale,
                                          featureExtraction.maxScale,
                                          featureRender.fillWithNoise);
    }

    private static CanvasFeatureMatcher getCanvasFeatureMatcher(final MatchDerivationParameters matchParameters) {
        return new CanvasFeatureMatcher(matchParameters.matchRod,
                                        matchParameters.matchModelType,
                                        matchParameters.matchIterations,
                                        matchParameters.matchMaxEpsilon,
                                        matchParameters.matchMinInlierRatio,
                                        matchParameters.matchMinNumInliers,
                                        matchParameters.matchMaxTrust,
                                        matchParameters.matchMaxNumInliers,
                                        matchParameters.matchFilter);
    }

    private static final Logger LOG = LoggerFactory.getLogger(SIFTPointMatchClient.class);
}
