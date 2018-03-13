/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment.transform;

import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import mpicbg.models.AffineModel2D;
import mpicbg.models.PointMatch;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasFeatureMatcherTest;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.CanvasNameToPointsMap;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.HierarchicalStack;
import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import junit.framework.Assert;
import net.imglib2.RealPoint;

/**
 * Tests the {@link ConsensusWarpFieldBuilder} class.
 *
 * @author Eric Trautman
 */
public class ConsensusWarpFieldBuilderTest {

    @Test
    public void testBuild() throws Exception {

        final List<List<PointMatch>> consensusSets = CanvasFeatureMatcherTest.findAndValidateFoldTestConsensusSets();

        final int consensusGridResolution = 16;
        final ConsensusWarpFieldBuilder consensusWarpFieldBuilder =
                new ConsensusWarpFieldBuilder(963.0, 1024.0, consensusGridResolution, consensusGridResolution);

        int setNumber = 0;
        for (final List<PointMatch> pointMatchList : consensusSets) {

            final List<RealPoint> pointList =
                    pointMatchList.stream()
                            .map(pointMatch -> new RealPoint(pointMatch.getP1().getL()))
                            .collect(Collectors.toList());

            final AffineModel2D alignmentModel = new AffineModel2D();
            alignmentModel.set(1.0, 0.0, 0.0, 1.0, (setNumber * 100), (setNumber * 100));
            consensusWarpFieldBuilder.addConsensusSetData(alignmentModel, pointList);
            setNumber++;
        }

        LOG.info("Consensus Grid is ...\n\n{}\n", consensusWarpFieldBuilder.toIndexGridString());

        Assert.assertEquals("invalid number of consensus sets left in grid after nearest neighbor analysis",
                            3, consensusWarpFieldBuilder.getNumberOfConsensusSetsInGrid());


        final AffineWarpFieldTransform affineWarpFieldTransform =
                new AffineWarpFieldTransform(new double[] {0.0, 0.0},
                                             consensusWarpFieldBuilder.build());

        LOG.info("Warp Field Data String is {}", affineWarpFieldTransform.toDataString());
    }

    @Test
    public void testMerge() throws Exception {

        final int gridRes = 10;
        final double cellSize = 1000.0;
        final ConsensusWarpFieldBuilder builder1 = new ConsensusWarpFieldBuilder(cellSize, cellSize, gridRes, gridRes);
        final ConsensusWarpFieldBuilder builder2 = new ConsensusWarpFieldBuilder(cellSize, cellSize, gridRes, gridRes);

        final List<RealPoint> set1A = new ArrayList<>();
        final List<RealPoint> set1B = new ArrayList<>();
        final List<RealPoint> set1C = new ArrayList<>();

        int column = 0;
        for (int row = 0; row < 10; row++) {
            if (row < 5) {
                addPoint(column * 100, row * 100, set1A);
                addPoint((column + 1) * 100, row * 100, set1B);
                column++;
            } else {
                addPoint(column * 100, row * 100, set1C);
                addPoint((column - 1) * 100, row * 100, set1A);
                column--;
            }
        }

        for (int c = 6; c < 10; c++) {
            addPoint(c * 100, 400, set1B);
            addPoint(c * 100, 500, set1C);
        }

        builder1.addConsensusSetData(new AffineModel2D(), set1A);
        builder1.addConsensusSetData(new AffineModel2D(), set1B);
        builder1.addConsensusSetData(new AffineModel2D(), set1C);

        LOG.info("Grid 1 is ...\n\n{}\n", builder1.toIndexGridString());

        Assert.assertEquals("invalid number of consensus sets left in grid after nearest neighbor analysis",
                            3, builder1.getNumberOfConsensusSetsInGrid());

        final List<RealPoint> set2A = new ArrayList<>();
        final List<RealPoint> set2B = new ArrayList<>();

        for (column = 0; column < 10; column++) {
            for (int row = 0; row < 10; row++) {
                if (column < 4) {
                    addPoint(column * 100, row * 100, set2A);
                } else {
                    addPoint(column * 100, row * 100, set2B);
                }
            }
        }

        builder2.addConsensusSetData(new AffineModel2D(), set2A);
        builder2.addConsensusSetData(new AffineModel2D(), set2B);

        LOG.info("Grid 2 is ...\n\n{}\n", builder2.toIndexGridString());

        Assert.assertEquals("invalid number of consensus sets left in grid after nearest neighbor analysis",
                            2, builder2.getNumberOfConsensusSetsInGrid());

        final ConsensusWarpFieldBuilder mergedBuilder = builder1.mergeBuilders(builder2);

        LOG.info("Merged Grid is ...\n\n{}\n", mergedBuilder.toIndexGridString());

        Assert.assertEquals("invalid number of consensus sets left in grid after merge",
                            6, mergedBuilder.getNumberOfConsensusSetsInGrid());
    }

    private void addPoint(final int x,
                          final int y,
                          final List<RealPoint> pointList) {
        pointList.add(new RealPoint(x, y));
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        try {
            showBox4();
            showBox7();
            saveBoxes4And7();
            showWarpedConsensusStack();
            showFullScaleWarpedConsensusLayer();
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    private static double[] getRelativeAffineData(final HierarchicalStack tierStack,
                                                  final double[] alignedAffineData,
                                                  final double alignedStackMinX,
                                                  final double alignedStackMinY) {
        final AffineModel2D lastTransform = new AffineModel2D();
        lastTransform.set(alignedAffineData[0], alignedAffineData[1], alignedAffineData[2],
                          alignedAffineData[3], alignedAffineData[4], alignedAffineData[5]);
        final AffineModel2D relativeAlignedModel = tierStack.getFullScaleRelativeModel(lastTransform,
                                                                                       alignedStackMinX,
                                                                                       alignedStackMinY);
        final double[] affineData = new double[6];
        relativeAlignedModel.toArray(affineData);
        return affineData;
    }

    private static void saveBoxes4And7() throws Exception {

        final int consensusRowCount = 10;
        final int consensusColumnCount = 10;

        final HierarchicalStack tier4Stack = HierarchicalStack.fromJson(TIER_STACK);
        final Bounds tier4StackBounds = tier4Stack.getFullScaleBounds();
        final ConsensusWarpFieldBuilder builder =
                new ConsensusWarpFieldBuilder(tier4StackBounds.getDeltaX(),
                                              tier4StackBounds.getDeltaY(),
                                              consensusRowCount,
                                              consensusColumnCount);
        final String groupId = "2214.0";
        final List<CanvasMatches> canvasMatchesList = CanvasMatches.fromJsonArray(MATCHES_OUTSIDE_2214_A);

        final CanvasNameToPointsMap nameToPointsForGroup = new CanvasNameToPointsMap(1 / tier4Stack.getScale());
        nameToPointsForGroup.addPointsForGroup(groupId, canvasMatchesList);

        final Bounds alignedStackBounds = new Bounds(-4.0, -38.0, 2213.0, 1106.0, 1109.0, 2215.0);
        final ResolvedTileSpecCollection alignedTiles = ResolvedTileSpecCollection.fromJson(ALIGNED_TILES);

        for (final String tileId : nameToPointsForGroup.getNames()) {

            final TileSpec tileSpecForZ = alignedTiles.getTileSpec(tileId);
            final AffineModel2D lastTransform = (AffineModel2D) tileSpecForZ.getLastTransform().getNewInstance();
            final AffineModel2D relativeAlignedModel =
                    tier4Stack.getFullScaleRelativeModel(lastTransform,
                                                         alignedStackBounds.getMinX(),
                                                         alignedStackBounds.getMinY());

            builder.addConsensusSetData(relativeAlignedModel, nameToPointsForGroup.getPoints(tileId));
        }

        final HierarchicalStack tier7Stack = HierarchicalStack.fromJson(TIER_7_STACK);

        AffineWarpField warpField = new AffineWarpField(tier7Stack.getTotalTierFullScaleWidth(),
                                                        tier7Stack.getTotalTierFullScaleHeight(),
                                                        tier7Stack.getTotalTierRowCount(),
                                                        tier7Stack.getTotalTierColumnCount(),
                                                        AffineWarpField.getDefaultInterpolatorFactory());

        warpField.set(1, 0, getRelativeAffineData(tier7Stack,
                                                  new double[] {0.999138172531,  0.010158452905, -0.004500172025,
                                                                0.993894967357,  6.408008687679,  0.000000000000},
                                                  0, 0));
        // 1,1 use consensus set data
        warpField.set(1, 2, getRelativeAffineData(tier7Stack,
                                                  new double[] {0.999763084203, -0.019854609259,  0.021505047005,
                                                                0.990914796204, 61.884915579413,  0.000000000000},
                                                  0, 0));
        warpField.set(2, 0, getRelativeAffineData(tier7Stack,
                                                  new double[] {0.993778062963,  0.004213636916,  0.001767546096,
                                                                0.998768975100,  3.687394053087,  0.000000000000},
                                                  0, 0));
        warpField.set(2, 1, getRelativeAffineData(tier7Stack,
                                                  new double[] {0.992857769693,  0.002892510697,  0.001641161429,
                                                                0.990484528910,  0.000000000000,  3.561567228159},
                                                  0, 0));
        warpField.set(2, 2, getRelativeAffineData(tier7Stack,
                                                  new double[] {0.995809759606, -0.004875265948,  0.003182766578,
                                                                0.988558542063,  0.000000000000,  9.774319840562},
                                                  0, 0));


        final AffineWarpField hiResField = warpField.getHighResolutionCopy(consensusRowCount,
                                                                           consensusColumnCount);
        final AffineWarpField consensusField = builder.build();
        final int startHiResRow = tier4Stack.getTierRow() * consensusRowCount;
        final int startHiResColumn = tier4Stack.getTierColumn() * consensusColumnCount;
        for (int row = 0; row < consensusRowCount; row++) {
            for (int column = 0; column < consensusColumnCount; column++) {
                hiResField.set((startHiResRow + row),
                               (startHiResColumn + column),
                               consensusField.get(row, column));
            }
        }

        warpField = hiResField;

        final Bounds parentBounds = new Bounds(36801.0, 38528.0, 2213.0, 47992.0, 49489.0, 2215.0);
        final double[] locationOffsets = new double[] { parentBounds.getMinX(), parentBounds.getMinY() };

        final AffineWarpFieldTransform warpFieldTransform = new AffineWarpFieldTransform(locationOffsets, warpField);


        final String warpFieldTransformId = "2214.0_AFFINE_WARP_FIELD";
        final TransformSpec warpTransformSpec = new LeafTransformSpec(warpFieldTransformId,
                                                                      null,
                                                                      AffineWarpFieldTransform.class.getName(),
                                                                      warpFieldTransform.toDataString());




        final RenderParameters box4Parameters =
                RenderParameters.parseJson(new File("/Users/trautmane/projects/git/render/render-app/src/test/resources/warp-field-test/box4.json"));
        final RenderParameters box7Parameters =
                RenderParameters.parseJson(new File("/Users/trautmane/projects/git/render/render-app/src/test/resources/warp-field-test/box7.json"));
        final List<TileSpec> box4And7Tiles = box7Parameters.getTileSpecs();
        box4And7Tiles.addAll(box4Parameters.getTileSpecs());

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (final TileSpec tileSpec : box4And7Tiles) {
            tileSpec.addTransformSpecs(Collections.singletonList(warpTransformSpec));
            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true, false);
            minX = Math.min(minX, tileSpec.getMinX());
            minY = Math.min(minY, tileSpec.getMinY());
            maxX = Math.max(maxX, tileSpec.getMaxX());
            maxY = Math.max(maxY, tileSpec.getMaxY());
        }

        final int margin = 10;
        final double x = minX - margin;
        final double y = minY - margin;
        final int width = (int) (maxX - minX + 1) + (margin * 2);
        final int height = (int) (maxY - minY + 1) + (margin * 2);

        final RenderParameters renderParameters = new RenderParameters(null, x, y, width, height, tier7Stack.getScale());
        box4And7Tiles.forEach(renderParameters::addTileSpec);

        final BufferedImage targetImage = renderParameters.openTargetImage();
        ArgbRenderer.render(renderParameters, targetImage, ImageProcessorCache.DISABLED_CACHE);
        final ImagePlus ipWarpedBox4And7 = new ImagePlus("", targetImage);
        final FileSaver fileSaver = new FileSaver(ipWarpedBox4And7);
        fileSaver.saveAsJpeg("/Users/trautmane/Desktop/ipWarpedBox4And7.jpg");

        //ipWarpedBox4And7.show();
    }

    private static void showBox7() throws Exception {

        final HierarchicalStack tierStack = HierarchicalStack.fromJson(TIER_7_STACK);

        final AffineWarpField warpField = new AffineWarpField(tierStack.getTotalTierFullScaleWidth(),
                                                              tierStack.getTotalTierFullScaleHeight(),
                                                              tierStack.getTotalTierRowCount(),
                                                              tierStack.getTotalTierColumnCount(),
                                                              AffineWarpField.getDefaultInterpolatorFactory());

        warpField.set(1, 0, getRelativeAffineData(tierStack,
                                                  new double[] {0.999138172531,  0.010158452905, -0.004500172025,
                                                                0.993894967357,  6.408008687679,  0.000000000000},
                                                  0, 0));
        // 1,1 use consensus set 0 alignment
        warpField.set(1, 1, getRelativeAffineData(tierStack,
                                                  new double[] {0.992226610716,  0.020787884186, -0.017282849775,
                                                                0.995925760553, 12.257180422620, 88.976148549632},
                                                  0, 0));
        warpField.set(1, 2, getRelativeAffineData(tierStack,
                                                  new double[] {0.999763084203, -0.019854609259,  0.021505047005,
                                                                0.990914796204, 61.884915579413,  0.000000000000},
                                                  0, 0));
        warpField.set(2, 0, getRelativeAffineData(tierStack,
                                                  new double[] {0.993778062963,  0.004213636916,  0.001767546096,
                                                                0.998768975100,  3.687394053087,  0.000000000000},
                                                  0, 0));
        warpField.set(2, 1, getRelativeAffineData(tierStack,
                                                  new double[] {0.992857769693,  0.002892510697,  0.001641161429,
                                                                0.990484528910,  0.000000000000,  3.561567228159},
                                                  0, 0));
        warpField.set(2, 2, getRelativeAffineData(tierStack,
                                                  new double[] {0.995809759606, -0.004875265948,  0.003182766578,
                                                                0.988558542063,  0.000000000000,  9.774319840562},
                                                  0, 0));


        final Bounds parentBounds = new Bounds(36801.0, 38528.0, 2213.0, 47992.0, 49489.0, 2215.0);
        final double[] locationOffsets = new double[] { parentBounds.getMinX(), parentBounds.getMinY() };

        final AffineWarpFieldTransform warpFieldTransform = new AffineWarpFieldTransform(locationOffsets, warpField);


        final String warpFieldTransformId = "2214.0_AFFINE_WARP_FIELD";
        final TransformSpec warpTransformSpec = new LeafTransformSpec(warpFieldTransformId,
                                                                      null,
                                                                      AffineWarpFieldTransform.class.getName(),
                                                                      warpFieldTransform.toDataString());




        final RenderParameters box7Parameters =
                RenderParameters.parseJson(new File("/Users/trautmane/projects/git/render/render-app/src/test/resources/warp-field-test/box7.json"));
        final List<TileSpec> box7Tiles = box7Parameters.getTileSpecs();
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (final TileSpec tileSpec : box7Tiles) {
            tileSpec.addTransformSpecs(Collections.singletonList(warpTransformSpec));
            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true, false);
            minX = Math.min(minX, tileSpec.getMinX());
            minY = Math.min(minY, tileSpec.getMinY());
            maxX = Math.max(maxX, tileSpec.getMaxX());
            maxY = Math.max(maxY, tileSpec.getMaxY());
        }

        final int margin = 10;
        final double x = minX - margin;
        final double y = minY - margin;
        final int width = (int) (maxX - minX + 1) + (margin * 2);
        final int height = (int) (maxY - minY + 1) + (margin * 2);

        final RenderParameters renderParameters = new RenderParameters(null, x, y, width, height, tierStack.getScale());
        box7Tiles.forEach(renderParameters::addTileSpec);

        final BufferedImage targetImage = renderParameters.openTargetImage();
        ArgbRenderer.render(renderParameters, targetImage, ImageProcessorCache.DISABLED_CACHE);
        final ImagePlus ipWarpedBox7 = new ImagePlus("box7", targetImage);

        ipWarpedBox7.show();
    }

    private static void showBox4() throws Exception {

        final int consensusRowCount = 10;
        final int consensusColumnCount = 10;

        final HierarchicalStack tierStack = HierarchicalStack.fromJson(TIER_STACK);
        final Bounds tierStackBounds = tierStack.getFullScaleBounds();
        final ConsensusWarpFieldBuilder builder =
                new ConsensusWarpFieldBuilder(tierStackBounds.getDeltaX(),
                                              tierStackBounds.getDeltaY(),
                                              consensusRowCount,
                                              consensusColumnCount);
        final String groupId = "2214.0";
        final List<CanvasMatches> canvasMatchesList = CanvasMatches.fromJsonArray(MATCHES_OUTSIDE_2214_A);

        final CanvasNameToPointsMap nameToPointsForGroup = new CanvasNameToPointsMap(1 / tierStack.getScale());
        nameToPointsForGroup.addPointsForGroup(groupId, canvasMatchesList);

        final Bounds alignedStackBounds = new Bounds(-4.0, -38.0, 2213.0, 1106.0, 1109.0, 2215.0);
        final ResolvedTileSpecCollection alignedTiles = ResolvedTileSpecCollection.fromJson(ALIGNED_TILES);

        for (final String tileId : nameToPointsForGroup.getNames()) {

            final TileSpec tileSpecForZ = alignedTiles.getTileSpec(tileId);
            final AffineModel2D lastTransform = (AffineModel2D) tileSpecForZ.getLastTransform().getNewInstance();
            final AffineModel2D relativeAlignedModel =
                    tierStack.getFullScaleRelativeModel(lastTransform,
                                                        alignedStackBounds.getMinX(),
                                                        alignedStackBounds.getMinY());

            builder.addConsensusSetData(relativeAlignedModel, nameToPointsForGroup.getPoints(tileId));
        }

        final AffineWarpField warpField = builder.build();
        final double[] locationOffsets = new double[] { tierStackBounds.getMinX(), tierStackBounds.getMinY() };

        final AffineWarpFieldTransform warpFieldTransform =
                new AffineWarpFieldTransform(locationOffsets, warpField);

        final String warpFieldTransformId = "2214.0_AFFINE_WARP_FIELD";
        final TransformSpec warpTransformSpec = new LeafTransformSpec(warpFieldTransformId,
                                                                      null,
                                                                      AffineWarpFieldTransform.class.getName(),
                                                                      warpFieldTransform.toDataString());


        final RenderParameters box4Parameters =
                RenderParameters.parseJson(new File("/Users/trautmane/projects/git/render/render-app/src/test/resources/warp-field-test/box4.json"));
        final List<TileSpec> box4Tiles = box4Parameters.getTileSpecs();
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (final TileSpec tileSpec : box4Tiles) {
            tileSpec.addTransformSpecs(Collections.singletonList(warpTransformSpec));
            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true, false);
            minX = Math.min(minX, tileSpec.getMinX());
            minY = Math.min(minY, tileSpec.getMinY());
            maxX = Math.max(maxX, tileSpec.getMaxX());
            maxY = Math.max(maxY, tileSpec.getMaxY());
        }

        final int margin = 10;
        final double x = minX - margin;
        final double y = minY - margin;
        final int width = (int) (maxX - minX + 1) + (margin * 2);
        final int height = (int) (maxY - minY + 1) + (margin * 2);

        final RenderParameters renderParameters = new RenderParameters(null, x, y, width, height, tierStack.getScale());
        box4Tiles.forEach(renderParameters::addTileSpec);

        final BufferedImage targetImage = renderParameters.openTargetImage();
        ArgbRenderer.render(renderParameters, targetImage, ImageProcessorCache.DISABLED_CACHE);
        final ImagePlus ipWarpedBox4 = new ImagePlus("box4", targetImage);

        ipWarpedBox4.show();

        LOG.info("consensus warp field builder grid:\n{}", builder.toIndexGridString());
    }

    private static void showWarpedConsensusStack() throws Exception {

        // WARNING: This uses the scaled aligned tier stack to simplify rendering,
        //          but that requires the warp field builder to be scaled
        //          and the consensus points not to be.

        final int consensusRowCount = 10;
        final int consensusColumnCount = 10;

        final HierarchicalStack tierStack = HierarchicalStack.fromJson(TIER_STACK);
        final Bounds tierStackBounds = tierStack.getFullScaleBounds();
        final ConsensusWarpFieldBuilder builder =
                new ConsensusWarpFieldBuilder(tierStackBounds.getDeltaX() * tierStack.getScale(), // scaled just for this test
                                              tierStackBounds.getDeltaY() * tierStack.getScale(), // scaled just for this test
                                              consensusRowCount,
                                              consensusColumnCount);
        final String groupId = "2214.0";
        final List<CanvasMatches> canvasMatchesList = CanvasMatches.fromJsonArray(MATCHES_OUTSIDE_2214_A);
        final CanvasNameToPointsMap nameToPointsForGroup = new CanvasNameToPointsMap(); // skip scaling for this test
        nameToPointsForGroup.addPointsForGroup(groupId, canvasMatchesList);

        final ResolvedTileSpecCollection alignedTiles = ResolvedTileSpecCollection.fromJson(ALIGNED_TILES);

        for (final String tileId : nameToPointsForGroup.getNames()) {
            final TileSpec tileSpecForZ = alignedTiles.getTileSpec(tileId);
            final AffineModel2D lastTransform = (AffineModel2D) tileSpecForZ.getLastTransform().getNewInstance();
            // skip relative aligned model for this test
            builder.addConsensusSetData(lastTransform, nameToPointsForGroup.getPoints(tileId));
        }

        final AffineWarpField warpField = builder.build();
        final AffineWarpFieldTransform warpFieldTransform =
                new AffineWarpFieldTransform(AffineWarpFieldTransform.EMPTY_OFFSETS, warpField); // skip offsets for this test

        final String warpFieldTransformId = "2214.0_AFFINE_WARP_FIELD";
        final TransformSpec warpTransformSpec = new LeafTransformSpec(warpFieldTransformId,
                                                                      null,
                                                                      AffineWarpFieldTransform.class.getName(),
                                                                      warpFieldTransform.toDataString());


        final TileSpec tileSpec2214 =
                alignedTiles.getTileSpec("z_2214.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_0");

        final int margin = 10;
        double renderX = tileSpec2214.getMinX() - margin;
        double renderY = tileSpec2214.getMinY() - margin;
        int renderWidth = (int) (tileSpec2214.getMaxX() - tileSpec2214.getMinX() + 1) + (margin * 2);
        int renderHeight = (int) (tileSpec2214.getMaxY() - tileSpec2214.getMinY() + 1) + (margin * 2);
        final ImagePlus ip2214 = renderImage(tileSpec2214, renderX, renderY, renderWidth, renderHeight);
        ip2214.show();


        tileSpec2214.removeLastTransformSpec();
        tileSpec2214.addTransformSpecs(Collections.singletonList(warpTransformSpec));
        tileSpec2214.deriveBoundingBox(tileSpec2214.getMeshCellSize(), true, false);

        renderX = tileSpec2214.getMinX() - margin;
        renderY = tileSpec2214.getMinY() - margin;
        renderWidth = (int) (tileSpec2214.getMaxX() - tileSpec2214.getMinX() + 1) + (margin * 2);
        renderHeight = (int) (tileSpec2214.getMaxY() - tileSpec2214.getMinY() + 1) + (margin * 2);
        final ImagePlus ipWarped2214 = renderImage(tileSpec2214, renderX, renderY, renderWidth, renderHeight);

        final TileSpec tileSpec2213 = alignedTiles.getTileSpec("z_2213.0_box_40532_42182_3731_3654_0.274457");
        final ImagePlus ip2213 = renderImage(tileSpec2213, renderX, renderY, renderWidth, renderHeight);

        final TileSpec tileSpec2215 = alignedTiles.getTileSpec("z_2215.0_box_40532_42182_3731_3654_0.274457");
        final ImagePlus ip2215 = renderImage(tileSpec2215, renderX, renderY, renderWidth, renderHeight);

        final ImageStack imageStack = new ImageStack(renderWidth, renderHeight);
        imageStack.addSlice(ip2213.getProcessor());
        imageStack.addSlice(ipWarped2214.getProcessor());
        imageStack.addSlice(ip2215.getProcessor());

        final String title = "warped 2214 with " + consensusRowCount + "x" + consensusColumnCount + " grid";
        final ImagePlus imagePlus = new ImagePlus(title, imageStack);

        imagePlus.show();

        LOG.info("consensus warp field builder grid:\n{}", builder.toIndexGridString());
    }

    private static void showFullScaleWarpedConsensusLayer() throws Exception {

        final int consensusRowCount = 10;
        final int consensusColumnCount = 10;

        final HierarchicalStack tierStack = HierarchicalStack.fromJson(TIER_STACK);
        final Bounds tierStackBounds = tierStack.getFullScaleBounds();
        final ConsensusWarpFieldBuilder builder =
                new ConsensusWarpFieldBuilder(tierStackBounds.getDeltaX(),
                                              tierStackBounds.getDeltaY(),
                                              consensusRowCount,
                                              consensusColumnCount);
        final String groupId = "2214.0";
        final List<CanvasMatches> canvasMatchesList = CanvasMatches.fromJsonArray(MATCHES_OUTSIDE_2214_A);

        final CanvasNameToPointsMap nameToPointsForGroup = new CanvasNameToPointsMap(1 / tierStack.getScale());
        nameToPointsForGroup.addPointsForGroup(groupId, canvasMatchesList);

        final Bounds alignedStackBounds = new Bounds(-4.0, -38.0, 2213.0, 1106.0, 1109.0, 2215.0);
        final ResolvedTileSpecCollection alignedTiles = ResolvedTileSpecCollection.fromJson(ALIGNED_TILES);

        for (final String tileId : nameToPointsForGroup.getNames()) {

            final TileSpec tileSpecForZ = alignedTiles.getTileSpec(tileId);
            final AffineModel2D lastTransform = (AffineModel2D) tileSpecForZ.getLastTransform().getNewInstance();
            final AffineModel2D relativeAlignedModel =
                    tierStack.getFullScaleRelativeModel(lastTransform,
                                                        alignedStackBounds.getMinX(),
                                                        alignedStackBounds.getMinY());

            builder.addConsensusSetData(relativeAlignedModel, nameToPointsForGroup.getPoints(tileId));
        }

        final AffineWarpField warpField = builder.build();
        final double[] locationOffsets = new double[] { tierStackBounds.getMinX(), tierStackBounds.getMinY() };

        final AffineWarpFieldTransform warpFieldTransform =
                new AffineWarpFieldTransform(locationOffsets, warpField);

        final String warpFieldTransformId = "2214.0_AFFINE_WARP_FIELD";
        final TransformSpec warpTransformSpec = new LeafTransformSpec(warpFieldTransformId,
                                                                      null,
                                                                      AffineWarpFieldTransform.class.getName(),
                                                                      warpFieldTransform.toDataString());


        final TileSpec tileSpec2214 =
                alignedTiles.getTileSpec("z_2214.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_0");

        // WARNING: need to add scaleAndOffset transform because we're using world coordinates for the builder

        final double scaleFactor = 1 / tierStack.getScale();
        final String dataString = scaleFactor + " 0 0 " + scaleFactor + " " +
                                  tierStackBounds.getMinX() + " " + tierStackBounds.getMinY();
        final LeafTransformSpec scaleAndOffsetSpec =
                new LeafTransformSpec(mpicbg.trakem2.transform.AffineModel2D.class.getName(), dataString);

        tileSpec2214.removeLastTransformSpec();
        tileSpec2214.addTransformSpecs(Collections.singletonList(scaleAndOffsetSpec));
        tileSpec2214.addTransformSpecs(Collections.singletonList(warpTransformSpec));
        tileSpec2214.deriveBoundingBox(tileSpec2214.getMeshCellSize(), true, false);

        final int margin = 10;
        final double renderX = tileSpec2214.getMinX() - margin;
        final double renderY = tileSpec2214.getMinY() - margin;
        final int renderWidth = (int) (tileSpec2214.getMaxX() - tileSpec2214.getMinX() + 1) + (margin * 2);
        final int renderHeight = (int) (tileSpec2214.getMaxY() - tileSpec2214.getMinY() + 1) + (margin * 2);
        final ImagePlus ipWarped2214 = renderImage(tileSpec2214, renderX, renderY, renderWidth, renderHeight);

        ipWarped2214.show();

        LOG.info("consensus warp field builder grid:\n{}", builder.toIndexGridString());
    }

    private static ImagePlus renderImage(final TileSpec tileSpec,
                                         final double x,
                                         final double y,
                                         final int width,
                                         final int height) {
        final RenderParameters renderParameters = new RenderParameters(null, x, y, width, height, 1.0);
        renderParameters.addTileSpec(tileSpec);
        final BufferedImage targetImage = renderParameters.openTargetImage();
        ArgbRenderer.render(renderParameters, targetImage, ImageProcessorCache.DISABLED_CACHE);
        return new ImagePlus(tileSpec.getTileId(), targetImage);
    }

    private static final String TIER_STACK =
            "{\n" +
            "    \"roughTilesStackId\" : { \"owner\" : \"flyTEM\", \"project\" : \"trautmane_fafb_fold\", \"stack\" : \"rough_tiles_k\" },\n" +
            "    \"parentTierStackId\" : { \"owner\" : \"flyTEM\", \"project\" : \"trautmane_fafb_fold\", \"stack\" : \"rough_tiles_k\" },\n" +
            "    \"alignedStackId\" : { \"owner\" : \"flyTEM\", \"project\" : \"trautmane_fafb_fold_rough_tiles_k_tier_1\", \"stack\" : \"0003x0003_000004_align\" },\n" +
            "    \"warpTilesStackId\" : { \"owner\" : \"flyTEM\", \"project\" : \"trautmane_fafb_fold\", \"stack\" : \"rough_tiles_k_tier_1_warp\" },\n" +
            "    \"tier\" : 1, \"tierRow\" : 1, \"tierColumn\" : 1, \"totalTierRowCount\" : 3, \"totalTierColumnCount\" : 3, \"scale\" : 0.27445725006700616,\n" +
            "    \"fullScaleBounds\" : { \"minX\" : 40532.0, \"minY\" : 42182.0, \"minZ\" : 2213.0, \"maxX\" : 44263.0, \"maxY\" : 45836.0, \"maxZ\" : 2215.0 },\n" +
            "    \"matchCollectionId\" : { \"owner\" : \"flyTEM\", \"name\" : \"trautmane_fafb_fold_rough_tiles_k_tier_1_0003x0003_000004\" },\n" +
            "    \"savedMatchPairCount\" : 4,\n" +
            "    \"alignmentQuality\" : 0.101609\n" +
            "}";

    private static final String TIER_7_STACK =
            "{\n" +
            "    \"roughTilesStackId\" : { \"owner\" : \"flyTEM\", \"project\" : \"trautmane_fafb_fold\", \"stack\" : \"rough_tiles_k\" },\n" +
            "    \"parentTierStackId\" : { \"owner\" : \"flyTEM\", \"project\" : \"trautmane_fafb_fold\", \"stack\" : \"rough_tiles_k\" },\n" +
            "    \"alignedStackId\" : { \"owner\" : \"flyTEM\", \"project\" : \"trautmane_fafb_fold_rough_tiles_k_tier_1\", \"stack\" : \"0003x0003_000007_align\" },\n" +
            "    \"warpTilesStackId\" : { \"owner\" : \"flyTEM\", \"project\" : \"trautmane_fafb_fold\", \"stack\" : \"rough_tiles_k_tier_1_warp\" },\n" +
            "    \"tier\" : 1, \"tierRow\" : 2, \"tierColumn\" : 1, \"totalTierRowCount\" : 3, \"totalTierColumnCount\" : 3, \"scale\" : 0.27445725006700616,\n" +
            "    \"fullScaleBounds\" : { \"minX\" : 40532.0, \"minY\" : 45836.0, \"minZ\" : 2213.0, \"maxX\" : 44263.0, \"maxY\" : 49490.0, \"maxZ\" : 2215.0 },\n" +
            "    \"matchCollectionId\" : { \"owner\" : \"flyTEM\", \"name\" : \"trautmane_fafb_fold_rough_tiles_k_tier_1_0003x0003_000007\" },\n" +
            "    \"savedMatchPairCount\" : 2,\n" +
            "    \"alignmentQuality\" : 0.0\n" +
            "}";

    private static final String MATCHES_OUTSIDE_2214_A =
            "[\n" +
            "  { \"pGroupId\" : \"2213.0\", \"pId\" : \"z_2213.0_box_40532_42182_3731_3654_0.274457\", \"qGroupId\" : \"2214.0\", \"qId\" : \"z_2214.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_0\", \"consensusSetData\" : { \"index\" : 0, \"originalPId\" : \"z_2213.0_box_40532_42182_3731_3654_0.274457\", \"originalQId\" : \"z_2214.0_box_40532_42182_3731_3654_0.274457\" }, \"matches\" : { \"p\" : [[153.46346947224578, 27.82445617022006, 476.9446413783853, 908.707000726556, 453.2295658236536, 821.7939759548573, 478.7865135460907, 513.6480220080267, 803.7778184926834, 85.14859548846412, 442.5197855080373, 954.2472412530739, 332.4036818166845, 842.4066202255171, 352.10216295158045, 366.0802994951752, 567.603198273175, 573.9060798834867, 402.6431372980465, 368.19332004869204, 425.7052786321322, 389.2307463957001, 531.7182809460598, 289.02803773871017, 399.4677208473643, 355.8461942097263, 634.344149038957, 359.62884353022633, 385.6960724282422, 464.3022835246452, 414.461829869957, 491.76413745777677, 74.88127259379313, 205.63028441271436, 292.3805104455909, 755.4576044243462, 375.39212748448733, 372.1317098132353, 425.564153988025, 319.10349177684265, 453.21345190856005, 44.16133251838714, 298.7249645871031, 36.64088606271831, 108.22168175876695, 300.24848075040893, 82.96004720367266, 13.477476642025207, 981.0211462317718, 281.67039638001535, 60.28222311466242, 333.2961394429344, 660.7957654622726, 746.1051187798365, 933.0306828271059, 963.1577877027363, 60.15814878101749, 20.906445747724153, 735.7608513640531, 528.1626562318503, 553.1796582370209, 296.73827949400436, 560.3769183831811, 437.34600256316895, 160.31896152505888, 204.61714128451382, 154.2745677804168, 26.046954332729065, 665.8115303773027, 327.23613918394744, 709.6184550485814, 620.545284209716, 265.2692591939347, 379.64180464199916, 375.5234450948236, 552.4638386998477, 526.9247566849263, 259.3935976747321, 421.4959228676452, 33.78246220586792, 40.26307338541579, 815.706875275597, 988.3389330975498, 522.0498339077456, 1002.2118182798519, 514.6438588186763, 62.16271926732783, 290.1703990994027, 330.04694410116707, 550.3430200080691, 413.18924704646764, 430.56013234961813, 141.2821754865715, 283.91535873233664, 923.9772267992845, 361.0293288174228, 6.380574805592185, 212.47829833050483, 284.7734500877453, 827.9601933384745, 942.1081596301949, 658.4539313285668, 332.63755239569093, 399.38855464705074, 328.99056258263437, 701.5560444130897, 526.5902207825354, 813.6601178740605, 327.6376427796229, 891.2413720156449, 294.0269077792966, 307.2267807274416, 630.5043907405849, 893.8121143688182, 677.5170280508559, 498.04158578544366, 598.2261117376881, 250.6211803960686, 414.4418995188899, 545.6582797562185, 304.9273850686568, 449.79516781850685, 725.8435957969467, 554.1385759127264, 26.111613512432847, 97.36170036185965, 703.9629711456264, 672.130168953749, 721.6218558708205, 556.3764134560647, 260.032658094587, 423.38551488916744, 324.62436718331384, 523.478333971342, 504.57635556728275, 550.1234483668101, 549.7964320539027, 570.8515237730323, 369.2467900063971, 24.847170211220757, 64.74844498047557, 139.31414225647765, 29.324412505967786, 418.34523595772635, 507.5955073448422, 507.5955073448422, 412.5841263718009, 37.23940058024934, 43.97288798520201, 238.3367426715946, 66.90563022685949, 344.3976430335342, 105.88667620318675, 72.84122915216442, 164.63948947345332, 584.8912853198195, 398.10412290229596, 363.27015232596955, 213.7104590662932, 279.39303655629726, 406.12993236593394, 421.49498141168414, 12.29385619978876, 547.0280626806477, 88.78170959970588, 636.9168319169378, 410.4293109640383, 19.138968795290324, 570.3215556798846, 82.69764432735424, 8.739194287095744, 360.71243875561936, 975.9830329477575, 71.77018159464407, 349.8413491009156, 332.9866941959694, 78.8065845278529, 230.76487457523527, 297.80304142614733, 288.24415802565994, 474.8536435808269, 61.20345678650598, 573.9265693631407, 110.84377684655576, 552.9712673070671, 370.2613273390461, 690.6307416781171, 40.11098688554211, 122.13488444086836, 23.680281293525645, 788.823464325545, 22.99020025597683, 353.0855515925617, 395.8186306377181, 475.62681518760024, 20.416811317926584, 297.20654670857584, 543.0211541664017, 457.42462897315164, 474.0247452372763, 386.6489648568137, 195.80291880705718, 555.4512449907355, 49.87900516038569, 863.7538377939716, 55.98444761907482, 351.6583342581097, 42.3008425553632, 33.56914252026278], [991.7149876544172, 970.3383096845696, 963.1917754370203, 959.3971692751488, 942.8943327565568, 932.2805956059749, 930.9552271152303, 926.0785647138221, 910.2668020748947, 897.6064900275736, 893.1400765753654, 891.0261848659694, 885.5717386091601, 879.0761603518347, 863.0032901636837, 842.331232288792, 833.8393170586337, 823.7317221438678, 814.1507887083211, 808.3267469560093, 797.5340310426667, 794.995073377774, 778.0057745736361, 772.4205660084169, 758.3472771756618, 733.5852878884856, 730.4520633939698, 718.8630258039221, 663.532619194594, 622.9868719670109, 607.0653109944788, 596.1699405506974, 581.6054568722777, 542.0983266538212, 516.5899906441971, 922.4305474814021, 863.3048399463851, 848.1352206676527, 805.7557406643739, 622.0243403989722, 612.2262831702672, 594.6110790517948, 502.64913270236536, 366.9731873139172, 354.7652140708026, 993.5583572740081, 972.8139641903898, 970.0603216904021, 967.1456098420621, 960.9479208711793, 951.4913629092941, 940.7958186350669, 935.7496212717604, 934.2025469893188, 811.0276581940307, 799.5998294738687, 711.3610654508734, 660.0346487773371, 625.3187588089258, 621.8336260661167, 620.1664064595001, 605.8420614430452, 601.0642768934372, 577.2783461298443, 992.3739822638424, 976.434063800531, 973.8916762071755, 963.9511283075293, 875.5824441426341, 819.872035498351, 816.2013213190601, 763.0575779894825, 762.9754647780477, 736.2241821074089, 700.9425208984328, 654.290363831917, 632.6806916855049, 543.9209923764628, 535.9734011750663, 428.219707742533, 344.424138760991, 979.2606791409981, 958.3707877435485, 848.1144125357587, 822.182866679456, 802.4300516645347, 795.3819033162198, 760.5156242846466, 725.0794240618897, 606.0812478504132, 477.76126529145097, 457.08211871296777, 994.3038130756563, 982.5541193123079, 976.9593165048149, 965.3698034313161, 962.5188240923666, 954.6038557076213, 953.1125199674328, 919.0417041336892, 911.2370041521908, 896.1264188855139, 895.8452329804975, 895.0348470711601, 880.1203781675766, 853.9291263762195, 850.8294658282354, 841.986131087745, 842.0165865665933, 825.3572242499006, 825.6483192996378, 820.6234982690539, 816.682901955559, 815.9762571061764, 810.0262887195295, 787.1785022391073, 777.5798187783538, 751.3411430820744, 742.2161815075199, 731.047765803494, 729.6127553121228, 717.4415018858684, 716.2724407939393, 711.0374864195614, 710.3233698104432, 702.3035509983381, 700.6906536116315, 653.808058172325, 638.8847536072805, 638.5098683803029, 630.7594283402456, 586.3878538905564, 569.4996291754135, 563.6778420982178, 544.621676571604, 540.2255708534749, 535.9152013773554, 521.9219279084178, 488.07908005630054, 403.0955292676613, 350.6357642780887, 333.25548184919506, 955.6476492467101, 851.2830704615255, 784.3478145520395, 784.3478145520395, 760.3340269678154, 749.4111299134944, 662.3803895425513, 636.8879114551133, 606.9673296593294, 800.4375298041602, 480.42779073075553, 993.2587999613494, 972.1369085266533, 806.719615083053, 802.6374565940968, 745.5045676487772, 707.8480987872716, 670.714177963911, 636.5790618877971, 576.6493288506406, 373.4429364536241, 909.4615908175324, 885.513956390906, 836.2299875430523, 804.8015946579152, 765.1521284089644, 606.4381756228291, 380.3291166110732, 981.4324925062368, 875.4374100938871, 792.4318278772014, 771.5693065756101, 742.5557322210008, 707.4367495565589, 705.5954384675435, 692.5408929812982, 979.598143990788, 955.1874806033001, 917.4795095717185, 866.5879371065855, 810.9801393446367, 793.0709413222211, 736.5656071716465, 731.3364771924586, 676.9323677852528, 648.5838279685645, 431.89450748687864, 413.7516467976923, 968.5464858237149, 948.8250453040408, 882.7332941027728, 744.1241738499809, 760.6149467410945, 689.9219442999027, 948.3443938025177, 754.8827869434565, 877.048180602651, 945.9237479835592, 946.0648027538691, 875.837288501586, 796.1236985598679, 948.6991873731025, 908.6033219830596, 790.4943069839982, 784.8247556690861, 378.5734857382397, 735.4256840366271]], \"q\" : [[152.0155156417605, 27.116715669769622, 480.2005506558022, 913.123002799836, 455.273101796283, 823.373110550364, 478.5194231121155, 515.3315749443195, 804.9916371754952, 83.12749841364719, 443.89378593430615, 961.0839230567524, 334.26571564366094, 843.7444199190703, 355.00816901228933, 366.2018625267435, 568.828792921411, 577.8560373997253, 399.7146370214256, 370.04534165112034, 428.0545482209223, 388.02817714256173, 535.0223747501304, 288.62935371995127, 397.38955680370276, 354.3711881990592, 635.2980429415742, 358.2046365257354, 381.6823591720665, 465.83671252554734, 410.8247553522711, 490.6810913718162, 71.71600243304613, 204.86807566708788, 289.474682815148, 756.3810031745753, 378.30405981957676, 372.1267350500816, 429.18372998409416, 320.12608913168975, 448.79605061872405, 41.25418278005236, 297.5648573814027, 32.75793386862691, 103.97115543254039, 302.56326021831006, 82.12075134747744, 15.774096767010327, 985.8736408364227, 279.6272607718806, 60.307446863154674, 334.87007349691766, 661.8774310769088, 748.9017656112958, 938.3396354552074, 966.1119630541277, 59.76915947685462, 20.467835691173832, 736.9345694135815, 527.4503719998711, 551.377841238683, 292.3458897510447, 558.6380247268494, 432.2241176935258, 159.1982118979488, 204.83675384193228, 154.98191632930792, 20.923849892587807, 668.7541874164914, 326.60156773652943, 714.4346333507326, 620.4972656765265, 260.97330474603575, 380.7741474161074, 373.2016460272153, 554.6596806478819, 523.0038972989188, 258.32998082494004, 415.7053145712119, 29.304404598772404, 34.9521539892134, 819.9886280490596, 991.1756899011361, 522.8389269210461, 1006.0564103889761, 519.2922187499385, 60.81792307379031, 287.4672480740971, 328.2851761016116, 547.9378844531898, 410.6314181525782, 427.33471318030377, 138.16880262344733, 285.32830473065957, 928.4831156813739, 362.0657848497331, 6.266183049242347, 211.89682483710737, 285.07414395935115, 831.8911561539052, 946.7527887032851, 661.3291581763327, 333.16762720157857, 401.9136434779906, 327.14789510937624, 700.1211811274845, 528.127799345176, 815.8719116962365, 329.14604076342874, 894.3869391232673, 294.6294697813141, 305.1808096281239, 634.0446931048954, 896.9697092714157, 677.7658005365637, 499.86967142024645, 598.5397689326701, 249.4560660394179, 414.64726237805377, 545.3338510221224, 304.0591907175419, 453.7812458507435, 727.7781458500067, 554.7507339898983, 24.502084527789936, 95.46782727061914, 706.7811275874569, 671.9978764136499, 721.8242383895102, 554.2980361268744, 262.51077622533757, 421.08203785936314, 321.38611717572985, 524.8746733402461, 502.3395112040098, 547.87486510753, 548.2986556107629, 566.7943177949948, 367.0433994222411, 18.646633402983735, 58.3435208072154, 133.2961081632701, 29.063594648351447, 419.5907397339764, 511.16042619086863, 510.9918455650709, 416.4523214704401, 35.53716820080305, 43.47464381907197, 236.3137759831852, 58.73717523092954, 341.237375507083, 102.91481100647151, 72.66277594841252, 161.7595776164367, 587.119987956009, 398.6587762569152, 362.36224481128914, 212.5367855660226, 279.66270267774854, 403.5752939666697, 417.8807093287907, 7.163129258120585, 550.8171671438581, 87.84559101941868, 640.9834212691806, 412.6227479899331, 14.114036857661635, 569.1973280918032, 75.96222329072297, 8.894632675056329, 362.5185705122218, 980.3577164021303, 74.30037684166764, 346.14729828412595, 330.37469039215, 74.43534754177153, 231.0893313768148, 298.5514601878965, 289.48290106299044, 478.4307825275954, 58.321904279427685, 577.7568468374371, 112.42592707932859, 553.1960066945071, 369.63086143356566, 688.8013080255691, 36.02167969519655, 119.321001865603, 19.128632582096042, 793.0359297205334, 24.601184217450406, 350.77323593611163, 394.6321943746266, 478.14381581108785, 21.439010478674135, 297.96500987657043, 544.4581426898856, 460.19540118666254, 477.7014799758995, 388.70839172619657, 197.26836977796302, 555.5040260291922, 51.229616446889494, 869.1459242382596, 56.650851011018005, 347.489494904589, 38.82551771764792, 34.123888662871146], [989.6111234216183, 968.459636255727, 956.3982594164057, 950.8113911752096, 933.9715987441347, 922.9688403862644, 922.3980741150423, 919.6100486427882, 900.5301453625179, 893.7069010910545, 888.1810089895895, 884.3005784911581, 882.7310743154349, 868.5597369724835, 854.3828757998758, 836.8400654006462, 825.7537032147212, 816.5569270164195, 808.3352949613184, 800.9295898948258, 789.597646517411, 790.1889379030752, 768.0069834166991, 768.6042909885928, 755.7942991738619, 727.4024492572069, 722.598399880758, 713.7155930766908, 656.6834294095951, 613.5017817022253, 600.0470264293465, 590.29150428153, 576.387311745266, 538.500774569457, 513.9888408519155, 914.5770459963591, 858.0185557145343, 842.254429290315, 797.8043686473399, 616.5574419444198, 603.0927143229692, 592.7119341435284, 496.6918951278803, 360.13334885665637, 350.39871154235124, 989.3669699440502, 969.3040577822596, 968.1975295894084, 959.9895300834751, 959.6815919229737, 949.1883989228734, 934.7357128574076, 928.988237930786, 926.0871193807817, 799.4394883775619, 788.1376416679474, 709.2517366688103, 658.6718540824, 611.6250112726002, 610.9322508710554, 610.9760701434202, 602.0142570116296, 592.0794480307605, 569.6153376350934, 989.9397766580277, 972.8595700874865, 973.1253700233608, 963.80728516867, 867.4004129763512, 815.9049206020849, 806.4893331048822, 753.329791063957, 757.8796652046433, 728.6653557921832, 696.9905994453851, 644.1561413005027, 624.6120871792928, 537.5843462999152, 528.0337058939066, 423.58681830262435, 339.7217313945275, 969.5752319471121, 951.4953098371755, 842.5351434882962, 814.4094391405439, 792.18974179768, 792.1537453543925, 755.562545420643, 719.1339382429031, 599.2533853860072, 469.51076092595855, 446.723142954432, 995.0123695629819, 977.170225468632, 968.1356979308304, 961.132204840739, 959.0164526057936, 952.1726245011762, 947.9097228398678, 911.0243685639258, 902.6848613008033, 889.1889168768553, 891.5445266446773, 889.4316200760305, 878.6663439209053, 845.2246034515249, 843.5562052937738, 831.3339687117605, 837.1107248740398, 815.7461216384017, 820.5533777874025, 817.5076080359327, 809.2004155396324, 805.6313991156594, 801.1853758969573, 780.3876912562262, 765.5276164722467, 746.5415212231491, 736.2970405382988, 722.0296708719429, 723.4566870337914, 709.3159067788182, 705.8911394936856, 703.0992581808741, 709.8531094711338, 699.4081092704415, 689.4784994485797, 642.7627208678871, 627.8046080234674, 628.9534903863689, 625.5369812289192, 579.9098024274301, 566.6776639380522, 553.4957319875689, 535.5550602636121, 529.1308367078097, 524.5986681273649, 511.5738070354781, 483.5903691359467, 398.46060630059094, 345.23078720571544, 328.5196699463444, 954.9601658794038, 845.4809742384658, 777.5574559660336, 777.7001484053379, 751.1761475549613, 746.5101539257904, 658.9942244580918, 631.2493663553363, 602.1262878183583, 796.5694352814866, 476.00782842726915, 991.5433695355206, 970.5628018627771, 799.3877737878441, 796.3508383028781, 740.5371171972712, 703.4385504460797, 664.924068814064, 630.7756731253482, 571.5345630399045, 366.38255329321134, 902.089014982763, 886.5860429778653, 828.8096920939192, 797.9268240852604, 760.1775818050586, 596.2601471618054, 376.0424934462945, 978.6811819093525, 869.8769815958276, 782.3941294501368, 768.3457704956427, 737.2073333044543, 697.8446289414194, 702.9617462321931, 688.3525889304095, 975.3871362795394, 951.6819976340289, 909.2321896512411, 862.5442498639712, 804.4308505103619, 792.0098443008228, 727.6537307250157, 725.2435736706217, 670.2049981672668, 646.5129964015115, 422.98985453725277, 408.7347043418542, 960.6441800237089, 946.1428703105432, 881.079750365577, 742.11766606765, 752.1796723524063, 686.106939613345, 941.636982159116, 745.8633615081992, 869.2200875248723, 939.5144938453267, 941.3454589379095, 876.9044083840391, 790.6413671304854, 945.2013665537642, 901.8108909325352, 788.0596660909999, 780.321924957427, 371.61788683679686, 730.8114013147468]], \"w\" : [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0] } },\n" +
            "  { \"pGroupId\" : \"2213.0\", \"pId\" : \"z_2213.0_box_40532_42182_3731_3654_0.274457\", \"qGroupId\" : \"2214.0\", \"qId\" : \"z_2214.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_1\", \"consensusSetData\" : { \"index\" : 1, \"originalPId\" : \"z_2213.0_box_40532_42182_3731_3654_0.274457\", \"originalQId\" : \"z_2214.0_box_40532_42182_3731_3654_0.274457\" }, \"matches\" : { \"p\" : [[982.6294646691823, 902.1625098839866, 787.9280307972458, 915.4514938528969, 492.1628834553578, 734.6859378996227, 768.6919855952857, 370.88594151025666, 607.1950018309924, 330.64141838280955, 349.0723173095799, 642.6699874549648, 605.5675698711505, 722.3584587078152, 539.4072426782577, 568.6307236015323, 598.0378718301585, 355.48256590369294, 377.1120913508569, 508.57093227744906, 323.44622910614953, 798.2450153197227, 731.1165202835784, 824.8513725045459, 784.0055788553082, 641.2801268914566, 535.1936720153832, 528.0390413109714, 505.8162036205315, 830.728430088685, 551.3971132193916, 666.376334999051, 637.0081349865502, 917.3972613123532, 803.3707215912968, 623.7985799664834, 439.68083694349644, 334.0453599100958, 331.55082967442524, 641.523923294345, 313.67418884266726, 773.7177718217077, 726.6666489890897, 93.88137750807816, 598.5587877607137, 718.3960014208087, 427.45768728356416, 399.75268078753794, 641.6142399228083, 928.8339631243593, 336.07996075686435, 528.3451901410734, 513.0343566213064, 286.69121280967624, 543.7534109818741, 342.4020758391119, 344.63364851006247, 722.5548676341123, 947.4163382940715, 795.9010102523185, 933.8566689932625, 918.0396519817422, 615.8240164926069, 913.2244514313445, 803.3152077341462, 800.6282497077893, 942.8986368463648, 926.5793133389338, 455.4617057194015, 359.1019542044467, 332.29863478673076, 850.9606669359408, 610.5172185217077, 524.5799942361979, 347.73411812467845, 885.0284062008899, 556.132562315956, 348.34734669562323, 561.9255591968504, 216.93118148149844, 736.6341436985496, 604.6763908482415, 595.533148167864, 893.0587632004716, 753.303125388303, 762.6392771519075, 782.1830286472217, 623.4350544926524, 916.2883732181514, 929.5737946816162, 621.4972928091522, 669.9958695188549, 670.5497114601557, 906.2523464785608, 823.7125571625643, 728.3625845654315, 546.4241965579363, 738.3663993839159, 751.7673471761115, 441.82313492068636, 533.6740611742953, 786.2734397567484, 702.5286884705439, 553.5540635159755, 255.1895392297541, 960.4581127384961, 505.7394203896581, 617.8686407599887, 883.9043346425485, 862.22557304102, 438.04265744344485, 744.53782223407, 777.2909746187804, 531.182473135772, 770.6295452729979, 818.7547678993299, 658.9305582694859, 517.6017335943803], [329.6167428164739, 292.3471051772644, 283.4501339837733, 227.15351926857107, 161.10398923661194, 137.9686196465705, 123.88548034042996, 121.8608824694135, 120.77116516973842, 116.22469330268643, 111.3655510335398, 100.16420483569063, 97.76452759929064, 92.80779662978617, 93.38988460087857, 82.94102446794716, 72.99225265861075, 38.03090478235091, 34.39844980149285, 31.98307708931557, 17.699981048157372, 133.12990991149664, 92.09689214954959, 78.76221051783801, 171.24543216355252, 155.10768442387692, 127.78032407063246, 122.39515168680542, 117.51686812364105, 89.9326495284258, 77.41553187567963, 37.382831874894364, 23.046674347505352, 312.23761516401373, 243.9155094966018, 238.53899689765112, 130.55572562836062, 104.80938070350936, 98.84669060486148, 93.21920847792195, 85.29492034893923, 70.56170241309695, 24.871691208622575, 23.04314772937606, 237.19214826841093, 179.5105209943644, 175.03790412260946, 173.33890108848834, 164.97443986965652, 135.82075586923784, 121.31977667957801, 116.1616436658141, 115.99608457293307, 102.63311289220192, 97.47974601937172, 94.54025434497929, 32.335627795898596, 28.30190070086123, 409.4296852805992, 318.31587203016306, 309.60193561372995, 276.1735537156105, 249.78581175991363, 225.3143108608082, 224.18696415760283, 208.1956079398579, 203.56978644762793, 202.60952006092342, 153.96476685287692, 151.01594625228492, 142.71238088313243, 140.66349984703123, 137.31106007073214, 131.0461278722664, 128.19979984729432, 98.8224794685251, 95.82673838743497, 95.96789859136209, 85.3879194330902, 81.92840574815594, 80.91622810150473, 80.1635464875245, 79.42095991037807, 76.10878397695735, 73.11571022870254, 57.875410937282034, 56.054282403211154, 30.497597702699522, 286.78077223783725, 247.73567804691496, 173.02415965603794, 170.16825070746253, 152.16237316899927, 118.27326975014282, 118.3936054211116, 99.14486620266005, 70.58839865875875, 29.403513693787172, 94.48875779171246, 47.8907122233116, 124.73803478293695, 112.99125950584927, 106.8974040228897, 92.53755016170902, 84.55125509746087, 201.56688385336386, 108.41923252047211, 185.9750798182854, 181.5585051245683, 146.74577194981626, 138.8547285606176, 119.21107884404579, 88.35498405411677, 80.81573971015779, 25.13406439819022, 65.41832886759427, 105.73080788554026, 103.95819707998857]], \"q\" : [[916.6783311051336, 838.8093865841332, 724.7609675529716, 853.2023719963586, 436.1302347849183, 677.3867993630391, 710.3232963888576, 319.9836517401495, 551.9997007743275, 278.4663526362728, 296.13339307080224, 589.1943764364374, 549.8436814937257, 667.2069982709176, 485.51283926263505, 517.609929142933, 544.887856383867, 305.8290730125916, 327.1628704445116, 456.3372327065875, 273.9080201593678, 740.5104990626371, 677.376612941752, 769.1175689589827, 724.128471569472, 584.1108893390764, 480.31764304187146, 474.17685960980856, 452.9557683293763, 773.7055897154344, 498.7590212161278, 611.9308677890509, 584.6074738251509, 849.5511890841254, 740.7879270513308, 565.040477741848, 387.1708594342552, 283.32178563576053, 279.396797361832, 589.5647602584404, 261.2133687994354, 716.6239040607737, 674.2597257895906, 46.41373878350303, 537.7402901958665, 661.9134003050632, 370.78993776534423, 345.7987301734162, 587.672180436251, 871.0857862364634, 280.56219564460406, 472.8412553591501, 458.6044452334457, 235.27888541369092, 492.44046056534364, 290.04162047726555, 291.88975799121334, 668.8130717868253, 875.6134500344347, 730.0137945084318, 868.7116862152467, 852.2057205561209, 555.442672811904, 851.4614579661745, 737.2174545844517, 735.5260678134838, 882.6612765733202, 865.0028911453863, 398.1539678015812, 306.15883133787554, 279.1899728605984, 794.2717690205618, 552.1355887043616, 471.5895857076798, 295.518805725079, 828.0747341731981, 501.44844008222225, 297.2838824170657, 509.06444895852684, 167.12365469829567, 678.3808926048836, 551.0244085064734, 542.2425862471168, 839.2070479982494, 698.3308524217138, 704.753749186328, 726.7551126313729, 572.7868298427882, 851.3528836567415, 864.8417663524335, 564.0279466458794, 611.0656053130788, 609.3115424510532, 848.8886176019139, 765.6672261008105, 674.5100756207371, 492.6187159626709, 685.8673512465376, 696.6947491385424, 388.3660565946258, 477.3985720634507, 727.9289631111606, 644.9198504832658, 500.1500267886498, 204.3065253917772, 901.1801659440753, 448.77907888308704, 558.9353208448262, 824.4808170678452, 803.8890737111327, 378.8813629897076, 685.1600469557867, 724.417170855162, 475.56179634883426, 715.1348210433869, 763.3689389015067, 605.1194091914691, 462.6930555839433], [462.79334745273655, 423.4842736685797, 409.7086834992505, 358.6817734474413, 268.95349602632615, 258.5669305267687, 246.44828079722802, 221.59849797004898, 234.9598636272591, 214.37496165613854, 210.3099509181189, 217.83896397268458, 207.63846020223468, 211.41899871231504, 202.5695956424336, 193.23993471231242, 185.9558425925418, 138.53349296094663, 135.80969753299763, 141.67512919670892, 117.68014425442755, 254.8307877833355, 211.85379449548816, 203.3768673087799, 296.401652560768, 270.2006767158864, 238.42890794785504, 231.62429904393335, 226.14220390006113, 215.53806302465185, 187.47096519767797, 152.69998656299433, 136.2784629539423, 444.4263910562567, 369.45497429509703, 356.22533711007014, 234.25924224603722, 207.468357467008, 195.51651917010014, 210.60959800783314, 183.19820214845936, 192.26725635406473, 143.25684902031279, 112.17013669100878, 351.81672345863313, 298.9633672857022, 276.95730066006047, 276.2221794754943, 280.18946900684506, 264.1921882185367, 224.14359023546893, 226.23399490066782, 224.54021675337657, 200.15813888537124, 208.67594253032897, 194.5929147559691, 134.7755972036064, 145.76436684021152, 541.4916804254913, 443.2177093149077, 441.7228916397397, 410.48094269206916, 367.3405956554061, 356.43165729210466, 351.0705419097243, 332.70492177805505, 338.59941876140715, 332.8308179469287, 260.33109517840006, 252.18815028775037, 243.30421219755715, 267.15330394387655, 253.33115056635708, 239.56289651213925, 228.46912280732917, 225.9827178860754, 207.07809486418176, 195.58010076176862, 196.38279590036953, 174.6346348619753, 202.43559919206575, 194.36128321638338, 193.5237765232738, 205.64385506227347, 190.63743322131117, 178.94573656798894, 176.45537430061174, 142.53953981282413, 418.0205763077285, 380.85493421060374, 289.60491776412624, 287.58119544636133, 270.02297237889746, 243.92419353997062, 241.60531417258852, 218.34121144058076, 180.93676124640268, 147.5451674441899, 214.42198238710222, 151.28741005237418, 236.25818593072503, 238.46071899791156, 228.21115050985844, 202.6232088859481, 180.06569089618478, 333.9526643586384, 216.49543578229938, 305.6080545968165, 309.0816647859525, 275.90193098458036, 247.20366370974875, 240.03895800961453, 206.96118652151554, 190.24133882328263, 148.8790768016769, 191.93282759814642, 222.55641464872588, 214.1317822459364]], \"w\" : [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0] } },\n" +
            "  { \"pGroupId\" : \"2214.0\", \"pId\" : \"z_2214.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_0\", \"qGroupId\" : \"2215.0\", \"qId\" : \"z_2215.0_box_40532_42182_3731_3654_0.274457\", \"consensusSetData\" : { \"index\" : 0, \"originalPId\" : \"z_2214.0_box_40532_42182_3731_3654_0.274457\", \"originalQId\" : \"z_2215.0_box_40532_42182_3731_3654_0.274457\" }, \"matches\" : { \"p\" : [[171.12305905469063, 156.95179807964735, 860.7327547518794, 800.2922660141414, 33.378181753981295, 418.16166119119197, 238.4878684691688, 22.074413265657714, 823.373110550364, 766.0312973699932, 510.50356128073213, 329.79313952425764, 349.8370830592162, 366.2018625267435, 318.25896582882626, 476.2873289925989, 728.4728848019728, 397.38955680370276, 264.4331301069091, 611.8348665124321, 89.82193565913622, 106.1489545372179, 84.98564666412068, 502.74946813815825, 438.34391781200327, 567.5066082347257, 343.53617843638585, 587.6292381180874, 163.68555446124847, 578.9381599055471, 471.3667994364982, 173.59581978564316, 82.05400561264668, 48.38870097401947, 708.5615887008396, 530.8686667526773, 404.4333829604881, 191.52173614939272, 68.8536878223454, 406.84605521504756, 384.0768554259452, 346.174550684505, 393.1305604382964, 220.07488827971565, 805.8403278217114, 366.3850103434535, 804.9916371754952, 309.41931391843394, 315.39086608780536, 68.42539712042979, 69.67284626472919, 45.39746186199393, 551.7163683139802, 72.81886502537718, 721.8578752566658, 570.2877782905889, 523.9043018496417, 82.25885409298077, 285.32830473065957, 833.0999969706584, 488.01652663980855, 39.263256060713736, 15.575945550642679, 915.216891325789, 919.3904631155722, 548.133717319326, 698.0566890703692, 63.07688739484928, 321.3366395657757, 776.0413405890673, 541.8725336437923, 365.25129498012103, 287.4672480740971, 370.4650772389684, 47.952838346687365, 551.377841238683, 405.1894240210469, 175.7601763059497, 40.068928022781, 797.0343431056693, 22.945727804068234, 843.7445293453195, 21.164647225384613, 332.0418062444695, 360.3126877662274, 770.6681119135683, 250.84555316790397, 59.76915947685462, 97.19571422354953, 543.7500987066574, 709.8867311268556, 584.4486037691203, 380.6570352408362, 62.28122363677363, 159.1982118979488, 173.63832067767015, 277.02361194063064, 33.45156395427005, 776.9506373089529, 847.5174220131219, 455.67986230393467, 55.79316844736264, 847.35115283149, 245.58547668941253, 832.2236537476474, 930.8226284408346, 33.53279823180941, 483.6905003049121, 814.6972298092303, 507.44213370040274, 324.5021529729562, 775.1410874341566, 755.475965312445, 349.84138325349215, 334.09001098720245, 333.16762720157857, 670.394463771278, 494.1071547596349, 162.23149510286325, 499.3278732891611, 850.723276554019, 815.1342984279859, 338.55323276918017, 394.06469127476964, 230.64471849087911, 304.16929835628434, 16.55522328032088, 262.61189186137403, 460.4563793115815, 255.7419308392137, 454.0792695891289, 382.2407250113362, 356.0538589869422, 516.6135319938958, 630.3771031235173, 48.484406235190875, 725.8015222487111, 131.60497532134704, 66.78423721754605, 754.8257682285035, 764.8225843486234, 539.1259277240293, 573.573586291663, 420.416251837828, 517.2313172321382, 488.7011164491229, 366.17568914769043, 6.925335443284321, 366.94607491850803, 18.646633402983735, 182.81385508517994, 234.47022063275037, 29.063594648351447, 140.046703702103, 1006.2848571397088, 560.9820266388699, 510.9918455650709, 7.337786163515613, 26.128404939083346, 438.7759394247699, 446.3063648804466, 514.1548419203633, 282.2063624183979, 269.3905147296351, 264.57142238808177, 72.66277594841252, 10.313218315657519, 384.45375884359714, 398.6587762569152, 519.0898264062207, 42.00172794899021, 351.1457350228227, 378.78528003711875, 472.90363570443844, 418.21323474419023, 388.43340319238825, 799.187111692619, 46.754804534143204, 456.276076870086, 396.974483905084, 443.68885137827033, 278.1719232672639, 71.94423572505679, 998.3194735271813, 711.3326509664474, 334.26571564366094, 900.6671506229422, 989.3607610307592, 508.11014558564136, 440.2949427704672, 81.4474734937965, 249.49499187504313, 248.73610203808119, 36.02167969519655, 660.4599061143145, 524.1899990084946, 374.09370543369624, 526.4224529826911, 444.2452164019741, 24.601184217450406, 719.4038713802057, 394.6321943746266, 276.77402911438867, 428.0832791377563, 51.229616446889494, 328.533621920175, 68.69963728636654, 85.33673252166113, 34.123888662871146], [978.0252777356093, 971.8663992606907, 947.0713059809493, 944.9595533798821, 945.249561517209, 935.1024781365309, 931.5585214968474, 927.175991274436, 922.9688403862644, 913.27455027773, 904.5595170553852, 872.8999156267115, 848.7403635540587, 836.8400654006462, 836.1117355489945, 824.295625569228, 773.2235298279516, 755.7942991738619, 741.1253058479717, 732.1646122522768, 728.0456645704826, 692.9648773816687, 679.8723246288577, 656.5355831060267, 633.8031530857676, 599.4089366713139, 593.8389172891997, 584.6844662154614, 559.9640611756145, 548.3317568347461, 488.8989800945368, 367.43314046516275, 348.4236354897512, 310.14974825783634, 947.9087051751975, 779.3871933922006, 765.7088911731405, 674.5190269568714, 637.9319343719628, 626.9499622856142, 448.325004207902, 447.0511573198195, 966.6195590837596, 942.2166599147333, 923.8284367473168, 902.0202438683768, 900.5301453625179, 841.3430009480898, 814.5120876105091, 783.1952675725372, 776.8774155882896, 747.9889346817201, 734.4330284114639, 684.9530579605279, 636.8663291057633, 607.0816679043002, 504.02438895511847, 978.2236471357622, 977.170225468632, 975.0209714079434, 967.2608860388458, 957.8986185889654, 906.897665794816, 896.6528596175594, 879.9842987756784, 873.6444639020267, 872.2863244149177, 830.5345870926591, 818.4518908178569, 817.9098854509097, 770.161503341238, 763.9192587012369, 755.562545420643, 734.9929472392466, 707.5073608010908, 610.9760701434202, 594.9282316690511, 349.3514090541756, 268.20130256673997, 975.5432500465188, 951.99929192802, 937.5057023597109, 935.2544025134447, 911.6886191530778, 905.5491383010173, 887.5225910985915, 738.534513742552, 709.2517366688103, 681.7213227447014, 652.6812580827082, 644.5762430071056, 590.7089143074269, 457.1046253944237, 344.2842437597836, 989.9397766580277, 982.7911610124513, 978.832651459825, 971.7082638960617, 967.9070273065064, 964.014470782705, 959.0171001259595, 956.7337991833373, 948.0243401674935, 947.337872252392, 944.7415135544248, 942.794168355706, 937.6500069253557, 936.668485158205, 930.2976437612713, 927.0791237686483, 910.5731622324859, 906.3800229860691, 903.8744707670697, 903.2866984951219, 900.8017381111624, 891.5445266446773, 877.4901013736958, 875.6322234518384, 850.0412356100435, 847.7082763429115, 843.8501286914347, 815.8538955601229, 809.4786754887853, 801.9445885099874, 776.5483592126508, 747.6096323399481, 740.5707589686939, 739.5271358752163, 738.545388603933, 731.5042915037817, 729.0667948318635, 728.8845611933622, 724.9400719957754, 704.8879069057955, 690.8232733376245, 652.4936015224998, 647.5265300977342, 638.3635770643793, 618.3006744975121, 617.6769282765558, 606.8108457646454, 537.3444469577245, 536.7299521675358, 531.5306440713152, 514.4848704380262, 511.8595164039554, 447.40477332075227, 430.11636041086706, 428.12211671713874, 398.46060630059094, 358.63028163846366, 961.1116555569927, 954.9601658794038, 939.581958276999, 884.2615010918443, 797.753570560604, 777.7001484053379, 737.6582529989831, 690.1440651248181, 676.8429811448906, 646.1581012393972, 624.340459884801, 583.1822642882341, 713.6686563180868, 550.8229040162778, 991.5433695355206, 957.3726494517897, 823.2375201226511, 796.3508383028781, 602.0700400204137, 500.41067380833545, 807.22655077482, 793.1215068243541, 781.7500200083283, 679.0324232324601, 601.4898726002937, 889.5253551979473, 840.9292343975501, 792.8294484770405, 754.7262221837968, 661.1698182378697, 556.8003027817148, 356.1749667391436, 984.9652513720783, 889.0248262243122, 882.7310743154349, 863.5959878981773, 821.5418157795641, 820.2310333115945, 780.4814487069676, 711.5398326917763, 710.3498605695665, 692.853811946385, 646.5129964015115, 617.543472749252, 614.2334062388244, 600.2939832521104, 581.5020969866172, 513.0921317155154, 946.1428703105432, 878.5923981199924, 742.11766606765, 742.0220819838743, 710.9340176561552, 945.2013665537642, 899.0229922462316, 759.2142713931116, 363.95324397930625, 730.8114013147468]], \"q\" : [[169.27150322277336, 152.16743916856063, 858.5169687356007, 803.5343024705547, 36.10537661010264, 417.0105957279613, 239.49411297834396, 22.271350235925244, 823.1736627682568, 763.4508189321504, 505.9515891558603, 330.5196150663263, 350.59963791219735, 363.98140455458935, 316.0732085474312, 478.02143192451456, 730.5634134432248, 396.8184808141968, 267.60113373521466, 615.5389292675618, 92.2212090488308, 108.06618753580328, 87.78938750882607, 506.597970666197, 442.06959682306086, 573.5911489569339, 345.98618510280625, 592.9300290591538, 170.56348540119828, 587.4263096480538, 475.1216550002183, 182.9547364947613, 92.21789480386481, 57.77444344588577, 711.5106852763762, 532.4169184179741, 403.6941153838387, 197.23670744668817, 70.83014099435289, 409.4790014234113, 392.16914023499305, 354.4482178524416, 393.42472470313413, 220.52202982243546, 804.5624249150522, 367.47528963723306, 802.6876108216603, 306.0213488470267, 318.25948922906525, 68.7161543170823, 69.99828401312156, 49.3623687327989, 551.6636596591611, 77.20297448170277, 723.1818177194912, 571.5470292000508, 528.4793955629679, 81.97382448484399, 285.88658481641784, 831.9417855571697, 489.44983751651984, 40.167495652286554, 12.060148075167389, 917.2012422571586, 918.498694821876, 546.8885273319203, 698.9138014791597, 65.48063988440758, 324.1000429486056, 780.6675274287539, 544.3396175794434, 369.7015701182068, 291.30442694780464, 374.22746343515695, 52.16668584920961, 555.3582242324968, 408.1331601982925, 186.93183282219388, 50.94346025787756, 795.5009973587918, 25.052031996050175, 843.0798963720299, 24.855608996823598, 331.69908241724943, 361.4338090049405, 768.4918328756177, 253.398382906844, 61.78412408257081, 101.33875367085133, 544.8919470342368, 712.379118457823, 590.363413313838, 388.25921337323507, 69.9512208248881, 159.04131242944456, 173.04361337507302, 278.94956261913245, 30.883775116690874, 778.0668957158465, 846.8791156038158, 450.72771023027815, 54.03457070382866, 845.7795952568353, 249.14221642873835, 830.721277978661, 928.3204949900994, 35.7836459452939, 483.0112939648236, 813.9756058911473, 507.17426870588923, 322.45843111033065, 772.41110571299, 755.8879365513416, 349.8763130758495, 335.3900887142681, 333.2472906336731, 672.5770308257377, 492.9179760319602, 163.62748629974385, 498.38166364467793, 851.1280662676237, 815.2468551854785, 338.39535788422995, 395.4929375921515, 232.89348638789403, 309.54256132882887, 19.500278737202763, 264.43490968960845, 462.8454369755351, 259.4290288289489, 455.5818265255203, 385.09860029598366, 361.62769715791967, 519.1675042870951, 632.5472176803012, 50.04878271605436, 728.1632174134622, 136.07131914944594, 69.97421205519083, 758.0909943718015, 767.7117352128495, 546.0839816273583, 579.4244061170838, 425.1793387400305, 521.3849253714471, 492.4432015492143, 376.17222418739186, 9.837986379497238, 377.0321074550584, 29.483811340565783, 191.4478244380051, 233.70067111761256, 30.17855851385401, 143.03328999276582, 1007.6206257834822, 561.898228319378, 515.5319739661171, 11.156726023837365, 29.534780365702815, 441.7141038622954, 453.61487490261834, 516.5916486449466, 285.8611655928144, 271.00822676964043, 268.77535545057884, 72.45950338900299, 10.759896548832353, 387.96074568544265, 401.06535090142063, 520.4504439683958, 46.47981966170832, 352.5391811797239, 381.44382674063905, 475.77537194985007, 422.5338870935386, 392.8889855806881, 800.8525916285406, 47.49411743577437, 456.0405624228498, 396.47165387692826, 447.2392589802631, 284.30375112606447, 82.13443078927156, 997.8977138502257, 710.2647932831658, 334.34477056333264, 899.9212278902787, 989.761579958347, 509.10451139049644, 444.10346482145724, 86.18165856456338, 254.7148852599169, 251.62140825210443, 39.48583665033964, 660.0747651263913, 528.4028568916061, 378.7661306138723, 532.2048035450747, 452.0911572962997, 24.70553722765238, 722.878925754058, 399.1015995691348, 280.42847410759424, 429.98115955836954, 51.47975129158983, 328.3409343346685, 72.0973288864961, 96.76302991149636, 36.36393549386747], [982.3369369626589, 974.2588851002157, 957.2039756172003, 957.1125456546766, 945.2345814423754, 939.8486774663947, 935.8201044385205, 931.4656557331276, 933.1700585055149, 925.4333327971372, 910.4046188416029, 878.6329933695673, 853.8099639252189, 844.644759738659, 841.7476252561281, 832.4981214680954, 783.9477644645084, 764.8834747333143, 743.83913504775, 744.7267904272951, 730.5946802945866, 695.0008439304033, 682.2885411124385, 665.2208524532474, 642.6273350102546, 609.9162487615225, 599.8049498100972, 594.8700356259744, 563.839541353114, 561.0273106837561, 499.88512216795965, 372.3325074926557, 352.5073632379664, 315.24789606489105, 956.3198094231595, 786.7359774109673, 776.4407142772689, 681.548630458623, 641.5801953207625, 636.9732461397019, 455.96356948887905, 455.69000300168096, 972.783389646333, 946.5607034348609, 933.0864179789847, 909.0518887622885, 909.5637544900912, 845.5701418534788, 819.147810340792, 784.4147289049242, 778.5899047638957, 750.346201376825, 742.9265677441996, 687.7325908305633, 651.1668046630589, 618.3566171445057, 517.5308192621995, 979.6950676730987, 981.8988357763274, 988.6673300435028, 974.9576361390648, 959.5424095637967, 906.2763874145761, 906.156822436955, 889.2290110785378, 884.8574935893448, 880.6546909643794, 830.784790985155, 823.417818284081, 828.3086322570856, 779.0029619249449, 771.8969476433368, 760.5642352689415, 739.9266942272758, 709.4864760008769, 622.5315929885958, 602.6306454109719, 355.10686716622644, 275.4714331749741, 985.0958712445129, 950.7333732164775, 946.7019685822187, 936.7991397007834, 918.4591971173281, 913.8722718603581, 899.1781278660877, 741.8850532136456, 711.4700014297406, 685.214638271807, 664.341018919661, 656.6508116102447, 602.2025636218696, 465.6647173699228, 349.62322706192276, 992.3077697071611, 986.2298519121339, 983.0795111719104, 973.7369375265695, 978.7059067827664, 975.8922456704025, 964.2563944108844, 959.5462507989489, 957.7859540664501, 950.8531898207426, 955.4867350091297, 954.2371275022471, 936.7309299972994, 943.61275517505, 939.5371850606771, 933.9149351644949, 918.3502757489894, 917.2240213967686, 917.1675295570108, 908.9866374337938, 906.7460456395844, 897.2737314944065, 885.9201869322305, 885.9888409171457, 854.3009845327568, 857.7327304349947, 851.958694207517, 826.5294560149263, 816.6854861084012, 808.7549666536141, 780.6403470638384, 749.8278464433683, 741.5858535322703, 745.157355768109, 745.6056619436702, 734.405544511996, 736.2657234819849, 734.3340424068244, 729.5069189551613, 713.1313991061863, 703.2601074335691, 654.9780572694999, 658.4711975280301, 644.2662775248085, 621.7734910636523, 632.1301467376505, 622.4387762578663, 548.994958606687, 551.547633905062, 540.8065479599597, 526.1846804768675, 525.1593316976895, 452.35029079369633, 435.98577140954785, 437.8467570151502, 399.1567499441949, 365.41748536354777, 966.787120866076, 956.7561943814545, 941.8006010735388, 896.4986110920439, 808.771051925707, 784.4664887203847, 739.7574165108388, 693.2775821852708, 685.6417619707597, 654.4387741399228, 636.5689384494028, 585.313346416726, 719.8831667543698, 557.372864099065, 992.4884836541977, 957.4559386340463, 828.8854110133764, 802.6929026333523, 613.611838110077, 505.0823655234231, 813.2180497024684, 800.1139520605058, 786.8105123651048, 687.2414305412736, 608.7654190393049, 898.8327794509916, 845.5883755681833, 797.2072646058044, 764.2782427696991, 668.2859391271427, 561.4344749402572, 360.9235523044615, 993.8797833997248, 899.1066787846967, 889.1643818204168, 874.5308330404531, 838.4663795999758, 832.088177805439, 791.3037517879152, 711.2683250828698, 714.6821877745031, 698.3128281944373, 649.3058197315909, 629.8714869764796, 622.3225437352937, 607.3777963339929, 592.6243635027903, 522.4193875183939, 948.7569401280634, 891.7805589800616, 748.2831967958629, 748.4824593254132, 718.9899169683339, 948.1808211550742, 906.5119225479779, 759.306028212642, 368.7236981066088, 732.7383021525304]], \"w\" : [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0] } },\n" +
            "  { \"pGroupId\" : \"2214.0\", \"pId\" : \"z_2214.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_1\", \"qGroupId\" : \"2215.0\", \"qId\" : \"z_2215.0_box_40532_42182_3731_3654_0.274457\", \"consensusSetData\" : { \"index\" : 1, \"originalPId\" : \"z_2214.0_box_40532_42182_3731_3654_0.274457\", \"originalQId\" : \"z_2215.0_box_40532_42182_3731_3654_0.274457\" }, \"matches\" : { \"p\" : [[894.59072419264, 822.1919660393481, 819.7047349086921, 557.91131077205, 713.0529764330923, 651.7536848268458, 787.2977365555087, 741.0349243585003, 748.3600564167651, 647.1090585323427, 265.64005878610675, 702.7285954133803, 558.1429370395599, 689.2470635766418, 462.5571484026674, 775.7478698116323, 845.3577976783403, 740.5104990626371, 613.8739545213581, 270.38010178936213, 936.2259810271818, 577.7698427892954, 714.550955236831, 664.4624830448882, 615.7411971169731, 773.7055897154344, 286.34126107626037, 690.8320753525114, 671.5959704195834, 696.8115761881573, 341.6023606778633, 841.6294566808862, 889.168212658864, 783.8784543723164, 847.1355818395352, 553.2090547067362, 845.2707765673291, 591.8348470621978, 599.8435375164677, 943.8317072417267, 278.8839943224087, 280.56219564460406, 535.6820375359496, 618.1386553542155, 402.98644863058007, 664.3174181742245, 872.7353794596911, 754.2216755424527, 685.4972113952009, 735.5260678134838, 535.9037796439313, 669.9584556555347, 849.0381667900907, 284.3404702950737, 297.9913172134446, 566.260021382604, 680.2654245388727, 447.79030852439087, 317.1889460113916, 632.6301079009328, 313.2720898323629, 338.0533202971784, 892.1663125924165, 826.9940144846187, 786.7701357954727, 745.7533623332799, 889.5267817858472, 842.6439113819617, 425.22897718888197, 279.1899728605984, 286.8404582386026, 684.974945755146, 302.6212431550199, 455.69807832795817, 491.1403715342987, 236.31793702428536, 427.8622323966729, 293.67817647841605, 336.25209927034206, 114.28502149603143, 435.4528916204944, 442.9468028129413, 440.1298289467891, 309.73923115304945, 380.3442320150107, 133.21168956681373, 851.3528836567415, 674.5100756207371, 665.2839658882137, 685.8673512465376, 449.62005398995416, 839.704441952622, 727.9289631111606, 644.9198504832658, 340.72069519504333, 500.1500267886498, 676.7363881046168, 452.2685614525508, 639.4822638024797, 747.6596324239172, 590.9364221796006, 828.4198794393479, 782.2698737275065, 695.4046378576037, 383.7760298181267, 506.83007165205413, 812.7287399496126, 750.7447625308374, 589.1943764364374, 667.2069982709176, 449.21656576442825, 420.363490448334, 240.79404948792344, 621.259872091259, 638.4627261959024, 675.1339102524895, 608.2081956571607, 605.1194091914691, 799.3507422530091, 577.6480183254768], [447.8944473084415, 383.80695247591115, 349.00597847636243, 306.50651493161354, 275.6134252493161, 240.34396291939322, 205.0289903931287, 198.66549172485594, 197.39110231779762, 184.68254285046717, 171.45820435926538, 165.98182408621136, 159.90582080509148, 149.73126646620227, 148.56394276810266, 416.00476936168195, 390.2916204302317, 254.8307877833355, 213.3216770115062, 140.27281904676371, 387.1163641847729, 278.2979131287273, 266.60511029448475, 237.80650271631578, 236.11167682509702, 215.53806302465185, 198.93230717643766, 181.40309058537534, 166.12557098505113, 158.70142261207712, 134.72092260633315, 462.70336684594946, 455.5206397926001, 455.1292145178598, 407.39125153471963, 327.73320145504493, 318.3256082672058, 299.475325524744, 297.5128507828808, 269.2865416952111, 239.29375551976617, 224.14359023546893, 208.92263271238272, 206.12385410382856, 150.8913694701419, 137.96160511543238, 508.0879979372167, 452.2094010895687, 338.6485326419209, 332.70492177805505, 321.1733530772117, 258.30030424796126, 250.6769894762376, 241.30530423733566, 218.04704176550487, 194.31868215253988, 178.3767453918233, 171.90089361947057, 144.11919108782226, 143.2827194338338, 140.38288221685102, 124.95279228635786, 544.6668593740238, 506.78758934244803, 467.9768897905472, 463.5438521776728, 348.73128962352877, 262.9312954927384, 245.84911047266849, 243.30421219755715, 221.21700108336267, 215.73154268425807, 215.59556809593926, 207.22055026257195, 200.78986226846334, 175.76323568773705, 175.22178005032285, 173.8251312020429, 159.45391376075722, 150.45207955605017, 141.23329161152944, 139.9274441327168, 136.33232604917077, 136.29301423275055, 124.38479479968052, 120.45274060554912, 418.0205763077285, 218.34121144058076, 182.09867956968714, 147.5451674441899, 127.80452266435958, 255.8917265340179, 238.46071899791156, 228.21115050985844, 187.0422447221299, 202.6232088859481, 201.10497909871384, 193.89814797018622, 344.296958588108, 246.5062238845429, 161.96854943552344, 458.1891405732588, 366.08652855194964, 337.04187989246066, 260.69565835831446, 252.41831427329777, 234.37009551308577, 219.74911455342306, 217.83896397268458, 211.41899871231504, 204.66313907220365, 177.9976714827022, 176.17637893853257, 268.8560111531239, 211.74234669055525, 251.49546404303268, 253.031150811801, 222.55641464872588, 200.39441247409118, 154.3236520835266]], \"q\" : [[968.0595572191934, 887.7314662187589, 887.0790359545839, 619.222580836253, 774.6839283548687, 708.8696076489539, 850.0728946973568, 802.096781302607, 807.5446309938452, 708.073726591579, 321.77402593228015, 760.4650859414337, 609.8072421414228, 746.2225195191936, 514.6331580637848, 841.1289791343312, 914.2546476516759, 803.3563099437939, 673.8817248811135, 327.38026662972163, 1004.5563581868914, 639.5158432178836, 774.2898353761772, 722.2714750499111, 675.4953275205547, 835.0273396582293, 343.8541640597029, 748.2420512404707, 727.3085223366377, 755.7032236901571, 397.6131619883993, 915.0227961088814, 965.0244653259552, 856.4379405264056, 916.2673777301349, 616.5660414423389, 911.9635563129654, 651.8391921089622, 661.6130764848308, 1007.9133609715946, 336.96563738147137, 337.85095645732275, 591.3034811963797, 674.0756448204362, 456.1405695014013, 717.6668951295705, 950.3195398229227, 821.925062866137, 750.4284399069171, 798.6256343925294, 596.7559936802876, 731.5645679997633, 913.8857006028667, 343.2350180491949, 355.51280153995754, 622.6926628123134, 736.7289345224283, 503.7226838705957, 373.94619732987206, 689.4195128221693, 369.908010161538, 391.79663405035865, 969.041037952234, 898.0947482013421, 860.0825473181555, 815.7030006141383, 954.1957131820316, 907.888100386851, 484.39911845048664, 337.7106234512185, 346.36671596760397, 744.6997440720173, 359.94815048362784, 512.3908910430315, 545.752291639245, 293.57382757385466, 483.4226678769681, 349.50091630601986, 390.45925334754656, 171.15460812053362, 490.85846455272343, 496.9226209922501, 492.91667570221375, 366.96991512137726, 436.1936408596011, 186.5361482841225, 923.6114116122958, 734.1654548219113, 723.0777212474064, 742.7808552214452, 500.7914123175579, 901.0477666180104, 791.3773042811083, 702.6549573821787, 398.9335108282343, 556.0716189678543, 737.5621613292021, 507.64349313565157, 705.287177255342, 810.4567912808159, 646.7256789847096, 900.9973339796173, 850.9634871255357, 759.9854186767687, 441.1236846661275, 564.24987021525, 877.2054301468451, 809.1476097319457, 648.1958923919078, 728.7463104467762, 504.0968542553471, 474.34914935691546, 297.69087276884636, 682.0011868025305, 695.2104389296114, 732.604707644142, 666.461095084258, 664.201528684978, 861.5055420336349, 635.8037462901079], [320.22304184398405, 261.668469768822, 220.24158971005903, 194.53348125286115, 161.10838704391935, 124.92099435226999, 84.16445132351647, 80.32656937598233, 74.31722618944536, 67.60701234129499, 73.91987386266894, 49.15319269139561, 51.68554863463682, 33.173751455569246, 42.832514532528194, 293.78858578325105, 266.9671236486068, 135.44089197914784, 99.54079931304166, 45.06084668928642, 253.39070409821932, 166.97695797105933, 144.90879224679043, 123.0792690776286, 122.28736876492515, 95.21472887648919, 103.23313476023894, 66.59617650161728, 50.139892529271954, 42.238751723752614, 35.85713741518638, 335.50579063986555, 323.3212489539198, 330.2532992225685, 280.7560651905841, 217.02913265432278, 190.8719337094959, 186.58284374643316, 183.30369712834454, 141.1455619802398, 140.54235906831278, 126.29550794141022, 99.78676102316793, 92.60842573099703, 50.91099487496411, 23.78078645191984, 377.46108698271865, 327.4965195013793, 220.95905140280843, 212.08299333376488, 208.27854927725082, 140.526677504705, 126.59223553591232, 142.75742699897228, 124.03914977659598, 87.02593316338752, 65.89498543455205, 67.32301750383661, 46.93435523209812, 31.045118590929437, 42.559628675831355, 26.94764256853306, 413.2117088267213, 380.38030594411595, 341.0757186024111, 338.2211682161425, 222.7980008007471, 139.98875433238993, 140.27857829919782, 146.41276345524503, 120.87316714140432, 102.37630428151515, 120.03102885923462, 100.95397581082132, 94.1445506361438, 82.39887443775967, 70.54184197486468, 76.96019265110485, 59.83319782455391, 61.360190527781384, 37.084600871320404, 36.07272844748238, 32.44532540042723, 38.18536863942388, 25.19943304660204, 34.03344016320508, 290.04047818526453, 103.16473798891865, 67.5786523706014, 35.22358362184706, 24.80972140426644, 130.49154254989102, 119.42739812370165, 115.87519785236489, 87.97937706316125, 95.6469708530922, 85.06713391309643, 88.34438617279457, 224.4940397191291, 124.67930410793363, 52.34295823204902, 328.2763722571867, 241.1972546737518, 219.2978679347151, 156.97017486472274, 145.66312919817858, 113.28614770203747, 98.80713974157919, 106.2768789885165, 96.05933655772145, 102.45830171741254, 75.51235144006333, 82.57801337565151, 155.60524464935312, 99.94516204877816, 134.08942598461596, 139.73709798366502, 107.61917093717432, 80.95821819975079, 40.396565847279206]], \"w\" : [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0] } }\n" +
            "]";

    private static final String ALIGNED_TILES =
            "{\"transformIdToSpecMap\":{},\"tileIdToSpecMap\": {\n" +
            "\"z_2213.0_box_40532_42182_3731_3654_0.274457\":{\"tileId\":\"z_2213.0_box_40532_42182_3731_3654_0.274457\",\"layout\":{\"sectionId\":\"2213.0\",\"temca\":\"n/a\",\"camera\":\"n/a\",\"imageRow\":0,\"imageCol\":0,\"stageX\":0.0,\"stageY\":0.0,\"rotation\":0.0},\"z\":2213.0,\"minX\":-4.0,\"minY\":82.0,\"maxX\":1024.0,\"maxY\":1099.0,\"width\":1024.0,\"height\":1003.0,\"mipmapLevels\":{},\"channels\":[{\"minIntensity\":0.0,\"maxIntensity\":255.0,\"mipmapLevels\":{\"0\":{\"imageUrl\":\"http://renderer.int.janelia.org:8080/render-ws/v1/owner/flyTEM/project/trautmane_fafb_fold/stack/rough_tiles_k/z/2213.0/box/40532,42182,3731,3654,0.27445725006700616/tiff-image?v=1520517608140&name=z2213.tif\"}}}],\"transforms\":{\"type\":\"list\",\"specList\":[{\"type\":\"leaf\",\"className\":\"mpicbg.trakem2.transform.AffineModel2D\",\"dataString\":\"0.996903566325 0.011961038489 -0.007011053456 1.001331472600 2.826188792061 82.418401343809\"}]},\"meshCellSize\":64.0},\n" +
            "\"z_2214.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_0\":{\"tileId\":\"z_2214.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_0\",\"layout\":{\"sectionId\":\"2214.0\",\"temca\":\"n/a\",\"camera\":\"n/a\",\"imageRow\":0,\"imageCol\":0,\"stageX\":0.0,\"stageY\":0.0,\"rotation\":0.0},\"z\":2214.0,\"minX\":-4.0,\"minY\":88.0,\"maxX\":1030.0,\"maxY\":1109.0,\"width\":1024.0,\"height\":1003.0,\"mipmapLevels\":{},\"channels\":[{\"minIntensity\":0.0,\"maxIntensity\":255.0,\"mipmapLevels\":{\"0\":{\"imageUrl\":\"http://renderer.int.janelia.org:8080/render-ws/v1/owner/flyTEM/project/trautmane_fafb_fold/stack/rough_tiles_k/z/2214.0/box/40532,42182,3731,3654,0.27445725006700616/tiff-image?v=1520517608140&name=z2214.tif\"}}}],\"transforms\":{\"type\":\"list\",\"specList\":[{\"type\":\"leaf\",\"className\":\"mpicbg.trakem2.transform.AffineModel2D\",\"dataString\":\"0.992305681556 0.020810813968 -0.017101539636 0.996093537088 12.382766856618 88.937856270370\"}]},\"meshCellSize\":64.0},\n" +
            "\"z_2214.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_1\":{\"tileId\":\"z_2214.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_1\",\"layout\":{\"sectionId\":\"2214.0\",\"temca\":\"n/a\",\"camera\":\"n/a\",\"imageRow\":0,\"imageCol\":0,\"stageX\":0.0,\"stageY\":0.0,\"rotation\":0.0},\"z\":2214.0,\"minX\":46.0,\"minY\":-38.0,\"maxX\":1106.0,\"maxY\":992.0,\"width\":1024.0,\"height\":1003.0,\"mipmapLevels\":{},\"channels\":[{\"minIntensity\":0.0,\"maxIntensity\":255.0,\"mipmapLevels\":{\"0\":{\"imageUrl\":\"http://renderer.int.janelia.org:8080/render-ws/v1/owner/flyTEM/project/trautmane_fafb_fold/stack/rough_tiles_k/z/2214.0/box/40532,42182,3731,3654,0.27445725006700616/tiff-image?v=1520517608140&name=z2214.tif\"}}}],\"transforms\":{\"type\":\"list\",\"specList\":[{\"type\":\"leaf\",\"className\":\"mpicbg.trakem2.transform.AffineModel2D\",\"dataString\":\"1.003639484401 -0.038036992488 0.031574912432 0.987179233126 46.200978381096 0.000000000000\"}]},\"meshCellSize\":64.0},\n" +
            "\"z_2215.0_box_40532_42182_3731_3654_0.274457\":{\"tileId\":\"z_2215.0_box_40532_42182_3731_3654_0.274457\",\"layout\":{\"sectionId\":\"2215.0\",\"temca\":\"n/a\",\"camera\":\"n/a\",\"imageRow\":0,\"imageCol\":0,\"stageX\":0.0,\"stageY\":0.0,\"rotation\":0.0},\"z\":2215.0,\"minX\":-4.0,\"minY\":81.0,\"maxX\":1019.0,\"maxY\":1097.0,\"width\":1024.0,\"height\":1003.0,\"mipmapLevels\":{},\"channels\":[{\"minIntensity\":0.0,\"maxIntensity\":255.0,\"mipmapLevels\":{\"0\":{\"imageUrl\":\"http://renderer.int.janelia.org:8080/render-ws/v1/owner/flyTEM/project/trautmane_fafb_fold/stack/rough_tiles_k/z/2215.0/box/40532,42182,3731,3654,0.27445725006700616/tiff-image?v=1520517608140&name=z2215.tif\"}}}],\"transforms\":{\"type\":\"list\",\"specList\":[{\"type\":\"leaf\",\"className\":\"mpicbg.trakem2.transform.AffineModel2D\",\"dataString\":\"0.994389794917 0.008003291771 -0.004724167600 1.003945659504 0.000000000000 81.000932941486\"}]},\"meshCellSize\":64.0}\n" +
            "}}";

    private static final Logger LOG = LoggerFactory.getLogger(ConsensusWarpFieldBuilderTest.class);

}
