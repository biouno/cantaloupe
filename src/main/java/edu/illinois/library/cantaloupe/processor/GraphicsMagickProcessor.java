package edu.illinois.library.cantaloupe.processor;

import edu.illinois.library.cantaloupe.config.Configuration;
import edu.illinois.library.cantaloupe.config.ConfigurationFactory;
import edu.illinois.library.cantaloupe.image.Color;
import edu.illinois.library.cantaloupe.image.Crop;
import edu.illinois.library.cantaloupe.image.Format;
import edu.illinois.library.cantaloupe.image.Operation;
import edu.illinois.library.cantaloupe.image.OperationList;
import edu.illinois.library.cantaloupe.image.Rotate;
import edu.illinois.library.cantaloupe.image.Scale;
import edu.illinois.library.cantaloupe.image.Transpose;
import org.apache.commons.lang3.StringUtils;
import org.im4java.process.ArrayListOutputConsumer;
import org.im4java.process.Pipe;
import org.im4java.process.ProcessStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Dimension;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>Processor using the GraphicsMagick <code>gm</code> command-line tool.
 * Tested with version 1.3.21; other versions may or may not work.</p>
 *
 * <p>This class does not implement <var>FileProcessor</var> because testing
 * indicates that reading from streams is significantly faster.</p>
 *
 * <p>This processor does not respect the
 * {@link edu.illinois.library.cantaloupe.resource.AbstractResource#PRESERVE_METADATA_CONFIG_KEY}
 * setting because telling GM not to preserve metadata means tell it not to
 * preserve an ICC profile. Thus, metadata always passes through.</p>
 */
class GraphicsMagickProcessor extends AbstractMagickProcessor
        implements StreamProcessor {

    private static Logger logger = LoggerFactory.
            getLogger(GraphicsMagickProcessor.class);

    static final String BACKGROUND_COLOR_CONFIG_KEY =
            "GraphicsMagickProcessor.background_color";
    static final String NORMALIZE_CONFIG_KEY =
            "GraphicsMagickProcessor.normalize";
    static final String SHARPEN_CONFIG_KEY = "GraphicsMagickProcessor.sharpen";
    static final String PATH_TO_BINARIES_CONFIG_KEY =
            "GraphicsMagickProcessor.path_to_binaries";

    // Lazy-initialized by getFormats()
    private static HashMap<Format, Set<Format>> supportedFormats;

    /**
     * @param binaryName Name of an executable
     * @return
     */
    private static String getPath(String binaryName) {
        String path = ConfigurationFactory.getInstance().
                getString(PATH_TO_BINARIES_CONFIG_KEY);
        if (path != null && path.length() > 0) {
            path = StringUtils.stripEnd(path, File.separator) + File.separator +
                    binaryName;
        } else {
            path = binaryName;
        }
        return path;
    }

    /**
     * @return Map of available output formats for all known source formats,
     * based on information reported by <code>gm version</code>.
     */
    private static HashMap<Format, Set<Format>> getFormats() {
        if (supportedFormats == null) {
            final Set<Format> formats = new HashSet<>();
            final Set<Format> outputFormats = new HashSet<>();

            // Get the output of the `gm version` command, which contains
            // a list of all optional formats.
            final ProcessBuilder pb = new ProcessBuilder();
            final List<String> command = new ArrayList<>();
            command.add(getPath("gm"));
            command.add("version");
            pb.command(command);
            final String commandString = StringUtils.join(pb.command(), " ");

            try {
                logger.info("getFormats(): invoking {}", commandString);
                final Process process = pb.start();

                try (final InputStream processInputStream = process.getInputStream()) {
                    BufferedReader stdInput = new BufferedReader(
                            new InputStreamReader(processInputStream));
                    String s;
                    boolean read = false;
                    while ((s = stdInput.readLine()) != null) {
                        if (s.contains("Feature Support")) {
                            read = true; // start reading
                        } else if (s.contains("Host type:")) {
                            break; // stop reading
                        }
                        if (read) {
                            s = s.trim();
                            if (s.startsWith("JPEG-2000 ") && s.endsWith(" yes")) {
                                formats.add(Format.JP2);
                                outputFormats.add(Format.JP2);
                            } else if (s.startsWith("JPEG ") && s.endsWith(" yes")) {
                                formats.add(Format.JPG);
                                outputFormats.add(Format.JPG);
                            } else if (s.startsWith("PNG ") && s.endsWith(" yes")) {
                                formats.add(Format.PNG);
                                outputFormats.add(Format.PNG);
                            } else if (s.startsWith("Ghostscript") && s.endsWith(" yes")) {
                                outputFormats.add(Format.PDF);
                            } else if (s.startsWith("TIFF ") && s.endsWith(" yes")) {
                                formats.add(Format.TIF);
                                outputFormats.add(Format.TIF);
                            } else if (s.startsWith("WebP ") && s.endsWith(" yes")) {
                                formats.add(Format.WEBP);
                                outputFormats.add(Format.WEBP);
                            }
                        }
                    }
                    process.waitFor();

                    // Add formats that are not listed in the output of
                    // "gm version" but are definitely available
                    // (http://www.graphicsmagick.org/formats.html)
                    formats.add(Format.BMP);
                    formats.add(Format.GIF);
                    // GIF output is buggy in GM 1.3.21 (returned images have
                    // improper dimensions).
                    //outputFormats.add(Format.GIF);
                } catch (InterruptedException e) {
                    logger.error("getFormats(): ", e.getMessage());
                }
            } catch (IOException e) {
                logger.error("getFormats(): ", e.getMessage());
            }

            supportedFormats = new HashMap<>();
            for (Format format : formats) {
                supportedFormats.put(format, outputFormats);
            }
        }
        return supportedFormats;
    }

    GraphicsMagickProcessor() {
        final Configuration config = ConfigurationFactory.getInstance();
        final String path = config.getString(PATH_TO_BINARIES_CONFIG_KEY);
        if (path != null && path.length() > 0) {
            ProcessStarter.setGlobalSearchPath(path);
        }
    }

    @Override
    public Set<Format> getAvailableOutputFormats() {
        Set<Format> formats = getFormats().get(format);
        if (formats == null) {
            formats = new HashSet<>();
        }
        return formats;
    }

    private List<String> getConvertArguments(final OperationList ops,
                                             final Dimension fullSize) {
        final List<String> args = new ArrayList<>();
        args.add("gm");
        args.add("convert");
        args.add(format.getPreferredExtension() + ":-"); // read from stdin

        // Normalization needs to happen before cropping to maintain the
        // intensity of cropped regions relative to the full image.
        final Configuration config = ConfigurationFactory.getInstance();
        if (config.getBoolean(NORMALIZE_CONFIG_KEY, false)) {
            args.add("-normalize");
        }

        for (Operation op : ops) {
            if (op instanceof Crop) {
                Crop crop = (Crop) op;
                if (!crop.isNoOp()) {
                    args.add("-crop");
                    if (crop.getShape().equals(Crop.Shape.SQUARE)) {
                        final int shortestSide =
                                Math.min(fullSize.width, fullSize.height);
                        final int x = (fullSize.width - shortestSide) / 2;
                        final int y = (fullSize.height - shortestSide) / 2;
                        final String string = String.format("%dx%d+%d+%d",
                                shortestSide, shortestSide, x, y);
                        args.add(string);
                    } else if (crop.getUnit().equals(Crop.Unit.PERCENT)) {
                        // GM doesn't support cropping x/y by percentage
                        // (only width/height), so we have to calculate them.
                        final int x = Math.round(crop.getX() * fullSize.width);
                        final int y = Math.round(crop.getY() * fullSize.height);
                        final int width = Math.round(crop.getWidth() * 100);
                        final int height = Math.round(crop.getHeight() * 100);
                        final String string = String.format("%dx%d+%d+%d%%",
                                width, height, x, y);
                        args.add(string);
                    } else {
                        final String string = String.format("%dx%d+%d+%d",
                                Math.round(crop.getWidth()),
                                Math.round(crop.getHeight()),
                                Math.round(crop.getX()),
                                Math.round(crop.getY()));
                        args.add(string);
                    }
                }
            } else if (op instanceof Scale) {
                Scale scale = (Scale) op;
                if (!scale.isNoOp()) {
                    args.add("-resize");
                    if (scale.getPercent() != null) {
                        final String arg = (scale.getPercent() * 100) + "%";
                        args.add(arg);
                    } else if (scale.getMode() == Scale.Mode.ASPECT_FIT_WIDTH) {
                        args.add(scale.getWidth().toString());
                    } else if (scale.getMode() == Scale.Mode.ASPECT_FIT_HEIGHT) {
                        args.add(scale.getHeight().toString());
                    } else if (scale.getMode() == Scale.Mode.NON_ASPECT_FILL) {
                        final String arg = String.format("%dx%d!",
                                scale.getWidth(), scale.getHeight());
                        args.add(arg);
                    } else if (scale.getMode() == Scale.Mode.ASPECT_FIT_INSIDE) {
                        final String arg = String.format("%dx%d",
                                scale.getWidth(), scale.getHeight());
                        args.add(arg);
                    }
                }
            } else if (op instanceof Transpose) {
                switch ((Transpose) op) {
                    case HORIZONTAL:
                        args.add("-flop");
                        break;
                    case VERTICAL:
                        args.add("-flip");
                        break;
                }
            } else if (op instanceof Rotate) {
                final Rotate rotate = (Rotate) op;
                if (!rotate.isNoOp()) {
                    // If the output format supports transparency, make the
                    // background transparent. Otherwise, use a
                    // user-configurable background color.
                    args.add("-background");
                    if (ops.getOutputFormat().supportsTransparency()) {
                        args.add("none");
                    } else {
                        args.add(config.getString(BACKGROUND_COLOR_CONFIG_KEY, "black"));
                    }
                    args.add("-rotate");
                    args.add(Double.toString(rotate.getDegrees()));
                }
            } else if (op instanceof Color) {
                switch ((Color) op) {
                    case GRAY:
                        args.add("-colorspace");
                        args.add("Gray");
                        break;
                    case BITONAL:
                        args.add("-monochrome");
                        break;
                }
            }
        }

        args.add("-depth");
        args.add("8");

        // Apply the sharpen operation, if present.
        final double sharpenValue = config.getDouble(SHARPEN_CONFIG_KEY, 0);
        if (sharpenValue > 0) {
            args.add("-unsharp");
            args.add(Double.toString(sharpenValue));
        }

        // Write to stdout.
        args.add(ops.getOutputFormat().getPreferredExtension() + ":-");

        return args;
    }

    @Override
    public ImageInfo getImageInfo() throws ProcessorException {
        try (InputStream inputStream = streamSource.newInputStream()) {
            final List<String> args = new ArrayList<>();
            args.add("gm");
            args.add("identify");
            args.add("-ping");
            args.add("-format");
            args.add("%w\n%h\n%r");
            args.add(format.getPreferredExtension() + ":-");

            ArrayListOutputConsumer consumer = new ArrayListOutputConsumer();

            final ProcessStarter cmd = new ProcessStarter();
            cmd.setInputProvider(new Pipe(inputStream, null));
            cmd.setOutputConsumer(consumer);
            logger.info("getImageInfo(): invoking {}",
                    StringUtils.join(args, " ").replace("\n", ""));
            cmd.run(args);

            final ArrayList<String> output = consumer.getOutput();
            final int width = Integer.parseInt(output.get(0));
            final int height = Integer.parseInt(output.get(1));
            return new ImageInfo(width, height, width, height,
                    getSourceFormat());
        } catch (Exception e) {
            throw new ProcessorException(e.getMessage(), e);
        }
    }

    @Override
    public void process(final OperationList ops,
                        final ImageInfo imageInfo,
                        final OutputStream outputStream)
            throws ProcessorException {
        if (!getAvailableOutputFormats().contains(ops.getOutputFormat())) {
            throw new UnsupportedOutputFormatException();
        }

        try (InputStream inputStream = streamSource.newInputStream()) {
            final List<String> args = getConvertArguments(ops, imageInfo.getSize());
            final ProcessStarter cmd = new ProcessStarter();
            cmd.setInputProvider(new Pipe(inputStream, null));
            cmd.setOutputConsumer(new Pipe(null, outputStream));
            logger.info("process(): invoking {}", StringUtils.join(args, " "));
            cmd.run(args);
        } catch (Exception e) {
            throw new ProcessorException(e.getMessage(), e);
        }
    }

}
