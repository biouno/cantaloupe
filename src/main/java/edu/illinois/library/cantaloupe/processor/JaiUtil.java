package edu.illinois.library.cantaloupe.processor;

import edu.illinois.library.cantaloupe.image.Color;
import edu.illinois.library.cantaloupe.image.Crop;
import edu.illinois.library.cantaloupe.image.Sharpen;
import edu.illinois.library.cantaloupe.image.Rotate;
import edu.illinois.library.cantaloupe.image.Scale;
import edu.illinois.library.cantaloupe.image.Transpose;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.media.jai.Interpolation;
import javax.media.jai.JAI;
import javax.media.jai.LookupTableJAI;
import javax.media.jai.OpImage;
import javax.media.jai.PlanarImage;
import javax.media.jai.ROIShape;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.TransposeDescriptor;
import java.awt.Dimension;
import java.awt.RenderingHints;
import java.awt.image.renderable.ParameterBlock;

abstract class JaiUtil {

    private static Logger logger = LoggerFactory.getLogger(JaiUtil.class);

    /**
     * Reduces an image's component size to 8 bits if greater.
     *
     * @param inImage Image to reduce
     * @return Reduced image, or the input image if it already is 8 bits or
     *         less.
     */
    static RenderedOp convertTo8Bits(RenderedOp inImage) {
        if (inImage.getColorModel().getComponentSize(0) != 8) {
            // This seems to clip the color depth to 8-bit. Not sure why it
            // works.
            final ParameterBlock pb = new ParameterBlock();
            pb.addSource(inImage);
            inImage = JAI.create("format", pb, inImage.getRenderingHints());
        }
        return inImage;
    }

    /**
     * @param inImage Image to crop
     * @param crop    Crop operation
     * @return Cropped image, or the input image if the given operation is a
     *         no-op.
     */
    static RenderedOp cropImage(RenderedOp inImage, Crop crop) {
        return cropImage(inImage, crop, new ReductionFactor(0));
    }

    /**
     * Crops the given image taking into account a reduction factor
     * (<code>reductionFactor</code>). In other words, the dimensions of the
     * input image have already been halved <code>reductionFactor</code> times
     * but the given region is relative to the full-sized image.
     *
     * @param inImage Image to crop
     * @param crop    Crop operation
     * @param rf      Number of times the dimensions of
     *                <code>inImage</code> have already been halved
     *                relative to the full-sized version
     * @return Cropped image, or the input image if the given operation is a
     *         no-op.
     */
    static RenderedOp cropImage(RenderedOp inImage, Crop crop,
                                ReductionFactor rf) {
        if (!crop.isNoOp()) {
            // Calculate the region x, y, and actual width/height.
            final double scale = rf.getScale();
            final double regionX = crop.getX() * scale;
            final double regionY = crop.getY() * scale;
            final double regionWidth = crop.getWidth() * scale;
            final double regionHeight = crop.getHeight() * scale;

            float x, y, requestedWidth, requestedHeight, croppedWidth,
                    croppedHeight;
            if (crop.getShape().equals(Crop.Shape.SQUARE)) {
                final int shortestSide =
                        Math.min(inImage.getWidth(), inImage.getHeight());
                x = (inImage.getWidth() - shortestSide) / 2;
                y = (inImage.getHeight() - shortestSide) / 2;
                requestedWidth = requestedHeight = shortestSide;
            } else if (crop.getUnit().equals(Crop.Unit.PERCENT)) {
                x = (int) Math.round(regionX * inImage.getWidth());
                y = (int) Math.round(regionY * inImage.getHeight());
                requestedWidth = (int) Math.round(regionWidth *
                        inImage.getWidth());
                requestedHeight = (int) Math.round(regionHeight *
                        inImage.getHeight());
            } else {
                x = (int) Math.round(regionX);
                y = (int) Math.round(regionY);
                requestedWidth = (int) Math.round(regionWidth);
                requestedHeight = (int) Math.round(regionHeight);
            }
            // prevent width/height from exceeding the image bounds
            croppedWidth = (x + requestedWidth > inImage.getWidth()) ?
                    inImage.getWidth() - x : requestedWidth;
            croppedHeight = (y + requestedHeight > inImage.getHeight()) ?
                    inImage.getHeight() - y : requestedHeight;

            logger.debug("cropImage(): x: {}; y: {}; width: {}; height: {}",
                    x, y, croppedWidth, croppedHeight);

            final ParameterBlock pb = new ParameterBlock();
            pb.addSource(inImage);
            pb.add(x);
            pb.add(y);
            pb.add(croppedWidth);
            pb.add(croppedHeight);
            inImage = JAI.create("crop", pb);
        }
        return inImage;
    }

    /**
     * @param inImage Image to get a RenderedOp of.
     * @return RenderedOp
     */
    static RenderedOp getAsRenderedOp(PlanarImage inImage) {
        final ParameterBlock pb = new ParameterBlock();
        pb.addSource(inImage);
        return JAI.create("null", pb);
    }

    /**
     * Linearly scales the pixel values of the given image into an 8-bit range.
     *
     * @param inImage Image to rescale.
     * @return Rescaled image.
     */
    static RenderedOp rescalePixels(RenderedOp inImage) {
        final int targetSize = 8;
        final int componentSize = inImage.getColorModel().getComponentSize(0);
        if (componentSize != targetSize) {
            ParameterBlock pb = new ParameterBlock();
            pb.addSource(inImage);

            final double multiplier = Math.pow(2, targetSize) / Math.pow(2, componentSize);
            // Per-band constants to multiply by.
            final double[] constants = {multiplier};
            pb.add(constants);

            // Per-band offsets to be added.
            final double[] offsets = {0};
            pb.add(offsets);

            logger.debug("rescalePixels(): multiplying by {}", multiplier);
            inImage = JAI.create("rescale", pb);
        }
        return inImage;
    }

    /**
     * @param inImage Image to rotate
     * @param rotate  Rotate operation
     * @return Rotated image, or the input image if the given rotate operation
     *         is a no-op.
     */
    static RenderedOp rotateImage(RenderedOp inImage, Rotate rotate) {
        if (!rotate.isNoOp()) {
            logger.debug("rotateImage(): rotating {} degrees",
                    rotate.getDegrees());

            ParameterBlock pb = new ParameterBlock();
            pb.addSource(inImage);
            pb.add(inImage.getWidth() / 2.0f);                   // x origin
            pb.add(inImage.getHeight() / 2.0f);                  // y origin
            pb.add((float) Math.toRadians(rotate.getDegrees())); // radians
            pb.add(Interpolation.getInstance(Interpolation.INTERP_BILINEAR));
            inImage = JAI.create("rotate", pb);
        }
        return inImage;
    }

    /**
     * Scales an image using JAI, taking an already-applied reduction factor
     * into account. (In other words, the dimensions of the input image have
     * already been halved <code>reductionFactor</code> times but the given
     * size is relative to the full-sized image.)
     *
     * @param inImage       Image to scale
     * @param scale         Requested size ignoring any reduction factor
     * @param interpolation Interpolation
     * @param rf            Reduction factor that has already been applied to
     *                      <code>inImage</code>
     * @return Scaled image, or the input image if the given scale is a no-op.
     */
    static RenderedOp scaleImage(RenderedOp inImage, Scale scale,
                                 Interpolation interpolation,
                                 ReductionFactor rf) {
        if (!scale.isNoOp()) {
            final int sourceWidth = inImage.getWidth();
            final int sourceHeight = inImage.getHeight();
            final Dimension scaledSize = scale.getResultingSize(
                    new Dimension(sourceWidth, sourceHeight));

            double xScale = scaledSize.width / (double) sourceWidth;
            double yScale = scaledSize.height / (double) sourceHeight;
            if (scale.getPercent() != null) {
                xScale = scale.getPercent() / rf.getScale();
                yScale = scale.getPercent() / rf.getScale();
            }

            // Enforce a minimum scale of 3 pixels on a side.
            // OpenSeadragon has been known to request smaller.
            double minXScale = 3f / (double) sourceWidth;
            double minYScale = 3f / (double) sourceHeight;
            xScale = (xScale < minXScale) ? minXScale : xScale;
            yScale = (yScale < minYScale) ? minYScale : yScale;

            logger.debug("scaleImage(): width: {}%; height: {}%",
                    xScale * 100, yScale * 100);
            final ParameterBlock pb = new ParameterBlock();
            pb.addSource(inImage);
            pb.add((float) xScale);
            pb.add((float) yScale);
            pb.add(0.0f);
            pb.add(0.0f);
            pb.add(interpolation);
            inImage = JAI.create("scale", pb);
        }
        return inImage;
    }

    /**
     * Better-quality alternative to {@link #scaleImage(RenderedOp, Scale,
     * Interpolation, ReductionFactor)}. Unfortunately, that method has to
     * remain around due to a bug in JAI (see inline comment in
     * {@link JaiProcessor#process}).
     *
     * @param inImage Image to scale
     * @param scale   Requested size ignoring any reduction factor
     * @param rf      Reduction factor that has already been applied to
     *                <code>inImage</code>
     * @return Scaled image, or the input image if the given scale is a no-op.
     */
    static RenderedOp scaleImageUsingSubsampleAverage(RenderedOp inImage,
                                                      Scale scale,
                                                      ReductionFactor rf) {
        if (!scale.isNoOp()) {
            final int sourceWidth = inImage.getWidth();
            final int sourceHeight = inImage.getHeight();
            final Dimension scaledSize = scale.getResultingSize(
                    new Dimension(sourceWidth, sourceHeight));

            double xScale = scaledSize.width / (double) sourceWidth;
            double yScale = scaledSize.height / (double) sourceHeight;
            if (scale.getPercent() != null) {
                xScale = scale.getPercent() / rf.getScale();
                yScale = scale.getPercent() / rf.getScale();
            }

            // Enforce a minimum scale of 3 pixels on a side.
            double minXScale = 3f / (double) sourceWidth;
            double minYScale = 3f / (double) sourceHeight;
            xScale = (xScale < minXScale) ? minXScale : xScale;
            yScale = (yScale < minYScale) ? minYScale : yScale;

            logger.debug("scaleImage(): width: {}%; height: {}%",
                    xScale * 100, yScale * 100);
            final ParameterBlock pb = new ParameterBlock();
            pb.addSource(inImage);
            pb.add(xScale);
            pb.add(yScale);
            pb.add(0.0); // X translation
            pb.add(0.0); // Y translation

            final RenderingHints hints = new RenderingHints(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            inImage = JAI.create("SubsampleAverage", pb, hints);
        }
        return inImage;
    }

    /**
     * @param inImage Image to sharpen.
     * @param sharpen The sharpen operation.
     * @return Sharpened image.
     */
    static RenderedOp sharpenImage(RenderedOp inImage,
                                   final Sharpen sharpen) {
        if (!sharpen.isNoOp()) {
            ParameterBlock pb = new ParameterBlock();
            pb.addSource(inImage);
            pb.add(null);
            pb.add(sharpen.getAmount());
            inImage = JAI.create("UnsharpMask", pb);
        }
        return inImage;
    }

    /**
     * @see #stretchContrast(RenderedOp, Crop)
     * @param inImage Image to stretch.
     * @return Stretched image.
     */
    static RenderedOp stretchContrast(RenderedOp inImage) {
        return stretchContrast(inImage,
                new Crop(0, 0, inImage.getWidth(), inImage.getHeight()));
    }

    /**
     * <p>Linearly stretches the contrast of an image to occupy the full range
     * of intensities. Histogram gaps will result.</p>
     *
     * <p>Does not work with indexed images.</p>
     *
     * @param inImage Image to stretch.
     * @param sampleArea Area of the image to sample.
     * @return Stretched image.
     */
    static RenderedOp stretchContrast(RenderedOp inImage, Crop sampleArea) {
        final int numLevels =
                (int) Math.pow(2, inImage.getColorModel().getComponentSize(0));
        final byte[] blut = new byte[numLevels];
        for (int i = 0; i < numLevels; i++) {
            blut[i] = (byte) (i >> 4);
        }

        final Dimension fullSize = new Dimension(inImage.getWidth(),
                inImage.getHeight());

        ParameterBlock pb = new ParameterBlock();
        pb.addSource(inImage);
        pb.add(new ROIShape(sampleArea.getRectangle(fullSize)));
        pb.add(1); // Horizontal sampling rate
        pb.add(1); // Vertical sampling rate
        RenderedOp op = JAI.create("extrema", pb);

        // Retrieve both the maximum and minimum pixel value.
        final double[][] extrema = (double[][]) op.getProperty("extrema");
        int min = numLevels, max = 0;
        for (int i = 0; i < inImage.getNumBands(); i++) {
            if (extrema[0][i] < min) {
                min = (int) extrema[0][i];
            }
            if (extrema[1][i] > max) {
                max = (int) extrema[1][i];
            }
        }

        double scale = 255f / (float) (max - min);
        for (int i = min; i <= max; i++) {
            blut[i] = (byte) ((i - min) * scale);
        }

        // Clamp any input values outside min/max range.
        for (int i = 0; i < min; i++) {
            blut[i] = 0;
        }
        for (int i = max; i < numLevels; i++) {
            blut[i] = (byte) 255;
        }

        pb = new ParameterBlock();
        pb.addSource(inImage);
        pb.add(new LookupTableJAI(blut));
        return JAI.create("lookup", pb);
    }

    /**
     * @param inImage Image to filter
     * @param color   Color transform operation
     * @return Transformed image, or the input image if the given operation
     *         is a no-op.
     */
    @SuppressWarnings({"deprecation"}) // really, JAI itself is basically deprecated
    static RenderedOp transformColor(RenderedOp inImage, Color color) {
        RenderedOp filteredImage = inImage;
        if (!color.isNoOp()) {
            // convert to grayscale
            ParameterBlock pb = new ParameterBlock();
            pb.addSource(inImage);
            final int numBands = OpImage.getExpandedNumBands(
                    inImage.getSampleModel(), inImage.getColorModel());
            double[][] matrix = new double[1][numBands + 1];
            matrix[0][0] = 0.114;
            matrix[0][1] = 0.587;
            matrix[0][2] = 0.299;
            for (int i = 3; i <= numBands; i++) {
                matrix[0][i] = 0;
            }
            pb.add(matrix);
            filteredImage = JAI.create("bandcombine", pb, null);
            if (color == Color.BITONAL) {
                pb = new ParameterBlock();
                pb.addSource(filteredImage);
                pb.add(1.0 * 128);
                filteredImage = JAI.create("binarize", pb);
            }
        }
        return filteredImage;
    }

    /**
     * @param inImage   Image to transpose.
     * @param transpose The transpose operation.
     * @return Transposed image, or the input image if the given transpose
     *         operation is a no-op.
     */
    static RenderedOp transposeImage(RenderedOp inImage, Transpose transpose) {
        ParameterBlock pb = new ParameterBlock();
        pb.addSource(inImage);
        switch (transpose) {
            case HORIZONTAL:
                logger.debug("transposeImage(): horizontal");
                pb.add(TransposeDescriptor.FLIP_HORIZONTAL);
                break;
            case VERTICAL:
                logger.debug("transposeImage(): vertical");
                pb.add(TransposeDescriptor.FLIP_VERTICAL);
                break;
        }
        return JAI.create("transpose", pb);
    }

}
