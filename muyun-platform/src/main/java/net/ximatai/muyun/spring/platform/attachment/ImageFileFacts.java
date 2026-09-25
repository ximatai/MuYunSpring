package net.ximatai.muyun.spring.platform.attachment;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.gif.GifHeaderDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;
import com.drew.metadata.webp.WebpDirectory;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Encoded raster dimensions, extracted without decoding pixels or exposing parser types to business code. */
record ImageFileFacts(String mimeType, int width, int height) {
    static ImageFileFacts read(byte[] content) {
        try {
            return from(ImageMetadataReader.readMetadata(new ByteArrayInputStream(content)));
        } catch (IOException | ImageProcessingException exception) {
            throw new IllegalArgumentException("image dimensions are unavailable", exception);
        }
    }

    private static ImageFileFacts from(Metadata metadata) {
        JpegDirectory jpeg = metadata.getFirstDirectoryOfType(JpegDirectory.class);
        if (jpeg != null) return dimensions("image/jpeg", jpeg, JpegDirectory.TAG_IMAGE_WIDTH, JpegDirectory.TAG_IMAGE_HEIGHT);
        PngDirectory png = metadata.getFirstDirectoryOfType(PngDirectory.class);
        if (png != null) return dimensions("image/png", png, PngDirectory.TAG_IMAGE_WIDTH, PngDirectory.TAG_IMAGE_HEIGHT);
        GifHeaderDirectory gif = metadata.getFirstDirectoryOfType(GifHeaderDirectory.class);
        if (gif != null) return dimensions("image/gif", gif, GifHeaderDirectory.TAG_IMAGE_WIDTH, GifHeaderDirectory.TAG_IMAGE_HEIGHT);
        WebpDirectory webp = metadata.getFirstDirectoryOfType(WebpDirectory.class);
        if (webp != null) return dimensions("image/webp", webp, WebpDirectory.TAG_IMAGE_WIDTH, WebpDirectory.TAG_IMAGE_HEIGHT);
        throw new IllegalArgumentException("image dimensions are unavailable");
    }

    private static ImageFileFacts dimensions(String mimeType, Directory directory, int widthTag, int heightTag) {
        Integer width = directory.getInteger(widthTag);
        Integer height = directory.getInteger(heightTag);
        if (width == null || height == null || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions are unavailable");
        }
        return new ImageFileFacts(mimeType, width, height);
    }
}
