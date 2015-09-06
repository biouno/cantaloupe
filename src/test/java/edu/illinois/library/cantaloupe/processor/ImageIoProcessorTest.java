package edu.illinois.library.cantaloupe.processor;

import edu.illinois.library.cantaloupe.image.ImageInfo;
import edu.illinois.library.cantaloupe.image.SourceFormat;
import edu.illinois.library.cantaloupe.request.OutputFormat;
import edu.illinois.library.cantaloupe.request.Quality;
import junit.framework.TestCase;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ImageIoProcessorTest extends TestCase {

    ImageIoProcessor instance;

    public void setUp() {
        instance = new ImageIoProcessor();
    }

    public void testGetImageInfo() throws Exception {
        // get an ImageInfo representing an image file
        File file = getFixture("escher_lego.jpg");
        InputStream is = new FileInputStream(file);
        SourceFormat sourceFormat = SourceFormat.JPG;
        String baseUri = "http://example.org/base/";
        ImageInfo info = instance.getImageInfo(is, sourceFormat, baseUri);

        assertEquals("http://iiif.io/api/image/2/context.json", info.getContext());
        assertEquals(baseUri, info.getId());
        assertEquals("http://iiif.io/api/image", info.getProtocol());
        assertEquals(594, (int) info.getWidth());
        assertEquals(522, (int) info.getHeight());

        List<Map<String, Integer>> sizes = info.getSizes();
        assertNull(sizes);

        List<Map<String,Object>> tiles = info.getTiles();
        assertNull(tiles);

        List<Object> profile = info.getProfile();
        assertEquals("http://iiif.io/api/image/2/level2.json", profile.get(0));

        Set<String> actualFormats = (Set<String>)((Map)profile.get(1)).get("formats");
        Set<String> expectedFormats = new HashSet<String>();
        expectedFormats.add("gif");
        expectedFormats.add("jpg");
        expectedFormats.add("png");
        expectedFormats.add("tif");
        assertEquals(expectedFormats, actualFormats);

        Set<String> actualQualities = (Set<String>)((Map)profile.get(1)).get("qualities");
        Set<String> expectedQualities = new HashSet<String>();
        for (Quality quality : Quality.values()) {
            expectedQualities.add(quality.toString().toLowerCase());
        }
        assertEquals(expectedQualities, actualQualities);

        Set<String> actualSupports = (Set<String>)((Map)profile.get(1)).get("supports");
        Set<String> expectedSupports = new HashSet<String>();
        expectedSupports.add("baseUriRedirect");
        expectedSupports.add("mirroring");
        expectedSupports.add("regionByPx");
        expectedSupports.add("rotationArbitrary");
        expectedSupports.add("rotationBy90s");
        expectedSupports.add("sizeByWhListed");
        expectedSupports.add("sizeByForcedWh");
        expectedSupports.add("sizeByH");
        expectedSupports.add("sizeByPct");
        expectedSupports.add("sizeByW");
        expectedSupports.add("sizeWh");
        assertEquals(expectedSupports, actualSupports);
    }

    public void testGetSupportedOutputFormats() {
        HashSet<OutputFormat> expectedFormats = new HashSet<OutputFormat>();
        expectedFormats.add(OutputFormat.GIF);
        expectedFormats.add(OutputFormat.JPG);
        expectedFormats.add(OutputFormat.PNG);
        expectedFormats.add(OutputFormat.TIF);
        assertEquals(expectedFormats, instance.getSupportedOutputFormats());
    }

    public void testProcess() {
        // This is not easily testable in code, so will have to be tested by
        // human eyes.
    }

    public void testToString() {
        assertEquals("ImageIoProcessor", instance.toString());
    }

    private File getFixture(String filename) throws IOException {
        File directory = new File(".");
        String cwd = directory.getCanonicalPath();
        Path testPath = Paths.get(cwd, "src", "test", "java", "edu",
                "illinois", "library", "cantaloupe", "test", "fixtures");
        return new File(testPath + File.separator + filename);
    }

}