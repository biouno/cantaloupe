package edu.illinois.library.cantaloupe.resource.iiif.v2;

import edu.illinois.library.cantaloupe.config.ConfigurationFactory;
import edu.illinois.library.cantaloupe.image.Format;
import edu.illinois.library.cantaloupe.image.Identifier;
import edu.illinois.library.cantaloupe.processor.Processor;
import edu.illinois.library.cantaloupe.processor.ProcessorException;
import edu.illinois.library.cantaloupe.resource.AbstractResource;
import edu.illinois.library.cantaloupe.resource.iiif.Feature;
import edu.illinois.library.cantaloupe.resource.iiif.ImageInfoUtil;
import edu.illinois.library.cantaloupe.script.DelegateScriptDisabledException;
import edu.illinois.library.cantaloupe.script.ScriptEngineFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.ScriptException;
import java.awt.Dimension;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

abstract class ImageInfoFactory {

    private static Logger logger = LoggerFactory.
            getLogger(ImageInfoFactory.class);

    static final String MIN_TILE_SIZE_CONFIG_KEY =
            "endpoint.iiif.min_tile_size";

    /** Minimum size that will be used in info.json "sizes" keys. */
    private static final int MIN_SIZE = 64;

    /** Delegate script method that returns a JSON object (Ruby hash)
     * containing additional keys to add to the information response. */
    private static final String SERVICE_DELEGATE_METHOD =
            "extra_iiif2_information_response_keys";

    /** Will be populated in the static initializer. */
    private static final Set<ServiceFeature> SUPPORTED_SERVICE_FEATURES =
            new HashSet<>();

    static {
        SUPPORTED_SERVICE_FEATURES.addAll(Arrays.asList(
                ServiceFeature.SIZE_BY_CONFINED_WIDTH_HEIGHT,
                ServiceFeature.SIZE_BY_WHITELISTED,
                ServiceFeature.BASE_URI_REDIRECT,
                ServiceFeature.CANONICAL_LINK_HEADER,
                ServiceFeature.CORS,
                ServiceFeature.JSON_LD_MEDIA_TYPE,
                ServiceFeature.PROFILE_LINK_HEADER));
    }

    static ImageInfo<String,Object> newImageInfo(
            final Identifier identifier,
            final String imageUri,
            final Processor processor,
            final edu.illinois.library.cantaloupe.processor.ImageInfo cacheInfo)
            throws ProcessorException {
        // We want to use the orientation-aware full size, which takes the
        // embedded orientation into account.
        final Dimension virtualSize = cacheInfo.getOrientationSize();

        // Create a Map instance, which will eventually be serialized to JSON
        // and returned in the response body.
        final ImageInfo<String,Object> imageInfo = new ImageInfo<>();
        imageInfo.put("@context", "http://iiif.io/api/image/2/context.json");
        imageInfo.put("@id", imageUri);
        imageInfo.put("protocol", "http://iiif.io/api/image");
        imageInfo.put("width", virtualSize.width);
        imageInfo.put("height", virtualSize.height);

        // sizes -- this will be a 2^n series that will work for both multi-
        // and monoresolution images.
        final List<ImageInfo.Size> sizes = new ArrayList<>();
        imageInfo.put("sizes", sizes);

        final int maxReductionFactor =
                ImageInfoUtil.maxReductionFactor(virtualSize, MIN_SIZE);
        for (double i = 2; i <= Math.pow(2, maxReductionFactor); i *= 2) {
            final int width = (int) Math.round(virtualSize.width / i);
            final int height = (int) Math.round(virtualSize.height / i);
            if (width < MIN_SIZE || height < MIN_SIZE) {
                break;
            }
            ImageInfo.Size size = new ImageInfo.Size(width, height);
            sizes.add(0, size);
        }

        // tiles -- this is not a canonical listing of tiles that are
        // actually encoded in the image, but rather a hint to the client as
        // to what can be delivered efficiently.
        final Set<Dimension> uniqueTileSizes = new HashSet<>();

        final int minTileSize = ConfigurationFactory.getInstance().
                getInt(MIN_TILE_SIZE_CONFIG_KEY, 1024);

        // Find a tile width and height. If the image is not tiled,
        // calculate a tile size close to MIN_TILE_SIZE_CONFIG_KEY pixels.
        // Otherwise, use the smallest multiple of the tile size above that
        // of image resolution 0.
        final List<ImageInfo.Tile> tiles = new ArrayList<>();
        imageInfo.put("tiles", tiles);

        final edu.illinois.library.cantaloupe.processor.ImageInfo.Image firstImage =
                cacheInfo.getImages().get(0);

        // Find the virtual tile size based on the virtual full image size.
        final Dimension virtualTileSize = firstImage.getOrientationTileSize();

        if (cacheInfo.getImages().size() == 1 &&
                virtualTileSize.equals(virtualSize)) {
            uniqueTileSizes.add(
                    ImageInfoUtil.smallestTileSize(virtualSize, minTileSize));
        } else {
            for (edu.illinois.library.cantaloupe.processor.ImageInfo.Image image : cacheInfo.getImages()) {
                uniqueTileSizes.add(
                        ImageInfoUtil.smallestTileSize(virtualSize,
                                image.getOrientationTileSize(), minTileSize));
            }
        }
        for (Dimension uniqueTileSize : uniqueTileSizes) {
            final ImageInfo.Tile tile = new ImageInfo.Tile();
            tile.width = uniqueTileSize.width;
            tile.height = uniqueTileSize.height;
            // Add every scale factor up to 2^n.
            for (int i = 0; i <= maxReductionFactor; i++) {
                tile.scaleFactors.add((int) Math.pow(2, i));
            }
            tiles.add(tile);
        }

        final List<Object> profile = new ArrayList<>();
        imageInfo.put("profile", profile);

        final String complianceUri = ComplianceLevel.getLevel(
                SUPPORTED_SERVICE_FEATURES,
                processor.getSupportedFeatures(),
                processor.getSupportedIiif2_0Qualities(),
                processor.getAvailableOutputFormats()).getUri();
        profile.add(complianceUri);

        // formats
        Map<String, Object> profileMap = new HashMap<>();
        Set<String> formatStrings = new HashSet<>();
        for (Format format : processor.getAvailableOutputFormats()) {
            formatStrings.add(format.getPreferredExtension());
        }
        profileMap.put("formats", formatStrings);
        profile.add(profileMap);

        // maxArea (maxWidth and maxHeight are currently not supported)
        final int maxPixels = ConfigurationFactory.getInstance().
                getInt(AbstractResource.MAX_PIXELS_CONFIG_KEY, 0);
        if (maxPixels > 0) {
            profileMap.put("maxArea", maxPixels);
        }

        // qualities
        Set<String> qualityStrings = new HashSet<>();
        for (Quality quality : processor.getSupportedIiif2_0Qualities()) {
            qualityStrings.add(quality.toString().toLowerCase());
        }
        profileMap.put("qualities", qualityStrings);

        // supports
        Set<String> featureStrings = new HashSet<>();
        for (Feature feature : processor.getSupportedFeatures()) {
            featureStrings.add(feature.getName());
        }
        for (Feature feature : SUPPORTED_SERVICE_FEATURES) {
            featureStrings.add(feature.getName());
        }
        profileMap.put("supports", featureStrings);

        // additional keys
        try {
            final Map keyMap = (Map) ScriptEngineFactory.getScriptEngine().
                    invoke(SERVICE_DELEGATE_METHOD, identifier.toString());
            imageInfo.putAll(keyMap);
        } catch (DelegateScriptDisabledException e) {
            logger.info("Delegate script disabled; skipping service " +
                    "information.");
        } catch (ScriptException | IOException e) {
            logger.error(e.getMessage());
        }

        return imageInfo;
    }

}
