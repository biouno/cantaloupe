package edu.illinois.library.cantaloupe.processor;

import edu.illinois.library.cantaloupe.Application;
import edu.illinois.library.cantaloupe.image.SourceFormat;
import edu.illinois.library.cantaloupe.request.OutputFormat;
import edu.illinois.library.cantaloupe.request.Quality;
import edu.illinois.library.cantaloupe.request.Region;
import edu.illinois.library.cantaloupe.request.Rotation;
import edu.illinois.library.cantaloupe.request.Size;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.media.jai.Interpolation;
import javax.media.jai.JAI;
import javax.media.jai.OpImage;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.TransposeDescriptor;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.renderable.ParameterBlock;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * A collection of helper methods for working with BufferedImages.
 */
class ProcessorUtil {

    public static BufferedImage cropImage(final BufferedImage inputImage,
                                          final Region region) {
        BufferedImage croppedImage;
        if (region.isFull()) {
            croppedImage = inputImage;
        } else {
            int x, y, requestedWidth, requestedHeight, width, height;
            if (region.isPercent()) {
                x = (int) Math.round((region.getX() / 100.0) *
                        inputImage.getWidth());
                y = (int) Math.round((region.getY() / 100.0) *
                        inputImage.getHeight());
                requestedWidth = (int) Math.round((region.getWidth() / 100.0) *
                        inputImage.getWidth());
                requestedHeight = (int) Math.round((region.getHeight() / 100.0) *
                        inputImage.getHeight());
            } else {
                x = Math.round(region.getX());
                y = Math.round(region.getY());
                requestedWidth = Math.round(region.getWidth());
                requestedHeight = Math.round(region.getHeight());
            }
            // BufferedImage.getSubimage() will protest if asked for more
            // width/height than is available
            width = (x + requestedWidth > inputImage.getWidth()) ?
                    inputImage.getWidth() - x : requestedWidth;
            height = (y + requestedHeight > inputImage.getHeight()) ?
                    inputImage.getHeight() - y : requestedHeight;
            croppedImage = inputImage.getSubimage(x, y, width, height);
        }
        return croppedImage;
    }

    public static BufferedImage filterImage(final BufferedImage inputImage,
                                            final Quality quality) {
        BufferedImage filteredImage = inputImage;
        if (quality != Quality.COLOR && quality != Quality.DEFAULT) {
            switch (quality) {
                case GRAY:
                    filteredImage = new BufferedImage(inputImage.getWidth(),
                            inputImage.getHeight(),
                            BufferedImage.TYPE_BYTE_GRAY);
                    break;
                case BITONAL:
                    filteredImage = new BufferedImage(inputImage.getWidth(),
                            inputImage.getHeight(),
                            BufferedImage.TYPE_BYTE_BINARY);
                    break;
            }
            Graphics2D g2d = filteredImage.createGraphics();
            g2d.drawImage(inputImage, 0, 0, null);
        }
        return filteredImage;
    }

    public static RenderedOp filterImage(RenderedOp inputImage, Quality quality) {
        RenderedOp filteredImage = inputImage;
        if (quality != Quality.COLOR && quality != Quality.DEFAULT) {
            // convert to grayscale
            ParameterBlock pb = new ParameterBlock();
            pb.addSource(inputImage);
            double[][] matrixRgb = { { 0.114, 0.587, 0.299, 0 } };
            double[][] matrixRgba = { { 0.114, 0.587, 0.299, 0, 0 } };
            if (OpImage.getExpandedNumBands(inputImage.getSampleModel(),
                    inputImage.getColorModel()) == 4) {
                pb.add(matrixRgba);
            } else {
                pb.add(matrixRgb);
            }
            filteredImage = JAI.create("bandcombine", pb, null);
            if (quality == Quality.BITONAL) {
                pb = new ParameterBlock();
                pb.addSource(filteredImage);
                pb.add(1.0 * 128);
                filteredImage = JAI.create("binarize", pb);
            }
        }
        return filteredImage;
    }

    public static Dimension getSize(ImageInputStream inputStream,
                                    SourceFormat sourceFormat)
            throws ProcessorException {
        // get width & height (without reading the entire image into memory)
        Iterator<ImageReader> iter = ImageIO.
                getImageReadersBySuffix(sourceFormat.getPreferredExtension());
        if (iter.hasNext()) {
            ImageReader reader = iter.next();
            int width, height;
            try {
                reader.setInput(inputStream);
                width = reader.getWidth(reader.getMinIndex());
                height = reader.getHeight(reader.getMinIndex());
            } catch (IOException e) {
                throw new ProcessorException(e.getMessage(), e);
            } finally {
                reader.dispose();
            }
            return new Dimension(width, height);
        }
        return null;
    }

    public static Set<OutputFormat> imageIoOutputFormats() {
        final String[] writerMimeTypes = ImageIO.getWriterMIMETypes();
        Set<OutputFormat> outputFormats = new HashSet<>();
        for (OutputFormat outputFormat : OutputFormat.values()) {
            for (String mimeType : writerMimeTypes) {
                if (outputFormat.getMediaType().equals(mimeType.toLowerCase())) {
                    outputFormats.add(outputFormat);
                }
            }
        }
        return outputFormats;
    }

    /**
     * Writes an image to the given output stream.
     *
     * @param image Image to write
     * @param outputFormat Format of the output image
     * @param outputStream Stream to which to write the image
     * @throws IOException
     */
    public static void outputImage(final BufferedImage image,
                                   final OutputFormat outputFormat,
                                   final OutputStream outputStream)
            throws IOException {
        switch (outputFormat) {
            case JPG:
                // TurboJpegImageWriter is used automatically if libjpeg-turbo
                // is available in java.library.path:
                // https://github.com/geosolutions-it/imageio-ext/wiki/TurboJPEG-plugin
                float quality = Application.getConfiguration().
                        getFloat("ImageIoProcessor.jpg.quality", 0.7f);
                Iterator iter = ImageIO.getImageWritersByFormatName("jpeg");
                ImageWriter writer = (ImageWriter) iter.next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
                param.setCompressionType("JPEG");
                ImageOutputStream os = ImageIO.createImageOutputStream(outputStream);
                writer.setOutput(os);
                IIOImage iioImage = new IIOImage(image, null, null);
                writer.write(null, iioImage, param);
                writer.dispose();
                break;
            default:
                ImageIO.write(image, outputFormat.getExtension(),
                        outputStream);
                break;
        }
    }

    public static RenderedOp rotateImage(RenderedOp inputImage,
                                         Rotation rotation) {
        // do mirroring
        RenderedOp mirroredImage = inputImage;
        if (rotation.shouldMirror()) {
            ParameterBlock pb = new ParameterBlock();
            pb.addSource(inputImage);
            pb.add(TransposeDescriptor.FLIP_HORIZONTAL);
            mirroredImage = JAI.create("transpose", pb);
        }
        // do rotation
        RenderedOp rotatedImage = mirroredImage;
        if (rotation.getDegrees() > 0) {
            ParameterBlock pb = new ParameterBlock();
            pb.addSource(rotatedImage);
            pb.add(mirroredImage.getWidth() / 2.0f);
            pb.add(mirroredImage.getHeight() / 2.0f);
            pb.add((float) Math.toRadians(rotation.getDegrees()));
            pb.add(Interpolation.getInstance(Interpolation.INTERP_BILINEAR));
            rotatedImage = JAI.create("rotate", pb);
        }
        return rotatedImage;
    }

    public static BufferedImage rotateImage(final BufferedImage inputImage,
                                            final Rotation rotation) {
        // do mirroring
        BufferedImage mirroredImage = inputImage;
        if (rotation.shouldMirror()) {
            AffineTransform tx = AffineTransform.getScaleInstance(-1, 1);
            tx.translate(-mirroredImage.getWidth(null), 0);
            AffineTransformOp op = new AffineTransformOp(tx,
                    AffineTransformOp.TYPE_BILINEAR);
            mirroredImage = op.filter(inputImage, null);
        }
        // do rotation
        BufferedImage rotatedImage = mirroredImage;
        if (rotation.getDegrees() > 0) {
            double radians = Math.toRadians(rotation.getDegrees());
            int sourceWidth = mirroredImage.getWidth();
            int sourceHeight = mirroredImage.getHeight();
            int canvasWidth = (int) Math.round(Math.abs(sourceWidth *
                    Math.cos(radians)) + Math.abs(sourceHeight *
                    Math.sin(radians)));
            int canvasHeight = (int) Math.round(Math.abs(sourceHeight *
                    Math.cos(radians)) + Math.abs(sourceWidth *
                    Math.sin(radians)));

            // note: operations happen in reverse order of declaration
            AffineTransform tx = new AffineTransform();
            // 3. translate the image to the center of the "canvas"
            tx.translate(canvasWidth / 2, canvasHeight / 2);
            // 2. rotate it
            tx.rotate(radians);
            // 1. translate the image so that it is rotated about the center
            tx.translate(-sourceWidth / 2, -sourceHeight / 2);

            rotatedImage = new BufferedImage(canvasWidth, canvasHeight,
                    inputImage.getType());
            Graphics2D g2d = rotatedImage.createGraphics();
            RenderingHints hints = new RenderingHints(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHints(hints);
            g2d.drawImage(mirroredImage, tx, null);
        }
        return rotatedImage;
    }

    public static RenderedOp scaleImage(RenderedOp inputImage, Size size) {
        RenderedOp scaledImage;
        if (size.getScaleMode() == Size.ScaleMode.FULL) {
            scaledImage = inputImage;
        } else {
            float xScale = 1.0f;
            float yScale = 1.0f;
            if (size.getScaleMode() == Size.ScaleMode.ASPECT_FIT_WIDTH) {
                xScale = (float) size.getWidth() / inputImage.getWidth();
                yScale = xScale;
            } else if (size.getScaleMode() == Size.ScaleMode.ASPECT_FIT_HEIGHT) {
                yScale = (float) size.getHeight() / inputImage.getHeight();
                xScale = yScale;
            } else if (size.getScaleMode() == Size.ScaleMode.NON_ASPECT_FILL) {
                xScale = (float) size.getWidth() / inputImage.getWidth();
                yScale = (float) size.getHeight() / inputImage.getHeight();
            } else if (size.getScaleMode() == Size.ScaleMode.ASPECT_FIT_INSIDE) {
                double hScale = (float) size.getWidth() / inputImage.getWidth();
                double vScale = (float) size.getHeight() / inputImage.getHeight();
                xScale = (float) (inputImage.getWidth() * Math.min(hScale, vScale));
                yScale = (float) (inputImage.getHeight() * Math.min(hScale, vScale));
            } else if (size.getPercent() != null) {
                xScale = size.getPercent() / 100.0f;
                yScale = xScale;
            }
            ParameterBlock pb = new ParameterBlock();
            pb.addSource(inputImage);
            pb.add(xScale);
            pb.add(yScale);
            pb.add(0.0f);
            pb.add(0.0f);
            pb.add(Interpolation.getInstance(Interpolation.INTERP_BILINEAR));
            scaledImage = JAI.create("scale", pb);
        }
        return scaledImage;
    }

    /**
     * Scales an image using an AffineTransform.
     *
     * @param inputImage
     * @param size
     * @return
     */
    public static BufferedImage scaleImageWithAffineTransform(
            final BufferedImage inputImage, final Size size) {
        BufferedImage scaledImage;
        if (size.getScaleMode() == Size.ScaleMode.FULL) {
            scaledImage = inputImage;
        } else {
            double xScale = 0.0f, yScale = 0.0f;
            if (size.getScaleMode() == Size.ScaleMode.ASPECT_FIT_WIDTH) {
                xScale = size.getWidth() / (double) inputImage.getWidth();
                yScale = xScale;
            } else if (size.getScaleMode() == Size.ScaleMode.ASPECT_FIT_HEIGHT) {
                yScale = size.getHeight() / (double) inputImage.getHeight();
                xScale = yScale;
            } else if (size.getScaleMode() == Size.ScaleMode.NON_ASPECT_FILL) {
                xScale = size.getWidth() / (double) inputImage.getWidth();
                yScale = size.getHeight() / (double) inputImage.getHeight();
            } else if (size.getScaleMode() == Size.ScaleMode.ASPECT_FIT_INSIDE) {
                double hScale = (double) size.getWidth() /
                        (double) inputImage.getWidth();
                double vScale = (double) size.getHeight() /
                        (double) inputImage.getHeight();
                xScale = inputImage.getWidth() * Math.min(hScale, vScale);
                yScale = inputImage.getHeight() * Math.min(hScale, vScale);
            } else if (size.getPercent() != null) {
                xScale = size.getPercent() / 100.0;
                yScale = xScale;
            }
            int width = (int) Math.round(inputImage.getWidth() * xScale);
            int height = (int) Math.round(inputImage.getHeight() * yScale);
            scaledImage = new BufferedImage(width, height, inputImage.getType());
            AffineTransform at = new AffineTransform();
            at.scale(xScale, yScale);
            AffineTransformOp scaleOp = new AffineTransformOp(at,
                    AffineTransformOp.TYPE_BILINEAR);
            scaledImage = scaleOp.filter(inputImage, scaledImage);
        }
        return scaledImage;
    }

    /**
     * Scales an image using Graphics2D.
     *
     * @param inputImage
     * @param size
     * @return
     */
    public static BufferedImage scaleImageWithG2d(final BufferedImage inputImage,
                                                  final Size size) {
        BufferedImage scaledImage;
        if (size.getScaleMode() == Size.ScaleMode.FULL) {
            scaledImage = inputImage;
        } else {
            int width = 0, height = 0;
            if (size.getScaleMode() == Size.ScaleMode.ASPECT_FIT_WIDTH) {
                width = size.getWidth();
                height = inputImage.getHeight() * width /
                        inputImage.getWidth();
            } else if (size.getScaleMode() == Size.ScaleMode.ASPECT_FIT_HEIGHT) {
                height = size.getHeight();
                width = inputImage.getWidth() * height /
                        inputImage.getHeight();
            } else if (size.getScaleMode() == Size.ScaleMode.NON_ASPECT_FILL) {
                width = size.getWidth();
                height = size.getHeight();
            } else if (size.getScaleMode() == Size.ScaleMode.ASPECT_FIT_INSIDE) {
                double hScale = (double) size.getWidth() /
                        (double) inputImage.getWidth();
                double vScale = (double) size.getHeight() /
                        (double) inputImage.getHeight();
                width = (int) Math.round(inputImage.getWidth() *
                        Math.min(hScale, vScale));
                height = (int) Math.round(inputImage.getHeight() *
                        Math.min(hScale, vScale));
            } else if (size.getPercent() != null) {
                width = (int) Math.round(inputImage.getWidth() *
                        (size.getPercent() / 100.0));
                height = (int) Math.round(inputImage.getHeight() *
                        (size.getPercent() / 100.0));
            }
            scaledImage = new BufferedImage(width, height,
                    inputImage.getType());
            Graphics2D g2d = scaledImage.createGraphics();
            RenderingHints hints = new RenderingHints(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHints(hints);
            g2d.drawImage(inputImage, 0, 0, width, height, null);
            g2d.dispose();
        }
        return scaledImage;
    }

}