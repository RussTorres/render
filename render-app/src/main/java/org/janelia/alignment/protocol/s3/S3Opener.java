package org.janelia.alignment.protocol.s3;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.google.common.net.MediaType;

import ij.ImagePlus;

import java.awt.Image;
import java.awt.Toolkit;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper to the ij.io.Opener for render.
 *
 * This is a kludge as -Djava.protocol.handler.pkgs=org.janelia.alignment.protocol does not seem to work with Jetty.
 */
public class S3Opener extends ij.io.Opener {

    private S3Handler handler;
    private String scheme;
    private Map<String, String> s3Parameters;

    public S3Opener() {
        super();
        this.handler = null;
        this.scheme = "s3://";
        this.s3Parameters = new LinkedHashMap<String, String>();
    }

    public S3Opener(final String scheme, final Map<String, String> s3Parameters) {
        super();
        this.handler = null;
        this.scheme = scheme;
        this.s3Parameters = s3Parameters;
    }

    @Override
    public ImagePlus openURL(final String url) {

        ImagePlus imagePlus = null;

        if (url.startsWith(this.scheme)) {

            String name = "";
            final int index = url.lastIndexOf('/');
            if (index > 0) {
                name = url.substring(index + 1);
            }

            try {

                if (handler == null) {
                    buildS3Handler();
                }

                final URL u = new URL(null, url, handler);
                final URLConnection uc = u.openConnection();

                // assumes content type is always available, should be ok
                final MediaType contentType = MediaType.parse(uc.getContentType());

                final String lowerCaseUrl = url.toLowerCase(Locale.US);

                // honor content type over resource naming conventions, check for most common source image types first
                if (contentType.equals(MediaType.TIFF)) {
                    imagePlus = super.openTiff(u.openStream(), name);
                } else if (contentType.equals(MediaType.PNG)) {
                    imagePlus = openPngUsingURL(name, u);
                } else if (contentType.equals(MediaType.JPEG) || contentType.equals(MediaType.GIF)) {
                    imagePlus = openJpegOrGifUsingURL(name, u);
                } else if (lowerCaseUrl.endsWith(".tif") || lowerCaseUrl.endsWith(".tiff")) {
                    imagePlus = super.openTiff(u.openStream(), name);
                } else if (lowerCaseUrl.endsWith(".png")) {
                    imagePlus = openPngUsingURL(name, u);
                } else if (lowerCaseUrl.endsWith(".jpg") || lowerCaseUrl.endsWith(".gif")) {
                    imagePlus = openJpegOrGifUsingURL(name, u);
                } else {
                    throw new IOException("unsupported content type " + contentType + " for " + url);
                }

            } catch (final Throwable t) {
                // null imagePlus will be returned and handled upstream, no need to raise exception here
                LOG.error("failed to load " + url, t);
            }

        } else {
            imagePlus = super.openURL(url);
        }

        return imagePlus;
    }

    /* The following are based on protected methods from ij.io.Opener. */
    private ImagePlus openJpegOrGifUsingURL(final String title,
                                            final URL url) {
        final Image img = Toolkit.getDefaultToolkit().createImage(url);
        return new ImagePlus(title, img);
    }

    private ImagePlus openPngUsingURL(final String title,
                                      final URL url)
            throws IOException {
        final Image img;
        final InputStream in = url.openStream();
        img = ImageIO.read(in);
        return new ImagePlus(title, img);
    }

    private synchronized void buildS3Handler() throws IOException {
        if (handler == null) {
            try {
                final AmazonS3ClientBuilder clientBuilder = AmazonS3ClientBuilder.standard();

                if (this.s3Parameters.containsKey("aws_secret_access_key") && this.s3Parameters.containsKey("aws_access_key_id")){
                    final AWSCredentials credentials = new BasicAWSCredentials(this.s3Parameters.get("aws_access_key_id"), this.s3Parameters.get("aws_secret_access_key"));
                    final AWSCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(credentials);
                    clientBuilder.setCredentials(credentialsProvider);
                } else if (this.s3Parameters.containsKey("profile_name")) {
                    final AWSCredentialsProvider credentialsProvider = new ProfileCredentialsProvider(this.s3Parameters.get("profile_name"));
                    clientBuilder.setCredentials(credentialsProvider);
                } else {
                    final AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();
                    clientBuilder.setCredentials(credentialsProvider);
                }

                String region = (String) this.s3Parameters.getOrDefault("region_name", clientBuilder.getRegion());
                if (this.s3Parameters.containsKey("endpoint_url")) {
                    final EndpointConfiguration endpointConfig = new EndpointConfiguration(this.s3Parameters.get("endpoint_url"), region);
                    final AmazonS3 s3Client = clientBuilder.withEndpointConfiguration(endpointConfig).build();
                    handler = new S3Handler(s3Client);
                } else {
                    final AmazonS3 s3Client = clientBuilder.withRegion(region).build();
                    handler = new S3Handler(s3Client);
                }

            } catch (final AmazonServiceException ase) {
                throw new IOException("Amazon S3 service failure for error type " + ase.getErrorType(), ase);
            } catch (final AmazonClientException ace) {
                throw new IOException("Amazon S3 client failure", ace);
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(S3Opener.class);
}
